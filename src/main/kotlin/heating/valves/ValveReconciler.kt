package heating.valves

import heating.Periodic
import heating.rooms.RoomService
import heating.rooms.ZoneControl
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Sends a valve its zone's target again when a command was lost. Battery valves sleep and
 * Zigbee2MQTT drops a write the valve is not awake for, with no error reaching the service.
 *
 * A valve that has not reported anything different since our last command did not take it. One that
 * reported a change did: that is a knob turn or the valve catching up, and is left to ScheduleControl.
 * Re-sends are quiet (no log, no feed event) and an offline valve is not sent anything until it is back.
 */
class ValveReconciler(
    private val valves: ValveRegistry,
    private val rooms: RoomService,
    private val zones: ZoneControl,
    private val commander: ValveCommander,
    private val now: () -> Instant,
) {
    private data class Seen(val setpoint: Double?, val mode: String?, val online: Boolean)

    private val seen = ConcurrentHashMap<String, Seen>()
    private val changedAt = ConcurrentHashMap<String, Instant>()
    private val resentAt = ConcurrentHashMap<String, Instant>()

    fun start() = valves.onStateChanged(::reported)

    val tick = Periodic { _ -> valves.all().forEach { check(it, returned = false) } }

    private fun reported(valve: Valve) {
        val current = Seen(valve.reportedSetpoint, valve.systemMode, valve.online)
        val previous = seen.put(valve.name, current) ?: return
        if (previous.setpoint != current.setpoint || previous.mode != current.mode) changedAt[valve.name] = now()
        if (!previous.online && current.online) check(valve, returned = true)
    }

    /** Send [valve] its target again if it did not take the last command. [returned]: it just came back online, so no waiting. */
    private fun check(valve: Valve, returned: Boolean) {
        if (!valve.online) return
        val sentAt = commander.lastCommandAt(valve.name) ?: return
        if ((changedAt[valve.name] ?: Instant.MIN) > sentAt) return
        val time = now()
        if (!returned) {
            if (Duration.between(sentAt, time) < GRACE) return
            if (resentAt[valve.name]?.let { Duration.between(it, time) < RESEND } == true) return
        }
        val zone = rooms.roomOf(valve.name)?.zones()?.firstOrNull { valve.name in it.valves } ?: return
        val target = zones.effectiveTarget(zone.settings)
        val setpoint = valve.reportedSetpoint
        val lost = if (target == null) valve.systemMode != null && valve.systemMode != "off"
        else valve.systemMode == "off" || (setpoint != null && setpoint != target)
        if (!lost) return
        resentAt[valve.name] = time
        if (target == null) commander.off(valve.name) else commander.heatTo(valve.name, target)
    }

    companion object {
        /** How long a valve gets to take a command before it counts as lost. */
        val GRACE: Duration = Duration.ofMinutes(2)
        val RESEND: Duration = Duration.ofMinutes(5)
    }
}
