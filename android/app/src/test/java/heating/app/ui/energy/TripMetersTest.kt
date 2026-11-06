package heating.app.ui.energy

import heating.app.data.EnergyTotal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TripMetersTest {
    // BDD: The trip counter ticks up with the house's use
    @Test
    fun `the trip counter rolls on at the 5 minute average and never back`() {
        val start = tripAt(null, "2026-11-05", 8.2, 1200.0, 0)
        assertEquals(8.2, start.kwh, 1e-9)
        // A minute at 1200 W is 0.02 kWh.
        val later = tripAt(start, "2026-11-05", 8.2, 1200.0, 60_000)
        assertEquals(8.22, later.kwh, 1e-9)
        // A lower recorded figure does not roll it back; a higher one is taken.
        assertEquals(8.22, tripAt(later, "2026-11-05", 8.21, 0.0, 61_000).kwh, 1e-9)
        assertEquals(9.5, tripAt(later, "2026-11-05", 9.5, 0.0, 61_000).kwh, 1e-9)
        // A new day starts from today's figure.
        assertEquals(0.1, tripAt(later, "2026-11-06", 0.1, 1200.0, 62_000).kwh, 1e-9)
        // The average is of the last 5 minutes only.
        assertEquals(1000.0, fiveMinuteAverage(listOf(0L to 5000.0, 300_000L to 800.0, 500_000L to 1200.0), 590_000)!!, 1e-9)
        assertNull(fiveMinuteAverage(emptyList(), 0))
    }

    // BDD: Today is compared with yesterday at the same time
    @Test
    fun `today is compared with yesterday at the same time`() {
        assertEquals("▲ 1.2 kWh vs yesterday" to true, versusYesterday(8.2, EnergyTotal(7.0, 189.0)))
        assertEquals("▼ 0.5 kWh vs yesterday" to false, versusYesterday(8.2, EnergyTotal(8.7, 235.0)))
        assertNull(versusYesterday(8.2, null))
    }

    // BDD: The odometer totals the day, week, month or year
    @Test
    fun `the odometer shows the chosen period with what the trip has rolled on since`() {
        assertEquals(listOf("Day", "Week", "Month", "Year"), OdometerPeriod.entries.map { it.label })
        assertEquals("2100.0", odometerText(2100.0))
        assertEquals("00051.0", odometerText(51.0).padStart(7, '0'))
    }
}
