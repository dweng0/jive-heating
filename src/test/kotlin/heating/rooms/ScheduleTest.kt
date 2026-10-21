package heating.rooms

import heating.testing.TestHeating
import java.time.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals

class ScheduleTest {

    // BDD: New zones get the default schedule
    @Test
    fun `new zones get the default schedule`() {
        val t = TestHeating()
        val room = t.app.rooms.create("Lounge")

        val expected = listOf(
            Block("00:00", 16.0),
            Block("06:30", 20.0),
            Block("08:30", 16.0),
            Block("16:30", 20.0),
            Block("22:30", 16.0),
        )
        val schedule = room.zones().single().settings.schedule
        DayOfWeek.entries.forEach { day -> assertEquals(expected, schedule[day].blocks, "$day") }
    }

    // BDD: Effective target follows the current block
    @Test
    fun `effective target follows the current block`() {
        val monday = DaySchedule(listOf(Block("00:00", 16.0), Block("06:30", 20.0), Block("08:30", 16.0)))
        val settings = ZoneSettings(mode = Mode.SCHEDULE, schedule = WeekSchedule.DEFAULT.with(DayOfWeek.MONDAY, monday))
        val mondayAt = { hhmm: String ->
            java.time.ZonedDateTime.of(java.time.LocalDate.of(2026, 10, 12), java.time.LocalTime.parse(hhmm), java.time.ZoneId.of("Europe/London"))
        }

        assertEquals(20.0, EffectiveTarget.of(settings, mondayAt("07:15")))
        assertEquals(16.0, EffectiveTarget.of(settings, mondayAt("06:29")))
        assertEquals(20.0, EffectiveTarget.of(settings, mondayAt("06:30")))
        assertEquals(16.0, EffectiveTarget.of(settings, mondayAt("23:59")))
    }
}
