package heating.rooms

import kotlinx.serialization.Serializable
import java.time.DayOfWeek

/** One schedule change: from [start] ("HH:mm", local time) heat to [target] °C. */
@Serializable
data class Block(val start: String, val target: Double)

/** One day's changes: 1-6 blocks, first at 00:00, in time order. */
@Serializable
data class DaySchedule(val blocks: List<Block>)

/** A zone's weekly schedule. Same shape as the WeekSchedule schema in openapi.json. */
@Serializable
data class WeekSchedule(
    val monday: DaySchedule,
    val tuesday: DaySchedule,
    val wednesday: DaySchedule,
    val thursday: DaySchedule,
    val friday: DaySchedule,
    val saturday: DaySchedule,
    val sunday: DaySchedule,
) {
    operator fun get(day: DayOfWeek): DaySchedule = when (day) {
        DayOfWeek.MONDAY -> monday
        DayOfWeek.TUESDAY -> tuesday
        DayOfWeek.WEDNESDAY -> wednesday
        DayOfWeek.THURSDAY -> thursday
        DayOfWeek.FRIDAY -> friday
        DayOfWeek.SATURDAY -> saturday
        DayOfWeek.SUNDAY -> sunday
    }

    fun with(day: DayOfWeek, schedule: DaySchedule): WeekSchedule = when (day) {
        DayOfWeek.MONDAY -> copy(monday = schedule)
        DayOfWeek.TUESDAY -> copy(tuesday = schedule)
        DayOfWeek.WEDNESDAY -> copy(wednesday = schedule)
        DayOfWeek.THURSDAY -> copy(thursday = schedule)
        DayOfWeek.FRIDAY -> copy(friday = schedule)
        DayOfWeek.SATURDAY -> copy(saturday = schedule)
        DayOfWeek.SUNDAY -> copy(sunday = schedule)
    }

    companion object {
        /** Every day: 00:00 16.0, 06:30 20.0, 08:30 16.0, 16:30 20.0, 22:30 16.0 (BDD: New zones get the default schedule). */
        val DEFAULT_DAY = DaySchedule(
            listOf(
                Block("00:00", 16.0),
                Block("06:30", 20.0),
                Block("08:30", 16.0),
                Block("16:30", 20.0),
                Block("22:30", 16.0),
            ),
        )

        val DEFAULT = WeekSchedule(DEFAULT_DAY, DEFAULT_DAY, DEFAULT_DAY, DEFAULT_DAY, DEFAULT_DAY, DEFAULT_DAY, DEFAULT_DAY)
    }
}
