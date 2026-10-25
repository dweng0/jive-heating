package heating.valves

import heating.Periodic
import heating.activity.ActivityLog
import heating.rooms.RoomService
import heating.rooms.ZoneControl
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Sends a valve its zone's target again when a command was lost. Battery valves sleep and
 * Zigbee2MQTT drops a write the valve is not awake for, with no error reaching the service.
 *
 * A valve that has not reported anything different since our last command did not take it. One that
 * reported a change did: that is a knob turn or the valve catching up, and is left to ScheduleControl.
 * Re-sends back off: 10 s, 20 s, 1 min, 3 min, then every 5 min until the valve takes it. Re-sends are quiet
 * and an offline valve is not sent anything until it is back. After [FLAGGED_AFTER] re-sends the feed says
 * the valve is "unresponsive" (once per episode), and "responding" when it finally takes the command.
 */
class ValveReconciler(
    private val valves: ValveRegistry,
    private val rooms: RoomService,
    private val zones: ZoneControl,
    private val commander: ValveCommander,
    private val now: () -> Instant,
    private val activity: ActivityLog? = null,
) {
    private data class Seen(val setpoint: Double?, val mode: String?, val online: Boolean)

    private val seen = ConcurrentHashMap<String, Seen>()
    private val changedAt = ConcurrentHashMap<String, Instant>()
    /** Re-sends since the last command that was not ours; picks the wait in [BACKOFF]. */
    private val attempts = ConcurrentHashMap<String, Int>()

    /** Valves the feed currently calls unresponsive. */
    private val flagged = ConcurrentHashMap.newKeySet<String>()

    /** The last command time we caused ourselves, to tell our re-sends from a person's change. */
    private val ownCommandAt = ConcurrentHashMap<String, Instant>()

    fun start() = valves.onStateChanged(::reported)

    val tick = Periodic { _ -> valves.all().forEach { check(it, returned = false) } }

    private fun reported(valve: Valve) {
        val current = Seen(valve.reportedSetpoint, valve.systemMode, valve.online)
        val previous = seen.put(valve.name, current) ?: return
        if (previous.setpoint != current.setpoint || previous.mode != current.mode) changedAt[valve.name] = now()
        if (valve.name in flagged && valve.online && lost(valve) == false) {
            flagged -= valve.name
            attempts.remove(valve.name)
            say(valve, "responding")
        }
        if (!previous.online && current.online) check(valve, returned = true)
    }

    /** Whether [valve] still disagrees with its zone's target; null when it is in no zone. */
    private fun lost(valve: Valve): Boolean? {
        val zone = rooms.roomOf(valve.name)?.zones()?.firstOrNull { valve.name in it.valves } ?: return null
        val target = zones.effectiveTarget(zone.settings)
        val setpoint = valve.reportedSetpoint
        return if (target == null) valve.systemMode != null && valve.systemMode != "off"
        else valve.systemMode == "off" || (setpoint != null && setpoint != target)
    }

    private fun say(valve: Valve, state: String) {
        val room = rooms.roomOf(valve.name) ?: return
        activity?.record("unresponsive", null, room.name, JsonPrimitive(state), roomIds = listOf(room.id))
    }

    /** Send [valve] its target again if it did not take the last command. [returned]: it just came back online, so no waiting. */
    private fun check(valve: Valve, returned: Boolean) {
        if (!valve.online) return
        val sentAt = commander.lastCommandAt(valve.name) ?: return
        if ((changedAt[valve.name] ?: Instant.MIN) > sentAt) return
        val time = now()
        if (ownCommandAt[valve.name] != sentAt) attempts.remove(valve.name)
        val tries = attempts[valve.name] ?: 0
        if (!returned && Duration.between(sentAt, time) < BACKOFF[minOf(tries, BACKOFF.lastIndex)]) return
        val zone = rooms.roomOf(valve.name)?.zones()?.firstOrNull { valve.name in it.valves } ?: return
        val target = zones.effectiveTarget(zone.settings)
        if (lost(valve) != true) {
            attempts.remove(valve.name)
            return
        }
        if (target == null) commander.off(valve.name) else commander.heatTo(valve.name, target)
        attempts[valve.name] = tries + 1
        ownCommandAt[valve.name] = commander.lastCommandAt(valve.name)!!
        if (tries + 1 >= FLAGGED_AFTER && flagged.add(valve.name)) say(valve, "unresponsive")
    }

    companion object {
        /** Lost re-sends after which the feed calls a valve unresponsive. */
        const val FLAGGED_AFTER = 3

        /** How long after the last command a valve gets to take it before it is sent again; the last wait repeats. */
        val BACKOFF: List<Duration> = listOf(
            Duration.ofSeconds(10), Duration.ofSeconds(20), Duration.ofMinutes(1), Duration.ofMinutes(3), Duration.ofMinutes(5),
        )
    }
}
