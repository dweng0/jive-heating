package heating.valves

import heating.mqtt.MqttBus
import heating.mqtt.Z2mTopics
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Sends commands to valves through Zigbee2MQTT. The only place that writes `<valve>/set` or `/get`. */
class ValveCommander(
    private val bus: MqttBus,
    private val topics: Z2mTopics,
    private val now: () -> Instant = Instant::now,
) {

    private val lastSetpoint = ConcurrentHashMap<String, Double>()

    /** Setpoints sent (or implied, see [off]) per valve, newest last, with when they were sent. */
    private val recent = ConcurrentHashMap<String, MutableList<Pair<Double, Instant>>>()

    /** The occupied_heating_setpoint most recently sent to [valve], or null if none since startup. */
    fun lastSentSetpoint(valve: String): Double? = lastSetpoint[valve]

    /**
     * Whether a setpoint [valve] reports is most likely the valve catching up with our own commands
     * rather than someone turning its knob: it matches a setpoint we sent within [ECHO_MEMORY]
     * ([matchesRecent]), or it arrives within [SETTLE] of our last setpoint command ([settling]).
     * Valves report late and out of order, and treating such a report as a knob turn makes the
     * service fight its own echoes.
     */
    fun matchesRecent(valve: String, reported: Double): Boolean {
        val time = now()
        val sent = recent[valve]?.let { synchronized(it) { it.toList() } } ?: return reported == lastSetpoint[valve]
        return sent.any { (setpoint, at) -> setpoint == reported && Duration.between(at, time) <= ECHO_MEMORY }
    }

    /** Whether [valve] got a setpoint command less than [SETTLE] ago and may still be catching up. */
    fun settling(valve: String): Boolean {
        val last = recent[valve]?.let { synchronized(it) { it.lastOrNull() } }?.second ?: return false
        return Duration.between(last, now()) < SETTLE
    }

    fun send(valve: String, command: JsonObject) {
        (command["occupied_heating_setpoint"] as? JsonPrimitive)?.doubleOrNull?.let {
            lastSetpoint[valve] = it
            remember(valve, it)
        }
        bus.publish(topics.set(valve), command.toString())
    }

    private fun remember(valve: String, setpoint: Double) {
        val list = recent.getOrPut(valve) { mutableListOf() }
        synchronized(list) {
            val cutoff = now().minus(ECHO_MEMORY)
            list.removeAll { it.second.isBefore(cutoff) }
            list += setpoint to now()
        }
    }

    fun read(valve: String, vararg attributes: String) =
        bus.publish(topics.get(valve), buildJsonObject { attributes.forEach { put(it, JsonPrimitive("")) } }.toString())

    /** Heat to [target] (°C). */
    fun heatTo(valve: String, target: Double) = send(valve, buildJsonObject {
        put("system_mode", JsonPrimitive("heat"))
        put("occupied_heating_setpoint", JsonPrimitive(target))
    })

    /** Off: the valve's own frost protection still applies, and it reports that as its setpoint. */
    fun off(valve: String) {
        remember(valve, FROST_PROTECTION)
        send(valve, buildJsonObject { put("system_mode", JsonPrimitive("off")) })
    }

    fun setOpenWindow(valve: String, enabled: Boolean) =
        send(valve, buildJsonObject { put("open_window", JsonPrimitive(if (enabled) "ON" else "OFF")) })

    fun setChildLock(valve: String, locked: Boolean) =
        send(valve, buildJsonObject { put("child_lock", JsonPrimitive(if (locked) "LOCK" else "UNLOCK")) })

    fun setSmartTemperatureControl(valve: String, enabled: Boolean) =
        send(valve, buildJsonObject { put("smart_temperature_control", JsonPrimitive(enabled)) })

    fun setFrostProtection(valve: String) =
        send(valve, buildJsonObject { put("frost_protection_temperature", JsonPrimitive(FROST_PROTECTION.toInt())) })

    /** Measure the room with an external sensor reading (°C) instead of the valve's own sensor. */
    fun useExternalSensor(valve: String, reading: Double) = send(valve, buildJsonObject {
        put("temperature_sensor_select", JsonPrimitive("external"))
        put("external_temperature_input", JsonPrimitive(reading))
    })

    /** Pass a new external sensor reading (°C) to a valve already using its external sensor. */
    fun externalTemperature(valve: String, reading: Double) =
        send(valve, buildJsonObject { put("external_temperature_input", JsonPrimitive(reading)) })

    /** Go back to the valve's own temperature sensor. */
    fun useInternalSensor(valve: String) =
        send(valve, buildJsonObject { put("temperature_sensor_select", JsonPrimitive("internal")) })

    /** Settings every valve gets when it is put into a room. */
    fun applyDefaults(valve: String) = send(valve, buildJsonObject {
        put("open_window", JsonPrimitive("ON"))
        put("frost_protection_temperature", JsonPrimitive(FROST_PROTECTION.toInt()))
        put("smart_temperature_control", JsonPrimitive(true))
        put("child_lock", JsonPrimitive("UNLOCK"))
    })

    companion object {
        const val FROST_PROTECTION = 7.0
        /** How long a sent setpoint is remembered as one the valve may still echo back. */
        val ECHO_MEMORY: Duration = Duration.ofMinutes(5)
        /** After a setpoint command, reports this soon are the valve settling, not a knob turn. */
        val SETTLE: Duration = Duration.ofSeconds(60)
    }
}
