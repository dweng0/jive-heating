package heating.app.ui.energy

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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

    // BDD: The odometer totals the day, week, month or year
    @Test
    fun `every wheel of the odometer shows its digit, not only the zeros`() {
        compose.mainClock.autoAdvance = false
        val totals = EnergyTotals(today = EnergyTotal(0.0, 0.0), week = EnergyTotal(123.4, 0.0), month = EnergyTotal(0.0, 0.0), year = EnergyTotal(0.0, 0.0))
        compose.setContent { MaterialTheme { TripMeters(totals, emptyList(), dark = false, now = { 0L }) } }
        compose.mainClock.advanceTimeBy(2_000)
        // 00123.4 on the odometer: one 1, one 2, one 3 and one 4 showing
        listOf("1", "2", "3", "4").forEach { assertEquals(1, shown(it), "wheels showing $it") }
    }
}
