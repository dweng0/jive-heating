package heating.app.ui.energy

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import heating.app.data.EnergyDashboard
import heating.app.data.EnergyState
import heating.app.data.EnergyUsage
import heating.app.data.EverythingElse
import heating.app.data.FakeHeatingApi
import heating.app.data.HeatingApi
import heating.app.data.HeatingApiException
import heating.app.data.LivePower
import heating.app.data.PlugUsage
import heating.app.data.RoomPlug
import heating.app.data.RoomPlugs
import heating.app.data.RoomPower
import heating.app.data.UsagePoint
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The screens themselves, composed and driven on the JVM with what the service would send. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class RoomEnergyScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val asked = mutableListOf<String?>()

    private fun points(to: Instant, watts: Double) = (0 until 288).map { i ->
        UsagePoint(to.minus(Duration.ofMinutes(5L * i)).toString(), watts, 0.008, 0.21, false)
    }.reversed()

    private fun usage(to: String?, withPlugs: Boolean): EnergyUsage {
        asked += to
        val end = to?.let(Instant::parse) ?: Instant.now()
        return EnergyUsage(
            from = end.minus(Duration.ofHours(24)).toString(),
            to = end.toString(),
            earliest = Instant.now().minus(Duration.ofDays(3)).toString(),
            room = points(end, 100.0),
            plugs = if (withPlugs) listOf(PlugUsage("Fridge Plug", points(end, 80.0)), PlugUsage("Kettle Plug", emptyList())) else emptyList(),
        )
    }

    private fun api(plugs: RoomPlugs?, live: LivePower = LivePower(watts = 855.0, rooms = listOf(RoomPower("kitchen", "Kitchen", 42.0)), elseWatts = 666.0)): HeatingApi =
        object : HeatingApi by FakeHeatingApi() {
            override suspend fun roomPlugs(room: String) = plugs ?: throw HeatingApiException(404, "room not found")
            override suspend fun roomUsage(room: String, window: String, to: String?) = usage(to, true)
            override suspend fun everythingElseUsage(window: String, to: String?) = usage(to, false)
            override fun livePower(): Flow<LivePower> = flow {
                emit(live)
                awaitCancellation()
            }
        }

    private val fridge = RoomPlug("Fridge Plug", 40.0, 1.2, 30.0)
    private val kettle = RoomPlug("Kettle Plug", 2.0, 0.4, 10.0)
    private val kitchen = RoomPlugs("kitchen", "GBP", 1.6, 40.0, 0, false, listOf(fridge, kettle))

    private fun room(plugs: RoomPlugs?) {
        val service = api(plugs)
        compose.setContent { MaterialTheme { RoomEnergyScreen(service, "kitchen", "Kitchen", PaddingValues()) } }
        settle()
    }

    /** Polling only waits on delays, which the clock passes over once it is moved; this lets animations and the first reads finish. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
    }

    private fun nextChart() {
        compose.onAllNodes(hasContentDescription("Next chart") and isEnabled())[0].performClick()
        settle()
    }

    // BDD: The room dashboard leads with what it is using and costing
    @Test
    fun `the room's cards show now, today and cost`() {
        room(kitchen)
        compose.onNodeWithText("42 W").assertIsDisplayed()
        compose.onNodeWithText("1.6 kWh").assertIsDisplayed()
        compose.onNodeWithText("£0.40").assertIsDisplayed()
    }

    // BDD: The room dashboard charts each plug in a carousel
    @Test
    fun `the arrows move from the room's chart to each plug's, keeping the choice`() {
        room(kitchen)
        compose.onNodeWithContentDescription("Kitchen, power over time", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Use").performClick()
        settle()
        nextChart()
        compose.onNodeWithContentDescription("Fridge Plug, use over time", substring = true).assertIsDisplayed()
    }

    // BDD: The room dashboard charts each plug in a carousel
    @Test
    fun `a room with one plug shows its chart alone`() {
        room(RoomPlugs("kitchen", "GBP", 1.2, 30.0, 0, false, listOf(fridge)))
        compose.onNodeWithContentDescription("Fridge Plug, power over time", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Kitchen, power over time", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Next chart").assertDoesNotExist()
    }

    // BDD: A chart scrolls back through time
    @Test
    fun `dragging the chart moves it back, reading earlier use, not the carousel`() {
        room(kitchen)
        val readBefore = asked.count { it != null }
        // Only the "Now" card until the chart has been moved.
        assertEquals(1, compose.onAllNodesWithText("Now").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("Kitchen, power over time", substring = true).performTouchInput { swipeRight() }
        settle()
        assertEquals(2, compose.onAllNodesWithText("Now").fetchSemanticsNodes().size)
        assertTrue(asked.count { it != null } > readBefore, "asked for $asked")
        // The drag moved the chart, not the carousel.
        compose.onNodeWithContentDescription("Kitchen, power over time", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Fridge Plug, power over time", substring = true).assertDoesNotExist()
        // The other charts are at the same time, and "Now" returns them to the latest.
        nextChart()
        assertEquals(2, compose.onAllNodesWithText("Now").fetchSemanticsNodes().size)
        compose.onAllNodesWithText("Now")[1].performClick()
        settle()
        assertEquals(1, compose.onAllNodesWithText("Now").fetchSemanticsNodes().size)
    }

    // BDD: A chart says what it shows at any point
    @Test
    fun `touching and holding the chart says what it shows there`() {
        room(kitchen)
        compose.onNodeWithContentDescription("Kitchen, power over time", substring = true).performTouchInput { down(center) }
        settle()
        compose.onNodeWithText("100 W · 0.008 kWh · 0.21p").assertIsDisplayed()
    }

    // BDD: A chart with nothing to draw says so
    @Test
    fun `a plug with nothing recorded says so`() {
        room(kitchen)
        repeat(2) { nextChart() }
        compose.onNodeWithText("Nothing recorded for this time").assertIsDisplayed()
    }

    // BDD: A table compares the plugs in the room
    @Test
    fun `a heading sorts the table, and picking a name shows its chart`() {
        room(kitchen)
        // Biggest cost first, then the heading reverses power when tapped again.
        fun tops(): Pair<Float, Float> = compose.onAllNodesWithText("Fridge Plug").fetchSemanticsNodes().last().boundsInRoot.top to
            compose.onAllNodesWithText("Kettle Plug").fetchSemanticsNodes().last().boundsInRoot.top
        assertTrue(tops().let { it.first < it.second }, "cost today, highest first")
        val power = compose.onNodeWithText("Power now")
        power.performScrollTo().performClick()
        settle()
        assertTrue(tops().let { it.first < it.second }, "power, highest first")
        power.performClick()
        settle()
        assertTrue(tops().let { it.first > it.second }, "power, lowest first")
        compose.onNodeWithText("40 W").assertExists()
        compose.onNodeWithText("75%").assertExists()
        compose.onAllNodesWithText("Kettle Plug").fetchSemanticsNodes().let { assertEquals(1, it.size) }
        compose.onNodeWithText("Kettle Plug").performClick()
        settle()
        compose.onNodeWithText("Nothing recorded for this time").performScrollTo().assertIsDisplayed()
    }

    // BDD: The table says when a plug's figures cannot be trusted
    @Test
    fun `an offline plug says so in the table`() {
        val tv = RoomPlug("TV Plug", null, 0.5, 13.5, incomplete = true, offlineSince = "2026-10-12T14:20:00+01:00")
        room(RoomPlugs("kitchen", "GBP", 1.7, 43.5, 0, true, listOf(fridge, tv)))
        assertTrue(compose.onAllNodesWithText("Offline").fetchSemanticsNodes().size == 2)
    }

    // BDD: A plug moved out of the room still adds up
    @Test
    fun `use by a plug moved out has a row of its own`() {
        room(RoomPlugs("kitchen", "GBP", 1.7, 43.0, 0, false, listOf(fridge)))
        compose.onNodeWithText("Moved out").assertExists()
        compose.onNodeWithText("0.5 kWh").assertExists()
    }

    // BDD: A room with no plugs left has nothing to chart
    @Test
    fun `no plugs says so, and an unknown room says it is not found`() {
        room(RoomPlugs("kitchen", "GBP", 0.5, 12.5, 0, false, emptyList()))
        compose.onNodeWithText("No plugs in this room").assertExists()
        compose.onNodeWithText("0.5 kWh").assertExists()
    }

    // BDD: A room with no plugs left has nothing to chart
    @Test
    fun `an unknown room says so`() {
        room(null)
        compose.onNodeWithText("Room not found").assertIsDisplayed()
    }

    // BDD: Everything else has its own dashboard
    @Test
    fun `everything else has cards and one chart, and no table`() {
        val service = object : HeatingApi by api(kitchen) {
            override suspend fun energyDashboard(period: String) = EnergyDashboard(
                period = "today", from = "2026-10-12T00:00:00Z", to = "2026-10-12T12:00:00Z",
                everythingElse = EverythingElse(5.6, 151.2, 0, false),
            )
        }
        compose.setContent { MaterialTheme { EverythingElseEnergyScreen(service, PaddingValues()) } }
        settle()
        compose.onNodeWithText("666 W").assertIsDisplayed()
        compose.onNodeWithText("5.6 kWh").assertIsDisplayed()
        compose.onNodeWithText("£1.51").assertIsDisplayed()
        compose.onNodeWithText("What no plug accounts for").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next chart").assertDoesNotExist()
        compose.onNodeWithText("Power now").assertDoesNotExist()
    }

    // BDD: Android dashboards pass being driven end to end
    @Test
    fun `back from a room's dashboard returns to the Energy tab where it was left`() {
        val many = LivePower(watts = 855.0, rooms = (1..12).map { RoomPower("r$it", "Room $it", 42.0) }, elseWatts = 666.0)
        val service = object : HeatingApi by api(kitchen, many) {
            override suspend fun energyDashboard(period: String) =
                EnergyDashboard(period = "today", from = "2026-10-12T00:00:00Z", to = "2026-10-12T12:00:00Z", electricityMeter = "House Meter")
        }
        lateinit var nav: NavHostController
        compose.setContent {
            MaterialTheme {
                nav = rememberNavController()
                NavHost(nav, startDestination = "energy") {
                    composable("energy") {
                        EnergyScreen(service, EnergyState(), {}, { nav.navigate("energy/room/$it") }, { nav.navigate("energy/else") }, PaddingValues())
                    }
                    composable("energy/room/{id}") { e -> RoomEnergyScreen(service, e.arguments?.getString("id")!!, "Room 12", PaddingValues()) }
                    composable("energy/else") { EverythingElseEnergyScreen(service, PaddingValues()) }
                }
            }
        }
        settle()
        val card = compose.onNodeWithText("Room 12")
        card.performScrollTo()
        settle()
        val left = card.getBoundsInRoot().top
        assertTrue(compose.onNodeWithText("Energy").getBoundsInRoot().bottom.value <= 0f, "the screen should have scrolled")
        card.performClick()
        settle()
        compose.onNodeWithContentDescription("Room 12, power over time", substring = true).assertIsDisplayed()
        compose.runOnUiThread { nav.popBackStack() }
        settle()
        assertEquals(left, compose.onNodeWithText("Room 12").assertIsDisplayed().getBoundsInRoot().top)
    }
}
