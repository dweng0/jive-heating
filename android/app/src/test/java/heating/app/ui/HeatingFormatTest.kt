package heating.app.ui

import heating.app.data.Block
import heating.app.data.Boost
import heating.app.data.Mode
import heating.app.data.NextChange
import heating.app.data.Override
import heating.app.data.Zone
import kotlin.test.Test
import kotlin.test.assertEquals

class HeatingFormatTest {

    @Test
    fun `whole temperatures drop the decimal`() {
        assertEquals("21°", formatTemp(21.0))
        assertEquals("20.5°", formatTemp(20.5))
        assertEquals("–", formatTemp(null))
    }

    @Test
    fun `targets step by half a degree within 5 to 30`() {
        assertEquals(21.5, stepTarget(21.0, +1))
        assertEquals(20.5, stepTarget(21.0, -1))
        assertEquals(30.0, stepTarget(30.0, +1))
        assertEquals(5.0, stepTarget(5.0, -1))
        assertEquals(20.5, stepTarget(20.3, 0))
    }

    @Test
    fun `dial fraction maps 5 to 30 onto 0 to 1`() {
        assertEquals(0f, dialFraction(5.0))
        assertEquals(1f, dialFraction(30.0))
        assertEquals(0.64f, dialFraction(21.0), 0.0001f)
        assertEquals(0f, dialFraction(null))
    }

    @Test
    fun `dial drag angle maps back to a half-degree target`() {
        // 135° is the start of the 270° sweep (5°C), 45° (= 405°) the end (30°C).
        assertEquals(5.0, targetForAngle(135f))
        assertEquals(30.0, targetForAngle(45f))
        assertEquals(17.5, targetForAngle(270f))
        // Inside the bottom gap: snap to the nearer end.
        assertEquals(5.0, targetForAngle(100f))
        assertEquals(30.0, targetForAngle(80f))
    }

    @Test
    fun `schedule segments cover the day in proportion`() {
        val segments = scheduleSegments(listOf(Block("00:00", 16.0), Block("06:00", 20.0), Block("18:00", 16.0)))
        assertEquals(listOf(0.25f, 0.5f, 0.25f), segments.map { it.fraction })
        assertEquals(listOf(16.0, 20.0, 16.0), segments.map { it.target })
    }

    @Test
    fun `clock time is read from an iso timestamp`() {
        assertEquals("22:30", clockTime("2026-10-13T22:30:00+01:00"))
        assertEquals("06:05", clockTime("2026-10-14T06:05:00Z"))
    }

    @Test
    fun `boost durations are 30 minutes to 3 hours`() {
        assertEquals(listOf(30, 60, 90, 120, 150, 180), BOOST_MINUTES)
        assertEquals("30 min", boostLabel(30))
        assertEquals("1 hour", boostLabel(60))
        assertEquals("1.5 hours", boostLabel(90))
        assertEquals("3 hours", boostLabel(180))
    }

    private fun zone(
        mode: Mode = Mode.SCHEDULE, target: Double? = 20.0, heating: Boolean = false,
        next: NextChange? = null, boost: Boost? = null, override: Override? = null,
    ) = Zone("z", listOf("v"), mode, 20.0, target, 19.0, heating, heating, next, boost, override)

    @Test
    fun `zone summary reads like the mockups`() {
        assertEquals("Off · frost protected", zoneSummary(zone(Mode.OFF, target = null)))
        assertEquals("Boost 22° · until 19:04", zoneSummary(zone(boost = Boost(22.0, "2026-10-13T19:04:00+01:00"), target = 22.0)))
        assertEquals("Held at 23° · until 22:30", zoneSummary(zone(override = Override(23.0, "2026-10-13T22:30:00+01:00"), target = 23.0)))
        assertEquals("Manual · 22°", zoneSummary(zone(Mode.MANUAL, target = 22.0)))
        assertEquals("Schedule · 20° till 22:30", zoneSummary(zone(next = NextChange("2026-10-13T22:30:00+01:00", 16.0))))
        assertEquals("Heating to 21° · till 22:30", zoneSummary(zone(target = 21.0, heating = true, next = NextChange("2026-10-13T22:30:00+01:00", 16.0))))
        assertEquals("Schedule · 20°", zoneSummary(zone()))
    }
}
