package heating.energy

import heating.mqtt.MqttBus
import heating.mqtt.Z2mTopics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Latest known state of one electricity meter: a Zigbee2MQTT device that exposes both `energy`
 * (a running kWh total) and `power` (W), such as an electricity clamp or a smart plug.
 */
data class Meter(
    val name: String,
    val online: Boolean = true,
    /** The meter's running total in kWh; it only goes up, except when the device resets it. */
    val energy: Double? = null,
    val power: Double? = null,
    /** When the meter last sent a state message. */
    val lastReading: Instant? = null,
    /** When the service first saw the meter in the bridge device list. */
    val discovered: Instant,
    /** When Zigbee2MQTT last said the meter went offline; null while it is online. */
    val wentOffline: Instant? = null,
    /** When [energy] last changed (or first arrived). */
    val energyChanged: Instant? = null,
    /** kWh the power readings say the meter used since [energyChanged], up to [lastReading]. */
    val usedSinceEnergyChanged: Double = 0.0,
)

/**
 * Knows every electricity meter Zigbee2MQTT knows and keeps their latest readings. Fed only by MQTT:
 * the bridge device list, per-device state and availability, like valves and room sensors.
 */
class MeterRegistry(
    private val bus: MqttBus,
    private val topics: Z2mTopics,
    private val clock: Clock,
) {
    private val meters = linkedMapOf<String, Meter>()
    /** Every device in the bridge device list, meters or not. */
    private var devices = emptySet<String>()
    private val removedListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val onlineListeners = CopyOnWriteArrayList<(Meter) -> Unit>()
    private val readingListeners = CopyOnWriteArrayList<(Meter) -> Unit>()

    fun start() {
        bus.subscribe(topics.bridgeDevices) { _, payload -> onDevices(payload) }
        bus.subscribe(topics.allAvailability) { topic, payload -> onAvailability(topics.valveName(topic), payload) }
        bus.subscribe(topics.allStates) { topic, payload -> onState(topics.valveName(topic), payload) }
    }

    fun all(): List<Meter> = synchronized(this) { meters.values.toList() }

    operator fun get(name: String): Meter? = synchronized(this) { meters[name] }

    /** Whether Zigbee2MQTT knows a device called [name], even one that is not a meter, such as an on/off plug. */
    fun isDevice(name: String): Boolean = synchronized(this) { name in devices }

    /** Whether [meter] has sent no reading for [STALE_AFTER] (since it was discovered, if it never has). */
    fun isStale(meter: Meter): Boolean =
        Duration.between(meter.lastReading ?: meter.discovered, clock.instant()) >= STALE_AFTER

    /**
     * When [meter] stopped reporting, if it is not reporting now: when Zigbee2MQTT said it went offline, or
     * its last reading once it is stale.
     */
    fun offlineSince(meter: Meter): Instant? = when {
        !meter.online -> meter.wentOffline ?: meter.lastReading ?: meter.discovered
        isStale(meter) -> meter.lastReading ?: meter.discovered
        else -> null
    }

    /**
     * When [meter]'s energy total last changed, if since then its power readings say it should have used at
     * least [STUCK_AFTER_KWH] kWh: the total is not going up, so its use is undercounted. Null while it is
     * offline now, since there is then no power reading to go by.
     */
    fun totalStuckSince(meter: Meter): Instant? {
        if (offlineSince(meter) != null) return null
        val changed = meter.energyChanged ?: return null
        val sinceReading = used(meter.power, meter.lastReading ?: return null, clock.instant())
        return changed.takeIf { meter.usedSinceEnergyChanged + sinceReading >= STUCK_AFTER_KWH - 1e-9 }
    }

    /** The running kWh total of [name] if it is reporting; null while it is unknown, offline or stale. */
    fun reading(name: String): Double? = get(name)?.takeIf { it.online && !isStale(it) }?.energy

    /** Called with the name of a meter that disappeared from Zigbee2MQTT. */
    fun onRemoved(listener: (String) -> Unit) {
        removedListeners += listener
    }

    /** Called with a meter that has just gone offline or come back online. */
    fun onOnlineChanged(listener: (Meter) -> Unit) {
        onlineListeners += listener
    }

    /** Called with a meter each time it sends a state message, after its latest reading is kept. */
    fun onReading(listener: (Meter) -> Unit) {
        readingListeners += listener
    }

    private fun onDevices(payload: String) {
        val devices = parse(payload) as? JsonArray ?: return
        val all = devices.mapNotNull { ((it as? JsonObject)?.get("friendly_name") as? JsonPrimitive)?.content }.toSet()
        val names = devices.mapNotNull { element ->
            val device = element as? JsonObject ?: return@mapNotNull null
            val definition = device["definition"] as? JsonObject ?: return@mapNotNull null
            val model = (definition["model"] as? JsonPrimitive)?.content
            val exposed = properties(definition["exposes"])
            if (model != "TRVZB" && "energy" in exposed && "power" in exposed) {
                (device["friendly_name"] as? JsonPrimitive)?.content
            } else null
        }.toSet()

        val removed = synchronized(this) {
            this.devices = all
            names.forEach { name -> meters.putIfAbsent(name, Meter(name, discovered = clock.instant())) }
            val gone = meters.keys - names
            gone.forEach { meters.remove(it) }
            gone
        }
        removed.forEach { name -> removedListeners.forEach { it(name) } }
    }

    /** Every `property` in an exposes list, including those nested under `features`. */
    private fun properties(exposes: JsonElement?): Set<String> = when (exposes) {
        is JsonArray -> exposes.flatMap(::properties).toSet()
        is JsonObject -> properties(exposes["features"]) + listOfNotNull((exposes["property"] as? JsonPrimitive)?.content)
        else -> emptySet()
    }

    private fun onState(name: String, payload: String) {
        val meter = update(name) { current -> withReading(current, payload) } ?: return
        readingListeners.forEach { runCatching { it(meter) } }
    }

    private fun withReading(current: Meter, payload: String): Meter? {
        val state = parse(payload) as? JsonObject ?: return null
        val now = clock.instant()
        val energy = (state["energy"] as? JsonPrimitive)?.doubleOrNull ?: current.energy
        // The power last reported is taken to hold until this message.
        val used = current.lastReading?.let { used(current.power, it, now) } ?: 0.0
        val changed = energy != current.energy
        return current.copy(
            online = true,
            wentOffline = null,
            energy = energy,
            power = (state["power"] as? JsonPrimitive)?.doubleOrNull ?: current.power,
            lastReading = now,
            energyChanged = if (changed) now else current.energyChanged,
            usedSinceEnergyChanged = if (changed) 0.0 else current.usedSinceEnergyChanged + used,
        )
    }

    private fun onAvailability(name: String, payload: String) = update(name) { current ->
        // Zigbee2MQTT sends {"state":"online"} or, in legacy mode, the bare string.
        val state = ((parse(payload) as? JsonObject)?.get("state") as? JsonPrimitive)?.content ?: payload.trim()
        val online = state == "online"
        when {
            online -> current.copy(online = true, wentOffline = null)
            // A repeated "offline" keeps the time it first went.
            current.online -> current.copy(online = false, wentOffline = clock.instant())
            else -> current
        }
    }

    /** Replaces meter [name] with [change] of it (null: no change), then tells listeners if it went on or offline. */
    private fun update(name: String, change: (Meter) -> Meter?): Meter? {
        val (before, after) = synchronized(this) {
            val current = meters[name] ?: return null
            val next = change(current) ?: return null
            meters[name] = next
            current to next
        }
        if (before.online != after.online) onlineListeners.forEach { runCatching { it(after) } }
        return after
    }

    /** kWh used at [watts] from [from] to [to]. */
    private fun used(watts: Double?, from: Instant, to: Instant): Double =
        (watts ?: 0.0).coerceAtLeast(0.0) * Duration.between(from, to).toMillis() / 3_600_000_000.0

    private fun parse(payload: String) = runCatching { Json.parseToJsonElement(payload) }.getOrNull()

    companion object {
        /** A meter that has sent nothing for this long is stale: its use is unknown, not zero. */
        val STALE_AFTER: Duration = Duration.ofMinutes(30)

        /**
         * kWh a meter's power must say it used, with no change in its total, before the total counts as stuck.
         * Totals move in 0.01 kWh steps, so a low-power plug may rightly show no change for hours.
         */
        const val STUCK_AFTER_KWH = 0.05
    }
}
