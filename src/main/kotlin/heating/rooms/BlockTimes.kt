package heating.rooms

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * When schedule blocks start, as real instants. Working in instants (not local clock times) makes the
 * spring-forward gap and the repeated autumn hour behave: a block inside the gap starts when the
 * clocks jump, and a block in the repeated hour starts at its first occurrence only.
 */
object BlockTimes {
    fun start(date: LocalDate, block: Block, zone: ZoneId): ZonedDateTime {
        val local = date.atTime(LocalTime.parse(block.start))
        val offsets = zone.rules.getValidOffsets(local)
        if (offsets.isEmpty()) return ZonedDateTime.ofInstant(zone.rules.getTransition(local).instant, zone)
        return ZonedDateTime.ofLocal(local, zone, offsets.first())
    }

    /** The block in force at [now]: today's last block already started, else the previous day's last block. */
    fun current(schedule: WeekSchedule, now: ZonedDateTime): Block {
        val today = now.toLocalDate()
        schedule[today.dayOfWeek].blocks.lastOrNull { !start(today, it, now.zone).isAfter(now) }?.let { return it }
        return schedule[today.minusDays(1).dayOfWeek].blocks.last()
    }

    /** The first block start strictly after [now], with its block. */
    fun next(schedule: WeekSchedule, now: ZonedDateTime): Pair<ZonedDateTime, Block> {
        var date = now.toLocalDate()
        repeat(9) {
            schedule[date.dayOfWeek].blocks.forEach { block ->
                val at = start(date, block, now.zone)
                if (at.isAfter(now)) return at to block
            }
            date = date.plusDays(1)
        }
        error("schedule has no blocks")
    }
}
