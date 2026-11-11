package heating.app.ui.energy

import heating.app.data.EnergyTotal
import heating.app.data.EnergyTotals
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TripMetersTest {
    private fun ms(at: String) = Instant.parse(at).toEpochMilli()

    /** Totals for household day [day] (London, UTC in November), ending at the next midnight. */
    private fun totals(day: String, today: Double, week: Double = 51.0, month: Double = 190.0, year: Double = 2100.0, at: String = "", receivedAt: Long = 0) = EnergyTotals(
        today = EnergyTotal(today, 0.0), week = EnergyTotal(week, 0.0), month = EnergyTotal(month, 0.0), year = EnergyTotal(year, 0.0),
        day = day, dayEndsAt = java.time.LocalDate.parse(day).plusDays(1).toString() + "T00:00:00Z", at = at, receivedAt = receivedAt,
    )

    // BDD: The trip counter ticks up with the house's use
    @Test
    fun `the trip counter rolls on at the 5 minute average and never back`() {
        val t = ms("2026-11-05T18:00:00Z")
        val start = counterAt(null, totals("2026-11-05", 8.2), 1200.0, t)
        assertEquals(8.2, start.today, 1e-9)
        // A minute at 1200 W is 0.02 kWh.
        val later = counterAt(start, totals("2026-11-05", 8.2), 1200.0, t + 60_000)
        assertEquals(8.22, later.today, 1e-9)
        // A lower recorded figure does not roll it back; a higher one is taken.
        assertEquals(8.22, counterAt(later, totals("2026-11-05", 8.21), 0.0, t + 61_000).today, 1e-9)
        assertEquals(9.5, counterAt(later, totals("2026-11-05", 9.5), 0.0, t + 61_000).today, 1e-9)
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
    fun `the usage counter holds the day, week, month and year totals`() {
        val c = counterAt(null, totals("2026-11-05", 8.2), null, ms("2026-11-05T18:00:00Z"))
        assertEquals(listOf(8.2, 51.0, 190.0, 2100.0), UsagePeriod.entries.map { c[it] })
    }

    // BDD: The usage counter shows the day, week, month or year
    @Test
    fun `a tap moves the usage counter on through the periods`() {
        assertEquals(listOf("today", "this week", "this month", "this year"), UsagePeriod.entries.map { it.label })
        assertEquals(UsagePeriod.WEEK, UsagePeriod.TODAY.next())
        assertEquals(UsagePeriod.TODAY, UsagePeriod.YEAR.next())
        // Every period rolls on at the house's power.
        val t = ms("2026-11-05T18:00:00Z")
        val start = counterAt(null, totals("2026-11-05", 8.2), 1200.0, t)
        val later = counterAt(start, totals("2026-11-05", 8.2), 1200.0, t + 60_000)
        assertEquals(51.02, later.week, 1e-9)
        assertEquals(2100.02, later.year, 1e-9)
    }

    // BDD: The usage counter starts again at the household's midnight
    @Test
    fun `the usage counter starts again at the household's midnight`() {
        val evening = totals("2026-11-10", 19.412, week = 60.0)
        val c = counterAt(null, evening, 1200.0, ms("2026-11-10T23:59:00Z"))
        assertEquals(19.412, c.today, 1e-9)
        // Midnight passes with only last night's totals to hand: today starts from 0, Wednesday's week carries on.
        val midnight = counterAt(c, evening, 0.0, ms("2026-11-11T00:00:01Z"))
        assertEquals("2026-11-11", midnight.day)
        assertEquals(0.0, midnight.today, 1e-9)
        assertEquals(c.week, midnight.week, 1e-6)
        // Totals for the day it has left are ignored.
        val stale = counterAt(midnight, evening, 0.0, ms("2026-11-11T00:00:30Z"))
        assertEquals(0.0, stale.today, 1e-9)
        // The new day's figure is taken though lower than last night's; the week is not counted twice.
        val fresh = counterAt(stale, totals("2026-11-11", 0.1, week = 60.2), 0.0, ms("2026-11-11T00:05:00Z"))
        assertEquals(0.1, fresh.today, 1e-9)
        assertEquals(60.2, fresh.week, 1e-6)
        // The week starts again on a Monday, the month on the 1st, the year on 1 January.
        val monday = turnOver(fresh, "2026-11-16", 0)
        assertEquals(listOf(0.0, 0.0), listOf(monday.today, monday.week))
        assertEquals(fresh.month, monday.month, 1e-9)
        val newYear = turnOver(fresh, "2027-01-01", 0)
        assertEquals(listOf(0.0, 0.0), listOf(newYear.month, newYear.year))
    }

    @Test
    fun `the usage counter turns over at the household's midnight whatever the phone's clock says`() {
        // The phone is an hour slow: it received the totals at its 22:59, the service's 23:59.
        val t = totals("2026-11-10", 19.412, at = "2026-11-10T23:59:00Z", receivedAt = ms("2026-11-10T22:59:00Z"))
        assertEquals(3_600_000L, serviceSkew(t))
        val phoneNow = ms("2026-11-10T23:00:01Z")
        assertEquals(0.0, counterAt(counterAt(null, t, 0.0, ms("2026-11-10T23:59:00Z")), t, 0.0, phoneNow + serviceSkew(t)).today, 1e-9)
    }

    // BDD: The dashboard reloads its day figures at the household's midnight
    @Test
    fun `the dashboard fetches its day figures just after the household's midnight`() {
        val t = totals("2026-11-10", 19.4, at = "2026-11-10T23:59:00Z", receivedAt = ms("2026-11-10T23:59:00Z"))
        // Fetched at 23:59: the next fetch is just after midnight, not five minutes later.
        assertEquals(ms("2026-11-11T00:00:10Z"), nextFetchAt(ms("2026-11-10T23:59:00Z"), t, 5 * 60_000L, 10_000))
        // In the afternoon the five minutes come first.
        assertEquals(ms("2026-11-10T15:05:00Z"), nextFetchAt(ms("2026-11-10T15:00:00Z"), t, 5 * 60_000L, 10_000))
        // A phone an hour slow fetches at its 23:00, the household's midnight.
        val slow = t.copy(receivedAt = ms("2026-11-10T22:59:00Z"))
        assertEquals(ms("2026-11-10T23:00:10Z"), nextFetchAt(ms("2026-11-10T22:59:00Z"), slow, 5 * 60_000L, 10_000))
        // Without the totals' day, every five minutes.
        assertEquals(300_000L, nextFetchAt(0, null, 5 * 60_000L, 10_000))
    }
}
