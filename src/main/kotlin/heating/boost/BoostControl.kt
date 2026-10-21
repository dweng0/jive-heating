package heating.boost

import heating.notify.ActionTracker
import heating.notify.Who

import heating.Actor
import heating.activity.displayName
import heating.ApiException
import heating.actionLog
import heating.logged
import heating.zoneName
import heating.Periodic
import heating.holiday.HolidayRepository
import heating.rooms.Boost
import heating.rooms.RoomService
import heating.rooms.ScheduleControl
import heating.rooms.Zone
import heating.rooms.ZoneControl
import heating.rooms.ZoneSettings
import heating.valves.ValveCommander
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.ZonedDateTime

/** Starts, cancels and expires zone boosts. Register [expiry] on `heating.periodic`. */
class BoostControl(
    private val rooms: RoomService,
    private val zones: ZoneControl,
    private val commander: ValveCommander,
    private val holidays: HolidayRepository,
    private val now: () -> ZonedDateTime,
    private val actions: ActionTracker? = null,
    private val activity: heating.activity.ActivityLog? = null,
    /** The household's boost temperature, used when a boost gives no target. */
    private val defaultTarget: () -> Double = { BoostSettings().defaultTarget },
) {
    /** Clears boosts that have run out and pushes the zone's new target. */
    val expiry = Periodic { time ->
        rooms.list().filter { room -> room.settingsList().any { it.boost.expired(time) } }.forEach { room ->
            room.zones().filter { it.settings.boost.expired(time) }.forEach {
                actionLog.info("boost ended {}", room.zoneName(it.id))
                activity?.record("boost", null, room.zoneName(it.id), roomIds = listOf(room.id))
            }
            rooms.update(room.id) { r ->
                r.copy(
                    settings = r.settings.clearExpired(time),
                    valveSettings = r.valveSettings.mapValues { it.value.clearExpired(time) },
                )
            }
        }
        // The boost event above already says why the target changed.
        val apply = { rooms.list().flatMap { it.zones() }.forEach(zones::applyIfChanged) }
        if (activity != null) activity.quiet(apply) else apply()
    }

    /** The boost target to use, or an error if a boost of [minutes] to [target] is not allowed now. */
    fun check(minutes: Int, target: Double?): Double {
        if (minutes !in 30..180 || minutes % 30 != 0) {
            throw ApiException.badRequest("minutes must be 30 to 180 in steps of 30")
        }
        val valid = ZoneControl.validTarget(target ?: defaultTarget())
        if (holidays.current()?.isActive(now()) == true) throw ApiException.conflict("cannot boost during a holiday")
        return valid
    }

    fun boost(zoneId: String, minutes: Int, target: Double?): Zone {
        val valid = check(minutes, target)
        val time = now()
        val endsAt = time.plusMinutes(minutes.toLong()).format(ScheduleControl.ISO)
        Actor.name?.let { actions?.record(zoneId, Who.Person(it)) }
        val room = rooms.updateZone(zoneId) { it.copy(boost = Boost(valid, endsAt)) }
        val zone = room.zones().first { it.id == zoneId }
        actionLog.info("boost {} {} min to {}{}", room.zoneName(zoneId), minutes, valid.logged(), Actor.by())
        activity?.record("boost", Actor.name?.let(::displayName), room.zoneName(zoneId), JsonPrimitive(valid), roomIds = listOf(room.id))
        zones.apply(zone)
        zone.valves.forEach { valve ->
            commander.send(valve, buildJsonObject {
                put("temporary_mode_select", JsonPrimitive("timer"))
                put("timer_mode_target_temp", JsonPrimitive(valid))
                put("temporary_mode_duration", JsonPrimitive(minutes))
            })
        }
        return zone
    }

    fun cancel(zoneId: String): Zone {
        val room = rooms.updateZone(zoneId) { it.copy(boost = null) }
        val zone = room.zones().first { it.id == zoneId }
        actionLog.info("boost cancelled {}{}", room.zoneName(zoneId), Actor.by())
        activity?.record("boost", Actor.name?.let(::displayName), room.zoneName(zoneId), roomIds = listOf(room.id))
        zones.apply(zone)
        return zone
    }

    /** Remove every zone's boost (a holiday has started). Does not push; callers re-apply. */
    fun clearAll() {
        rooms.list().filter { room -> room.settingsList().any { it.boost != null } }.forEach { room ->
            room.zones().filter { it.settings.boost != null }.forEach { actionLog.info("boost cleared {} (holiday)", room.zoneName(it.id)) }
            rooms.update(room.id) { r ->
                r.copy(
                    settings = r.settings.copy(boost = null),
                    valveSettings = r.valveSettings.mapValues { it.value.copy(boost = null) },
                )
            }
        }
    }

    private fun heating.rooms.Room.settingsList() = listOf(settings) + valveSettings.values

    private fun Boost?.expired(time: ZonedDateTime) =
        this != null && !time.toInstant().isBefore(ZonedDateTime.parse(endsAt).toInstant())

    private fun ZoneSettings.clearExpired(time: ZonedDateTime) = if (boost.expired(time)) copy(boost = null) else this
}
