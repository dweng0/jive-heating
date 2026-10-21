package heating.sensors

import heating.Change
import heating.ChangeNotifier
import heating.mqtt.MqttBus
import heating.mqtt.Z2mTopics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Knows every temperature sensor Zigbee2MQTT knows (any device other than a TRVZB whose
 * definition exposes `temperature`) and keeps their latest state. Fed only by MQTT: the bridge
 * device list, per-device state and availability.
 */
class SensorRegistry(
    private val bus: MqttBus,
    private val topics: Z2mTopics,
    private val changes: ChangeNotifier,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(SensorRegistry::class.java)
    private val sensors = linkedMapOf<String, Sensor>()
    private val lastHeard = HashMap<String, Instant>()
    private val silent = HashSet<String>()
    private val removedListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val stateListeners = CopyOnWriteArrayList<(Sensor) -> Unit>()

    fun start() {
        bus.subscribe(topics.bridgeDevices) { _, payload -> onDevices(payload) }
        bus.subscribe(topics.allAvailability) { topic, payload -> onAvailability(topics.valveName(topic), payload) }
        bus.subscribe(topics.allStates) { topic, payload -> onState(topics.valveName(topic), payload) }
    }

    fun all(): List<Sensor> = synchronized(this) { sensors.values.toList() }

    operator fun get(name: String): Sensor? = synchronized(this) { sensors[name] }

    /** True while [name] is offline only because it stopped reporting (see [markSilent]). */
    fun isSilent(name: String): Boolean = synchronized(this) { name in silent }

    /** Marks sensors that have sent nothing for [limit] as offline. A later message brings them back. */
    fun markSilent(limit: Duration) {
        val now = clock.instant()
        val gone = synchronized(this) {
            sensors.values.filter { it.online && Duration.between(lastHeard[it.name] ?: now, now) >= limit }
                .map { sensor ->
                    silent += sensor.name
                    sensor.copy(online = false).also { sensors[sensor.name] = it }
                }
        }
        gone.forEach { sensor ->
            log.warn("sensor '{}' silent for {} min, treating as offline", sensor.name, limit.toMinutes())
            notify(sensor)
        }
    }

    /** Called with the name of a sensor that disappeared from Zigbee2MQTT. */
    fun onRemoved(listener: (String) -> Unit) {
        removedListeners += listener
    }

    /** Called after a sensor's state changed (reading, battery, availability, silence). */
    fun onStateChanged(listener: (Sensor) -> Unit) {
        stateListeners += listener
    }

    private fun onDevices(payload: String) {
        val devices = parse(payload) as? JsonArray ?: return
        val names = devices.mapNotNull { element ->
            val device = element as? JsonObject ?: return@mapNotNull null
            val definition = device["definition"] as? JsonObject ?: return@mapNotNull null
            val model = (definition["model"] as? JsonPrimitive)?.content
            if (model != "TRVZB" && exposesTemperature(definition["exposes"])) {
                (device["friendly_name"] as? JsonPrimitive)?.content
            } else null
        }.toSet()

        val removed = synchronized(this) {
            names.forEach { name ->
                if (sensors.putIfAbsent(name, Sensor(name)) == null) lastHeard[name] = clock.instant()
            }
            val gone = sensors.keys - names
            gone.forEach { sensors.remove(it); lastHeard.remove(it); silent.remove(it) }
            gone
        }
        removed.forEach { name -> removedListeners.forEach { it(name) } }
        (names + removed).forEach { changes.emit(Change.SensorChanged(it)) }
    }

    /** Whether an exposes list (whose entries may nest more under `features`) has a `temperature` property. */
    private fun exposesTemperature(exposes: JsonElement?): Boolean = when (exposes) {
        is JsonArray -> exposes.any(::exposesTemperature)
        is JsonObject -> (exposes["property"] as? JsonPrimitive)?.content == "temperature" ||
            exposesTemperature(exposes["features"])
        else -> false
    }

    private fun onState(name: String, payload: String) {
        val updated = synchronized(this) {
            val current = sensors[name] ?: return
            val state = parse(payload) as? JsonObject ?: return
            lastHeard[name] = clock.instant()
            val recovered = silent.remove(name)
            current.copy(
                online = if (recovered) true else current.online,
                temperature = (state["temperature"] as? JsonPrimitive)?.doubleOrNull ?: current.temperature,
                battery = (state["battery"] as? JsonPrimitive)?.intOrNull ?: current.battery,
            ).also { sensors[name] = it }
        }
        notify(updated)
    }

    private fun onAvailability(name: String, payload: String) {
        val updated = synchronized(this) {
            val current = sensors[name] ?: return
            // Zigbee2MQTT sends {"state":"online"} or, in legacy mode, the bare string.
            val state = ((parse(payload) as? JsonObject)?.get("state") as? JsonPrimitive)?.content ?: payload.trim()
            lastHeard[name] = clock.instant()
            silent.remove(name)
            current.copy(online = state == "online").also { sensors[name] = it }
        }
        notify(updated)
    }

    private fun notify(sensor: Sensor) {
        stateListeners.forEach { it(sensor) }
        changes.emit(Change.SensorChanged(sensor.name))
    }

    private fun parse(payload: String) = runCatching { Json.parseToJsonElement(payload) }.getOrNull()
}
