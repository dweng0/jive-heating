package heating.rooms

import heating.holiday.Holiday
import java.time.ZonedDateTime

/**
 * What a zone should be heating to right now. Precedence, highest first:
 * holiday > boost > valve-knob override > mode (manual target / schedule block / off).
 * Returns null when the zone is off.
 *
 * A holiday does not touch zones that are off; a boost does heat an off zone.
 */
object EffectiveTarget {
    fun of(settings: ZoneSettings, now: ZonedDateTime, holiday: Holiday? = null): Double? {
        if (settings.mode != Mode.OFF && holiday?.isActive(now) == true) return holiday.target
        activeBoost(settings, now)?.let { return it.target }
        return when (settings.mode) {
            Mode.OFF -> null
            Mode.MANUAL -> settings.manualTarget
            Mode.SCHEDULE -> activeOverride(settings, now)?.target ?: currentBlock(settings.schedule, now).target
        }
    }

    /** The boost if it has not yet ended. */
    fun activeBoost(settings: ZoneSettings, now: ZonedDateTime): Boost? =
        settings.boost?.takeIf { now.toInstant().isBefore(ZonedDateTime.parse(it.endsAt).toInstant()) }

    /** The knob override if it has not yet run out. */
    fun activeOverride(settings: ZoneSettings, now: ZonedDateTime): KnobOverride? =
        settings.override?.takeIf { now.toInstant().isBefore(ZonedDateTime.parse(it.until).toInstant()) }

    /**
     * The block in force at [now]: the last block today whose start is not after the local time.
     * A block inside the spring-forward gap takes effect when the clocks jump past it.
     */
    fun currentBlock(schedule: WeekSchedule, now: ZonedDateTime): Block = BlockTimes.current(schedule, now)
}
