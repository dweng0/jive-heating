package heating.holiday

import heating.Actor
import heating.activity.displayName
import heating.ApiException
import heating.actionLog
import heating.logged
import heating.Change
import heating.ChangeNotifier
import heating.Periodic
import heating.boost.BoostControl
import heating.rooms.ScheduleControl
import heating.rooms.ZoneControl
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Starts, schedules and ends the household holiday. Register [transitions] on `heating.periodic`. */
class HolidayControl(
    private val repository: HolidayRepository,
    private val boosts: BoostControl,
    private val zones: ZoneControl,
    private val changes: ChangeNotifier,
    private val timezone: ZoneId,
    private val now: () -> ZonedDateTime,
    private val activity: heating.activity.ActivityLog? = null,
) {
    private fun record(value: String) =
        activity?.record("holiday", Actor.name?.let(::displayName), null, kotlinx.serialization.json.JsonPrimitive(value))

    @Volatile private var wasActive: Boolean? = null

    /** Ends a holiday that has run out and reacts when one becomes active. */
    val transitions = Periodic { time ->
        if (refresh(time)) {
            if (wasActive == true) actionLog.info("holiday active until {}", repository.current()?.until)
            else actionLog.info("holiday over")
            record(if (wasActive == true) "start" else "end")
            zones.applyAll()
            changes.emit(Change.StatusChanged)
        }
    }

    fun current(): Holiday? = repository.current()

    /** [from] null means now. Times without an offset are read in the configured time zone. */
    fun start(from: String?, until: String, target: Double?): Holiday {
        val time = now()
        val start = from?.let(::parse) ?: time
        val end = parse(until)
        if (!end.toInstant().isAfter(start.toInstant())) throw ApiException.badRequest("until must be after from")
        val holiday = Holiday(
            start.format(ScheduleControl.ISO),
            end.format(ScheduleControl.ISO),
            ZoneControl.validTarget(target ?: DEFAULT_TARGET),
        )
        actionLog.info("holiday start {} until {} target {}{}", holiday.from, holiday.until, holiday.target.logged(), Actor.by())
        repository.save(holiday)
        record("start")
        refresh(time)
        zones.applyAll()
        changes.emit(Change.StatusChanged)
        return holiday
    }

    fun end() {
        actionLog.info("holiday ended{}", Actor.by())
        record("end")
        repository.save(null)
        refresh(now())
        zones.applyAll()
        changes.emit(Change.StatusChanged)
    }

    /** Returns true when the holiday just became active or inactive. */
    private fun refresh(time: ZonedDateTime): Boolean {
        var holiday = repository.current()
        if (holiday != null && holiday.isOver(time)) {
            repository.save(null)
            holiday = null
        }
        val active = holiday?.isActive(time) == true
        if (active) boosts.clearAll()
        val before = wasActive
        wasActive = active
        return before != null && before != active
    }

    private fun parse(text: String): ZonedDateTime = try {
        ZonedDateTime.parse(text).withZoneSameInstant(timezone)
    } catch (_: Exception) {
        try {
            LocalDateTime.parse(text).atZone(timezone)
        } catch (_: Exception) {
            throw ApiException.badRequest("invalid date-time: $text")
        }
    }

    companion object {
        const val DEFAULT_TARGET = 12.0
    }
}
