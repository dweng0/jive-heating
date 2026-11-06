package heating.app.ui.energy

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.onNodeWithTag
import heating.app.data.AreaPower
import heating.app.data.EnergyDashboard
import heating.app.data.EnergyState
import heating.app.data.EnergyTarget
import heating.app.data.FakeHeatingApi
import heating.app.data.HeatingApi
import heating.app.data.HouseHistory
import heating.app.data.HouseHistoryPoint
import heating.app.data.LivePower
import heating.app.data.PeakPart
import heating.app.data.PowerSummary
import heating.app.data.RoomPower
import heating.app.data.TodayPeak
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class EnergyDialsTest {
    @get:Rule
    val compose = createComposeRule()

    private val london = ZoneId.of("Europe/London")
    private val target = EnergyTarget(kwh = 12.0, usedKwh = 5.0, expectedKwh = 6.0, deltaKwh = -1.0, onTarget = true)
    private val today = EnergyDashboard("today", electricityMeter = "Main Clamp", electricityKwh = 5.0, electricityCost = 135.0, target = target)
    private val peak = TodayPeak(
        6200.0, "2026-11-05T18:05:00Z",
        listOf(PeakPart("room", "kitchen", "Kitchen", 3500.0), PeakPart("else", null, "Everything else", 2300.0), PeakPart("area", "lights", "Lights", 400.0)),
    )

    private fun live(house: Double?, kitchen: Double?, utility: Double?, lights: Double? = 150.0) = LivePower(
        house,
        rooms = listOf(RoomPower("kitchen", "Kitchen", kitchen), RoomPower("utility", "Utility", utility)),
        areas = listOf(AreaPower("lights", "Lights", lights)),
        elseWatts = 3000.0,
    )

    private fun api(first: LivePower = live(2400.0, 900.0, 300.0)): HeatingApi = object : HeatingApi by FakeHeatingApi() {
        override suspend fun powerSummary() = PowerSummary(todayPeak = peak)
        override suspend fun houseHistory(range: String, to: String?) = HouseHistory(range = range, from = "2026-11-05T00:00:00Z", to = "2026-11-06T00:00:00Z")
        override suspend fun energyDashboard(period: String) = today
        override fun livePower(): Flow<LivePower> = kotlinx.coroutines.flow.flow { emit(first); kotlinx.coroutines.awaitCancellation() }
    }

    private fun showSection(service: HeatingApi = api()) {
        compose.setContent { MaterialTheme { LivePowerSection(service, {}, {}) } }
        settle()
    }

    /** Polling only waits on delays, which the clock passes over once it is moved; this lets animations and the first reads finish. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
    }

    // BDD: The house dial shows the whole house now
    @Test
    fun `the house dial shows the whole house now`() {
        showSection()
        compose.onNodeWithContentDescription("Whole house: 2.40 kW").assertIsDisplayed()
    }

    // BDD: The second dial follows the busiest room or area
    @Test
    fun `the second dial follows the busiest room or area`() {
        val all = places(live(2400.0, 900.0, 300.0))
        // Everything else and devices in no room or area are never candidates.
        assertEquals(listOf("room:kitchen", "room:utility", "area:lights"), all.map { it.id })
        assertEquals("room:kitchen", followBusiest(Busiest(), all, 0).current)
        assertNull(followBusiest(Busiest("room:kitchen"), places(live(2400.0, null, null, null)), 0).current)
        showSection()
        compose.onNodeWithContentDescription("Kitchen: 900 W").assertIsDisplayed()
    }

    // BDD: The second dial does not flicker between close rooms
    @Test
    fun `the second dial does not flicker between close rooms`() {
        var s = followBusiest(Busiest(), places(live(2400.0, 900.0, 300.0)), 0)
        // Less than 10% ahead: stays, however long.
        s = followBusiest(s, places(live(2400.0, 900.0, 950.0)), 1_000)
        s = followBusiest(s, places(live(2400.0, 900.0, 950.0)), 60_000)
        assertEquals("room:kitchen", s.current)
        // Ahead enough, but only once it has been for 30 seconds.
        s = followBusiest(s, places(live(2400.0, 900.0, 1100.0)), 100_000)
        s = followBusiest(s, places(live(2400.0, 900.0, 1100.0)), 120_000)
        assertEquals("room:kitchen", s.current)
        s = followBusiest(s, places(live(2400.0, 900.0, 1100.0)), 130_000)
        assertEquals("room:utility", s.current)
        // Its room stops reporting: moves at once.
        assertEquals("room:kitchen", followBusiest(s, places(live(2400.0, 900.0, null)), 130_500).current)
        // 10% ahead but under 50 W more is not enough.
        val small = followBusiest(Busiest(), places(live(500.0, 300.0, 100.0)), 0)
        val later = followBusiest(followBusiest(small, places(live(500.0, 300.0, 340.0)), 1), places(live(500.0, 300.0, 340.0)), 60_000)
        assertEquals("room:kitchen", later.current)
    }

    // BDD: The fuel gauge drains towards the daily target
    @Test
    fun `the fuel gauge drains towards the daily target`() {
        val f = fuel(target)
        assertEquals(7.0 / 12, f.left, 1e-9)
        assertEquals(6.0 / 12, f.pace, 1e-9)
        assertEquals("7.0 kWh left", fuelLeft(f))
        assertEquals("5.0 of 12.0 kWh", fuelUsed(target))
        assertEquals("1.0 kWh under pace", paceLine(f.paceKwh))
        assertEquals(false, f.low)
        val over = fuel(target.copy(usedKwh = 13.0, expectedKwh = 9.0))
        assertEquals(0.0, over.left)
        assertEquals("1.0 kWh over", fuelLeft(over))
        assertTrue(over.low)
        assertTrue(fuel(target.copy(usedKwh = 10.0)).low)

        showSection()
        compose.onNodeWithContentDescription("Daily target: 7.0 kWh left, 5.0 of 12.0 kWh, 1.0 kWh under pace").assertIsDisplayed()
    }

    // BDD: Today's peak is the highest five-minute power since midnight
    @Test
    fun `the peak gauge reads today's peak and when`() {
        assertEquals("6.20 kW" to "at 18:05", peakReading(peak, london))
        assertEquals("–" to "None yet today", peakReading(null, london))
    }

    // BDD: Today's peak says what was using the power
    @Test
    fun `tapping the peak gauge shows what was using the power`() {
        assertEquals(listOf("Kitchen" to "3.50 kW · 56%", "Everything else" to "2.30 kW · 37%", "Lights" to "400 W · 6%"), peakLines(peak))
        showSection()
        compose.onNodeWithContentDescription("Today's peak", substring = true).performClick()
        settle()
        compose.onNodeWithText("3.50 kW · 56%").assertIsDisplayed()
        compose.onNodeWithText("2.30 kW · 37%").assertIsDisplayed()
    }

    // BDD: The chart can be dragged back in time
    @Test
    fun `the chart can be dragged back in time`() {
        val now = Instant.parse("2026-11-05T12:00:00Z").toEpochMilli()
        val week = HistoryRange.WEEK
        // Never past now, never before the first sample's window.
        assertEquals(now, clampEnd(now + 1000, week.span, now, now - 400 * 86_400_000L))
        assertEquals(now - 10 * 86_400_000L + week.span, clampEnd(now - 30 * 86_400_000L, week.span, now, now - 10 * 86_400_000L))
        // What is still to load, working back from the right edge.
        assertEquals(now - week.span, uncovered(listOf((now - week.span)..now), now - 2 * week.span, now))
        assertNull(uncovered(listOf((now - week.span)..now, (now - 3 * week.span)..(now - week.span)), now - 2 * week.span, now))

        val asked = mutableListOf<String?>()
        val service: HeatingApi = object : HeatingApi by FakeHeatingApi() {
            override suspend fun houseHistory(range: String, to: String?): HouseHistory {
                asked += to
                val end = to?.let { Instant.parse(it).toEpochMilli() } ?: System.currentTimeMillis()
                val step = 300_000L
                val points = (0 until 288).map { i -> HouseHistoryPoint(Instant.ofEpochMilli(end - (287 - i) * step).toString(), 0.05, 1.35, 600.0) }
                return HouseHistory(range = range, from = points.first().at, to = Instant.ofEpochMilli(end).toString(), earliest = Instant.ofEpochMilli(end - 400 * 86_400_000L).toString(), points = points)
            }
        }
        compose.setContent { MaterialTheme { HouseHistoryChart(service, dark = false) } }
        settle()
        assertNull(asked.first())
        assertEquals(0, compose.onAllNodesWithTextCount("Now"))
        compose.onNodeWithTag("history-chart").performTouchInput { swipeRight() }
        settle()
        compose.onNodeWithText("Now").assertExists()
        assertTrue(asked.any { it != null }, "asked for $asked")
        compose.onNodeWithText("Now").performClick()
        settle()
        assertTrue(compose.onAllNodesWithTextCount("Now") == 0)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size

    // BDD: The chart says what each point used and cost
    @Test
    fun `the chart says what each point used and cost`() {
        val day = HouseHistoryPoint("2026-11-05T00:00:00Z", kwh = 14.4, cost = 388.8, watts = 600.0, targetKwh = 12.0)
        assertEquals(listOf("Thu 5 Nov", "Used 14.4 kWh", "Cost £3.89", "Target 12.0 kWh"), pointReadout(day, HistoryRange.MONTH, "GBP", london))
        val five = HouseHistoryPoint("2026-11-05T18:05:00Z", kwh = 0.05, cost = 1.35, watts = 600.0, incomplete = true)
        assertEquals(listOf("Thu 5 Nov, 18:05", "Power 600 W", "Used 0.05 kWh", "Cost £0.01", READINGS_MISSING), pointReadout(five, HistoryRange.DAY, "GBP", london))
    }

    // BDD: The Energy tab shows for people with energy access
    @Test
    fun `a member with energy access sees the energy tab read only`() {
        var opened = false
        compose.setContent {
            MaterialTheme {
                EnergyScreen(api(), EnergyState(dashboard = today.copy(target = null)), {}, { opened = true }, {}, PaddingValues(), admin = false)
            }
        }
        settle()
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasText("Manage")).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasText(SET_TARGET)).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasText("Add area")).fetchSemanticsNodes().size)
        assertEquals(false, opened)
    }
}
