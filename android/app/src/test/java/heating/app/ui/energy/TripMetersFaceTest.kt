package heating.app.ui.energy

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import heating.app.data.EnergyTotal
import heating.app.data.EnergyTotals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class TripMetersFaceTest {
    @get:Rule
    val compose = createComposeRule()

    /** How many "[digit]" wheel faces can be seen (laid out and inside their wheel's window). */
    private fun shown(digit: String) = compose.onAllNodesWithText(digit, useUnmergedTree = true).fetchSemanticsNodes()
        .count { it.boundsInRoot.height > 1f && it.boundsInRoot.width > 1f }

    // BDD: The usage counter shows the day, week, month or year
    @Test
    fun `tapping the usage counter shows the week, month and year, every wheel showing its digit`() {
        compose.mainClock.autoAdvance = false
        val totals = EnergyTotals(today = EnergyTotal(0.0, 0.0), week = EnergyTotal(123.4, 0.0), month = EnergyTotal(5678.0, 0.0), year = EnergyTotal(0.0, 0.0), day = "2026-11-05", dayEndsAt = "2026-11-06T00:00:00Z")
        compose.setContent { MaterialTheme { TripMeters(totals, emptyList(), dark = false, now = { 0L }) } }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("USAGE TODAY").assertExists()
        compose.onNodeWithContentDescription("Usage today kWh 0.0000").performClick()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("USAGE THIS WEEK").assertExists()
        // 123.4000 this week: one 1, one 2, one 3 and one 4 showing
        listOf("1", "2", "3", "4").forEach { assertEquals(1, shown(it), "wheels showing $it") }
        compose.onNodeWithContentDescription("Usage this week kWh 123.4000").performClick()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("USAGE THIS MONTH").assertExists()
        compose.onNodeWithContentDescription("Usage this month kWh 5678.000").assertExists()
        // The "£" switch shows the cost.
        compose.onNodeWithText("£").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("COST THIS MONTH").assertExists()
    }
}
