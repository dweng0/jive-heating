package heating.demand

import heating.api.averageTemperature
import heating.logged
import heating.rooms.Zone
import heating.rooms.ZoneSettings
import heating.valves.Valve
import heating.valves.ValveRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether a zone is calling for heat. Starts calling when the temperature (the room's online
 * sensor, else the average of online valves) is [ON_BELOW] or more under the effective target, keeps calling until it reaches the
 * target (hysteresis), and also calls while any online valve reports running_state "heat".
 * Below target only counts while some online valve could open: it is not switched off and its own
 * reported setpoint is above the zone's temperature, or unknown, or it was sent a command moments ago. A valve that kept a lower setpoint stays shut, so the boiler
 * would heat nothing.
 */
class ZoneDemand(
    private val valves: ValveRegistry,
    /** The zone's effective target including holiday and boost (ZoneControl.effectiveTarget). */
    private val effectiveTarget: (ZoneSettings) -> Double?,
    /** The room's online sensor reading, if it has one (SensorControl.temperatureOf). */
    private val sensorTemperature: (roomId: String) -> Double? = { null },
    /** Whether a valve got a setpoint command moments ago and may still report its old one (ValveCommander.settling). */
    private val settling: (valve: String) -> Boolean = { false },
) {
    private val calling = ConcurrentHashMap<String, Boolean>()

    fun isCalling(zone: Zone): Boolean = why(zone) != null

    /**
     * Why [zone] is calling for heat ("19.0 -> 21.0", or "valve heating"), or null when it is not.
     * Same rules and side effects as [isCalling].
     */
    fun why(zone: Zone): String? {
        val target = effectiveTarget(zone.settings)
        if (target == null) {
            calling[zone.id] = false
            return null
        }
        val zoneValves = zone.valves.mapNotNull { valves[it] }.filter { it.online }
        val current = temperature(zone)
        if (current == null) calling[zone.id] = false else {
            when {
                current <= target - ON_BELOW -> calling[zone.id] = true
                current >= target -> calling[zone.id] = false
            }
        }
        return when {
            calling[zone.id] == true && zoneValves.any { it.canOpenAt(current) } -> "${current?.logged()} -> ${target.logged()}"
            zoneValves.any { it.runningState == "heat" } -> "valve heating"
            else -> null
        }
    }

    /** The zone's temperature: the room's online sensor, else the average of its online valves. */
    fun temperature(zone: Zone): Double? =
        sensorTemperature(zone.roomId) ?: averageTemperature(zone.valves.mapNotNull { valves[it] }.filter { it.online })

    private fun Valve.canOpenAt(current: Double?): Boolean {
        // Valves report late and out of order: just after a command, trust the command.
        if (settling(name)) return true
        if (systemMode == "off") return false
        val setpoint = reportedSetpoint ?: return true
        return current == null || setpoint > current
    }

    companion object {
        const val ON_BELOW = 0.5
    }
}
