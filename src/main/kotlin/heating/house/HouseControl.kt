package heating.house

import heating.Actor
import heating.ApiException
import heating.actionLog
import heating.logged
import heating.activity.ActivityLog
import heating.activity.displayName
import heating.boost.BoostControl
import heating.rooms.Mode
import heating.rooms.RoomService
import heating.rooms.Zone
import heating.rooms.ZoneControl
import kotlinx.serialization.json.JsonPrimitive

/**
 * Whole-house actions: the same change applied to every zone that has valves, through the same
 * code as a single zone so the same rules apply. Everything is checked before anything changes.
 * Passing room ids limits the change to those rooms (a group); [label] names them in the log
 * ("house", or "group Upstairs"). The activity feed gets one event for the whole change.
 */
class HouseControl(
    private val rooms: RoomService,
    private val zones: ZoneControl,
    private val boosts: BoostControl,
    private val activity: ActivityLog? = null,
) {
    private fun zonesWithValves(roomIds: Collection<String>?): List<Zone> =
        rooms.list().filter { roomIds == null || it.id in roomIds }.flatMap { it.zones() }.filter { it.valves.isNotEmpty() }

    /** One feed event for a change to [zonesChanged]: its name is the group's, or "Whole house". */
    private fun record(kind: String, label: String, zonesChanged: List<Zone>, value: JsonPrimitive?) {
        val ids = zonesChanged.map { it.roomId }.distinct()
        val names = ids.mapNotNull { id -> rooms.list().firstOrNull { it.id == id }?.name }
        val name = if (label == HouseControl.HOUSE) "Whole house" else label.removePrefix("group ")
        activity?.record(kind, Actor.name?.let(::displayName), name, value, rooms = names, roomIds = ids)
    }

    private fun <T> quietly(block: () -> T): T = if (activity != null) activity.quiet(block) else block()

    /** Every zone to [mode]. Back to schedule also drops boosts and knob overrides. */
    fun setMode(mode: Mode, target: Double?, roomIds: Collection<String>? = null, label: String = HOUSE) {
        if (mode == Mode.MANUAL && target == null) throw ApiException.badRequest("manual needs a target")
        val valid = target?.let(ZoneControl::validTarget)
        val targetText = if (mode == Mode.MANUAL && valid != null) " target ${valid.logged()}" else ""
        actionLog.info("{} mode {}{}{}", label, mode.name.lowercase(), targetText, Actor.by())
        val changed = zonesWithValves(roomIds)
        quietly {
            changed.forEach { zone ->
                if (mode == Mode.SCHEDULE) rooms.updateZone(zone.id) { it.copy(boost = null, override = null) }
                zones.setMode(zone.id, mode, valid)
            }
        }
        if (mode == Mode.MANUAL) record("setpoint", label, changed, JsonPrimitive(valid))
        else record("mode", label, changed, JsonPrimitive(mode.name.lowercase()))
    }

    fun boost(minutes: Int, target: Double?, roomIds: Collection<String>? = null, label: String = HOUSE) {
        val valid = boosts.check(minutes, target)
        actionLog.info("{} boost {} min to {}{}", label, minutes, valid.logged(), Actor.by())
        val changed = zonesWithValves(roomIds)
        quietly { changed.forEach { boosts.boost(it.id, minutes, valid) } }
        record("boost", label, changed, JsonPrimitive(valid))
    }

    fun cancelBoosts(roomIds: Collection<String>? = null, label: String = HOUSE) {
        actionLog.info("{} boost cancelled{}", label, Actor.by())
        val changed = zonesWithValves(roomIds).filter { it.settings.boost != null }
        quietly { changed.forEach { boosts.cancel(it.id) } }
        if (changed.isNotEmpty()) record("boost", label, changed, null)
    }

    companion object {
        const val HOUSE = "house"
    }
}
