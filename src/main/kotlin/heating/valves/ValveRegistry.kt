package heating.valves

import heating.Change
import heating.ChangeNotifier
import heating.mqtt.MqttBus
import heating.mqtt.Z2mTopics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Knows every TRVZB that Zigbee2MQTT knows, and keeps their latest state.
 * Fed only by MQTT: the bridge device list, per-valve state and availability.
 */
class ValveRegistry(
    private val bus: MqttBus,
    private val topics: Z2mTopics,
    private val changes: ChangeNotifier,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(ValveRegistry::class.java)
    private val valves = linkedMapOf<String, Valve>()
    private val lastHeard = HashMap<String, Instant>()
    private val silent = HashSet<String>()
    private val removedListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val stateListeners = CopyOnWriteArrayList<(Valve) -> Unit>()

    fun start() {
        bus.subscribe(topics.bridgeDevices) { _, payload -> onDevices(payload) }
        bus.subscribe(topics.allAvailability) { topic, payload -> onAvailability(topics.valveName(topic), payload) }
        bus.subscribe(topics.allStates) { topic, payload -> onState(topics.valveName(topic), payload) }
    }

    fun all(): List<Valve> = synchronized(this) { valves.values.toList() }

    operator fun get(name: String): Valve? = synchronized(this) { valves[name] }

    /** True while [name] is offline only because it stopped reporting (see [markSilent]). */
    fun isSilent(name: String): Boolean = synchronized(this) { name in silent }

    /**
     * Marks valves that have sent nothing for [limit] as offline, so a dead valve cannot keep
     * heat demand on with a stale reading. A later message from the valve brings it back.
     */
    fun markSilent(limit: Duration) {
        val now = clock.instant()
        val gone = synchronized(this) {
            valves.values.filter { it.online && Duration.between(lastHeard[it.name] ?: now, now) >= limit }
                .map { valve ->
                    silent += valve.name
                    valves[valve.name] = valve.copy(online = false)
                    valve.name
                }
        }
        gone.forEach { name ->
            log.warn("valve '{}' silent for {} min, treating as offline", name, limit.toMinutes())
            valves[name]?.let { notify(it) }
        }
    }

    /** Called with the name of a valve that disappeared from Zigbee2MQTT. */
    fun onRemoved(listener: (String) -> Unit) {
        removedListeners += listener
    }

    /** Called after a valve's state changed (temperature, setpoint, running state, availability...). */
    fun onStateChanged(listener: (Valve) -> Unit) {
        stateListeners += listener
    }

    private fun onDevices(payload: String) {
        val devices = parse(payload) as? JsonArray ?: return
        val trvNames = devices.mapNotNull { element ->
            val device = element as? JsonObject ?: return@mapNotNull null
            val model = (device["definition"] as? JsonObject)?.get("model")?.jsonPrimitive?.content
                ?: device["model_id"]?.jsonPrimitive?.content
            if (model == "TRVZB") device["friendly_name"]?.jsonPrimitive?.content else null
        }.toSet()

        val removed = synchronized(this) {
            trvNames.forEach { name ->
                if (valves.putIfAbsent(name, Valve(name)) == null) lastHeard[name] = clock.instant()
            }
            val gone = valves.keys - trvNames
            gone.forEach { valves.remove(it); lastHeard.remove(it); silent.remove(it) }
            gone
        }
        removed.forEach { name -> removedListeners.forEach { it(name) } }
        (trvNames + removed).forEach { changes.emit(Change.ValveChanged(it)) }
    }

    private fun onState(name: String, payload: String) {
        val state = parse(payload) as? JsonObject ?: return
        val updated = synchronized(this) {
            val current = valves[name] ?: return
            lastHeard[name] = clock.instant()
            val recovered = silent.remove(name)
            current.copy(
                online = if (recovered) true else current.online,
                currentTemperature = state.double("local_temperature") ?: current.currentTemperature,
                reportedSetpoint = state.double("occupied_heating_setpoint") ?: current.reportedSetpoint,
                runningState = state.string("running_state") ?: current.runningState,
                systemMode = state.string("system_mode") ?: current.systemMode,
                frostProtection = state.double("frost_protection_temperature") ?: current.frostProtection,
                battery = state["battery"]?.jsonPrimitive?.intOrNull ?: current.battery,
                openWindow = state.string("open_window")?.let { it == "ON" } ?: current.openWindow,
                childLock = state.string("child_lock")?.let { it == "LOCK" } ?: current.childLock,
            ).also { valves[name] = it }
        }
        notify(updated)
    }

    private fun onAvailability(name: String, payload: String) {
        // Zigbee2MQTT sends {"state":"online"} or, in legacy mode, the bare string.
        val state = (parse(payload) as? JsonObject)?.string("state") ?: payload.trim()
        val updated = synchronized(this) {
            val current = valves[name] ?: return
            lastHeard[name] = clock.instant()
            silent.remove(name)
            current.copy(online = state == "online").also { valves[name] = it }
        }
        notify(updated)
    }

    private fun notify(valve: Valve) {
        stateListeners.forEach { it(valve) }
        changes.emit(Change.ValveChanged(valve.name))
    }

    private fun parse(payload: String) = runCatching { Json.parseToJsonElement(payload) }
        .onFailure { log.warn("ignoring non-JSON payload: {}", payload.take(200)) }
        .getOrNull()

    private fun JsonObject.double(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
