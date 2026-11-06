package heating.app.ui.energy

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import heating.app.data.EnergyDashboard
import heating.app.data.EnergyDay
import heating.app.data.EnergyTarget
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class DailyTargetTest {
    @get:Rule
    val compose = createComposeRule()

    private val noon = EnergyTarget(kwh = 12.0, usedKwh = 5.0, expectedKwh = 6.0, deltaKwh = -1.0, deltaCost = -27.0, onTarget = true, projectedKwh = 10.0)

    // BDD: The Energy tab shows the delta for today
    @Test
    fun `today says how far under target, where it is heading, and where use should be by now`() {
        compose.setContent { MaterialTheme { TargetCard(noon, today = true) } }
        compose.onNodeWithText("1.0 kWh under pace").assertIsDisplayed()
        compose.onNodeWithText("5.0 of 12.0 kWh, 7.0 kWh left today, on track for 10.0 kWh").assertIsDisplayed()
        compose.onNodeWithContentDescription("Where use should be by now").assertIsDisplayed()
    }

    // BDD: The Energy tab shows the delta for today
    @Test
    fun `over target says so, and missing readings are said`() {
        compose.setContent { MaterialTheme { TargetCard(noon.copy(usedKwh = 7.0, deltaKwh = 1.0, onTarget = false, incomplete = true), today = true) } }
        compose.onNodeWithText("1.0 kWh over pace").assertIsDisplayed()
        compose.onNodeWithText("Some readings are missing").assertIsDisplayed()
    }

    // BDD: The Energy tab shows the delta for today
    @Test
    fun `week bars carry a target line and mark days over it`() {
        fun day(date: String, kwh: Double, delta: Double) =
            EnergyDay(date, electricityKwh = kwh, electricityCost = kwh * 27, standingCost = 80.0, totalCost = 80 + kwh * 27, targetDeltaKwh = delta)
        val week = EnergyDashboard(
            "week", electricityMeter = "Main Clamp", electricityKwh = 24.0, electricityCost = 648.0,
            days = listOf(day("2026-11-09", 10.0, -2.0), day("2026-11-10", 14.0, 2.0)),
            target = EnergyTarget(24.0, 24.0, 24.0, 0.0, 0.0, true, 24.0),
        )
        val bars = dayBars(week)
        assertEquals(listOf(false, true), bars.map { it.overTarget })
        assertNotNull(bars[0].target)
        assertNull(dayBars(week.copy(target = null))[0].target)
    }
}
