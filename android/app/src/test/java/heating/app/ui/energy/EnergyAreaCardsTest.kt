package heating.app.ui.energy

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import heating.app.data.AreaEnergy
import heating.app.data.AreaPlugEnergy
import heating.app.data.AreaPower
import heating.app.data.EnergyDashboard
import heating.app.data.FakeHeatingApi
import heating.app.data.HeatingApi
import heating.app.data.LivePower
import heating.app.data.Meter
import heating.app.data.RoomPower
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class EnergyAreaCardsTest {
    @get:Rule
    val compose = createComposeRule()

    private val today = EnergyDashboard(
        "today", electricityMeter = "Main Clamp", electricityKwh = 8.0,
        areas = listOf(
            AreaEnergy("garage-office", "Garage/Office", kwh = 1.0, cost = 27.0, wholeMeter = "Garage Clamp", plugs = listOf(AreaPlugEnergy("Desk Plug", 0.2, 5.4)), rest = 0.8, restCost = 21.6),
            AreaEnergy("lights", "Lights", kwh = 0.5, cost = 13.5, wholeMeter = "Lights Clamp"),
        ),
    )

    // BDD: The Energy tab shows areas below the rooms
    @Test
    fun `area cards come after the rooms and before everything else, with power now and today's use`() {
        val live = LivePower(855.0, rooms = listOf(RoomPower("kitchen", "Kitchen", 42.0)), elseWatts = 600.0, areas = listOf(AreaPower("lights", "Lights", 60.0)))
        val cards = liveCards(live, today)
        assertEquals(listOf("room-kitchen", "area-lights", "rest"), cards.map { it.key })
        val lights = cards[1]
        assertEquals("60 W", lights.now)
        assertEquals("Today 0.5 kWh · £0.14", lights.today)
        // A clamp with no reading says so, never an old figure.
        assertEquals("No reading", liveCards(live.copy(areas = listOf(AreaPower("lights", "Lights", null))), today)[1].now)
    }

    // BDD: An area card opens its energy dashboard
    @Test
    fun `an area's dashboard has its figures, a chart per plug and a rest row`() {
        val service: HeatingApi = object : HeatingApi by FakeHeatingApi() {
            override suspend fun energyDashboard(period: String) = today
            override suspend fun meters(unassignedOnly: Boolean) = listOf(Meter("Desk Plug", power = 40.0))
            override suspend fun areaUsage(area: String, window: String, to: String?) =
                heating.app.data.EnergyUsage(from = "2026-11-05T17:00:00Z", to = "2026-11-06T17:00:00Z")
            override fun livePower(): Flow<LivePower> = flowOf(LivePower(855.0, areas = listOf(AreaPower("garage-office", "Garage/Office", 300.0))))
        }
        compose.setContent { MaterialTheme { AreaEnergyScreen(service, "garage-office", PaddingValues()) } }
        compose.waitForIdle()
        compose.onNodeWithText("300 W").assertIsDisplayed()
        compose.onNodeWithText("Desk Plug").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Rest of Garage/Office").performScrollTo().assertIsDisplayed()
    }
}
