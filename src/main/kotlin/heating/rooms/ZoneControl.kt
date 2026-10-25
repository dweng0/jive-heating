package heating.rooms

import heating.notify.ActionTracker
import heating.notify.Who

import heating.Actor
import heating.ApiException
import heating.actionLog
import heating.logged
import heating.zoneName
import heating.holiday.Holiday
import heating.valves.ValveCommander
import kotlinx.serialization.json.JsonPrimitive
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/** Changes what zones are told to do and pushes the result to their valves. */
class ZoneControl(
    private val rooms: RoomService,
    private val commander: ValveCommander,
    private val now: () -> ZonedDateTime,
    private val holiday: () -> Holiday? = { null },
    private val actions: ActionTracker? = null,
    private val activity: heating.activity.ActivityLog? = null,
    /** True while an admin has forced the boiler off: no zone has a target then. */
    private val forcedOff: () -> Boolean = { false },
) {
    private class AppliedTarget(val target: Double?)

    private val applied = java.util.concurrent.ConcurrentHashMap<String, AppliedTarget>()

    /** Set [mode]; [target] (manual target) is validated and rounded to 0.5. [log] false when the caller logs it. */
    fun setMode(zoneId: String, mode: Mode, target: Double?, log: Boolean = true): Room {
        val rounded = target?.let(::validTarget)
        Actor.name?.let { actions?.record(zoneId, Who.Person(it)) }
        val before = rooms.list().flatMap { it.zones() }.firstOrNull { it.id == zoneId }?.settings
        val room = rooms.updateZone(zoneId) { it.copy(mode = mode, manualTarget = rounded ?: it.manualTarget) }
        val zone = room.zones().first { it.id == zoneId }
        if (log) {
            val targetText = if (mode == Mode.MANUAL) " target ${zone.settings.manualTarget.logged()}" else ""
            actionLog.info("zone {} mode {}{}{}", room.zoneName(zoneId), mode.name.lowercase(), targetText, Actor.by())
        }
        Actor.name?.let { person ->
            val unchanged = before != null && before.mode == mode && (mode != Mode.MANUAL || before.manualTarget == zone.settings.manualTarget)
            if (!unchanged) {
                val who = heating.activity.displayName(person)
                if (mode == Mode.MANUAL) {
                    activity?.record("setpoint", who, room.zoneName(zoneId), JsonPrimitive(zone.settings.manualTarget), roomIds = listOf(room.id))
                } else {
                    activity?.record("mode", who, room.zoneName(zoneId), JsonPrimitive(mode.name.lowercase()), roomIds = listOf(room.id))
                }
            }
        }
        apply(zone)
        return room
    }

    /** What [settings] should heat to right now, including any holiday. */
    fun effectiveTarget(settings: ZoneSettings): Double? =
        if (forcedOff()) null else EffectiveTarget.of(settings, now(), holiday())

    /** Send a zone's effective target to its valves: heat to it, or off. */
    fun apply(zone: Zone) {
        val target = effectiveTarget(zone.settings)
        applied[zone.id] = AppliedTarget(target)
        zone.valves.forEach { valve -> if (target == null) commander.off(valve) else commander.heatTo(valve, target) }
    }

    /**
     * Push [zone] only if its effective target differs from what was last pushed. The first time a
     * zone is seen nothing is sent; its current target is just remembered. Used by periodic jobs.
     */
    fun applyIfChanged(zone: Zone) {
        val target = effectiveTarget(zone.settings)
        val last = applied[zone.id]
        if (last == null) applied[zone.id] = AppliedTarget(target) else if (last.target != target) {
            val name = rooms.list().firstOrNull { it.id == zone.roomId }?.zoneName(zone.id) ?: zone.id
            actionLog.info("zone {} now {} (was {})", name, target.text(), last.target.text())
            activity?.record("schedule", null, name, target?.let(::JsonPrimitive), roomIds = listOf(zone.roomId))
            apply(zone)
        }
    }

    private fun Double?.text() = this?.logged() ?: "off"

    /** Push every zone's effective target to its valves. */
    fun applyAll() = rooms.list().flatMap { it.zones() }.forEach(::apply)

    companion object {
        const val MIN_TARGET = 5.0
        const val MAX_TARGET = 30.0

        fun validTarget(target: Double): Double {
            if (target < MIN_TARGET || target > MAX_TARGET) {
                throw ApiException.badRequest("target must be between $MIN_TARGET and $MAX_TARGET")
            }
            return (target * 2).roundToInt() / 2.0
        }
    }
}
