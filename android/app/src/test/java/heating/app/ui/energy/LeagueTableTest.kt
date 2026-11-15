package heating.app.ui.energy

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import heating.app.data.ItemPower
import heating.app.data.LivePower
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class LeagueTableTest {
    @get:Rule
    val compose = createComposeRule()

    // The spec's 11000 W house, plus the 3 W lamp that reads as off.
    private val live = LivePower(
        watts = 11003.0,
        items = listOf(
            ItemPower("Fridge Plug", "Fridge", 90.0, room = "kitchen", job = "monitored"),
            ItemPower("Kettle Plug", "Kettle", 0.0, room = "kitchen", job = "monitored"),
            ItemPower("Shower Clamp", "Shower", 9500.0, area = "bathroom", job = "area"),
            ItemPower("Lamp Plug", "Lamp", 3.0, job = "monitored"),
            ItemPower("Oven Clamp", "Oven", 1200.0, area = "kitchen-ring", job = "area"),
            ItemPower("Garage Plug", "Garage", null, job = "monitored"),
        ),
    )

    // BDD: League table ranks items by their live watts
    @Test
    fun `the league table ranks items by their live watts, then everything else, off and no reading`() {
        val rows = leagueRows(live)
        assertEquals(listOf("Shower", "Oven", "Fridge", "Everything else", "Kettle", "Lamp", "Garage"), rows.map { it.name })
        assertEquals(listOf(LeagueState.ON, LeagueState.ON, LeagueState.ON, LeagueState.ELSE, LeagueState.OFF, LeagueState.OFF, LeagueState.NO_READING), rows.map { it.state })
        assertEquals(listOf("9.50 kW", "1.20 kW", "90 W", "210 W", "Off", "Off", "No reading"), rows.map { leagueWatts(it) })
        assertEquals(listOf("86%", "11%", "1%", "2%", "", "", ""), rows.map { leagueShare(it) })
        // The bar is as long as its share.
        assertEquals(9500 / 11003.0, rows[0].share!!, 1e-9)

        compose.setContent { MaterialTheme { LeagueTable(live) } }
        compose.onNodeWithTag("league-table").assertIsDisplayed()
        compose.onNodeWithText("Shower").assertIsDisplayed()
        compose.onNodeWithText("86%").assertIsDisplayed()
        compose.onNodeWithText("No reading").assertIsDisplayed()
        val shown = compose.onAllNodesWithTag("league-row", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(7, shown.size)
        compose.onAllNodesWithTag("league-row", useUnmergedTree = true)[6]
            .assert(SemanticsMatcher.expectValue(LeagueGreyed, true))
    }

    @Test
    fun `a monitored device inside a measured area is not counted twice in everything else`() {
        val rows = leagueRows(
            LivePower(
                watts = 2000.0,
                items = listOf(
                    ItemPower("Ring Clamp", "Kitchen ring", 1500.0, area = "kitchen", job = "area"),
                    ItemPower("Kettle Plug", "Kettle", 1400.0, area = "kitchen", job = "monitored"),
                ),
            ),
        )
        assertEquals(500.0, rows.first { it.state == LeagueState.ELSE }.watts)
    }

    private fun kitchen(oven: Double, kettle: Double) = LivePower(
        watts = 5000.0,
        items = listOf(
            ItemPower("Oven Clamp", "Oven", oven, job = "monitored"),
            ItemPower("Kettle Plug", "Kettle", kettle, job = "monitored"),
        ),
    )

    /** The row names in the order the table lays them out (each row's first text is its name). */
    private fun shownNames(): List<String> {
        fun firstText(n: androidx.compose.ui.semantics.SemanticsNode): String? =
            n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.firstOrNull()?.text
                ?: n.children.firstNotNullOfOrNull { firstText(it) }
        return compose.onAllNodesWithTag("league-row", useUnmergedTree = true).fetchSemanticsNodes().map { firstText(it)!! }
    }

    private fun row(name: String) = compose.onNode(hasTestTag("league-row") and androidx.compose.ui.test.hasAnyDescendant(hasText(name)), useUnmergedTree = true)
    private fun share(name: String) = compose.onNode(hasTestTag("league-share") and androidx.compose.ui.test.hasAnyAncestor(hasTestTag("league-row") and androidx.compose.ui.test.hasAnyDescendant(hasText(name))), useUnmergedTree = true)

    /** Moves the test clock on a frame at a time, so a reading set from the test is picked up on the next frame. */
    private fun step(ms: Long) = repeat((ms / 16).toInt().coerceAtLeast(1)) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() }

    // BDD: League rows swap places as readings arrive
    @Test
    fun `league rows slide past each other, count to new figures, flash their share and swap at most every 2 s`() {
        // The ordering rule on its own: a swap holds both rows in place for 2 s.
        var p = placeRows(Placing(), listOf("oven", "kettle"), 0)
        assertEquals(listOf("oven", "kettle"), p.order)
        p = placeRows(p, listOf("kettle", "oven"), 1_000)
        assertEquals(listOf("kettle", "oven"), p.order)
        p = placeRows(p, listOf("oven", "kettle"), 2_500)
        assertEquals(listOf("kettle", "oven"), p.order, "held: they swapped 1.5 s ago")
        assertEquals(3_000L, nextUnlock(p, listOf("oven", "kettle"), 2_500))
        p = placeRows(p, listOf("oven", "kettle"), 3_000)
        assertEquals(listOf("oven", "kettle"), p.order)
        // A held row blocks others from passing it, but rows free to move go straight to their place.
        val q = placeRows(Placing(listOf("a", "b", "c"), mapOf("b" to 0L)), listOf("c", "b", "a"), 1_000)
        assertEquals(listOf("a", "b", "c"), q.order)
        assertEquals(listOf("c", "a"), placeRows(Placing(listOf("a", "b")), listOf("c", "a"), 0).order)

        compose.mainClock.autoAdvance = false
        val live = mutableStateOf(kitchen(1200.0, 800.0))
        compose.setContent { MaterialTheme { LeagueTable(live.value, now = { compose.mainClock.currentTime }) } }
        step(100)
        assertEquals(listOf("Oven", "Kettle", "Everything else"), shownNames())

        compose.runOnIdle { live.value = kitchen(1200.0, 2800.0) }
        step(50)
        // Laid out in its new place, though still drawn sliding up from the old one.
        assertEquals(listOf("Kettle", "Oven", "Everything else"), shownNames())
        row("Kettle").assert(SemanticsMatcher.expectValue(LeagueSliding, true))
        share("Kettle").assert(SemanticsMatcher.expectValue(LeagueFlash, "up"))
        // Part way through, the watts sit between the old and new figure.
        step(150)
        val mid = compose.onNode(hasTestTag("league-watts") and androidx.compose.ui.test.hasAnyAncestor(hasTestTag("league-row") and androidx.compose.ui.test.hasAnyDescendant(hasText("Kettle"))), useUnmergedTree = true)
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text
        val midW = if (mid.endsWith("kW")) mid.dropLast(3).toDouble() * 1000 else mid.dropLast(2).toDouble()
        assertTrue(midW > 800 && midW < 2800, "counting: $mid")
        step(1_000)
        compose.onNodeWithText("2.80 kW").assertIsDisplayed()
        compose.onNodeWithText("56%").assertIsDisplayed()
        row("Kettle").assert(SemanticsMatcher.expectValue(LeagueSliding, false))
        share("Kettle").assert(SemanticsMatcher.expectValue(LeagueFlash, null))

        // Oven flickers back above inside 2 s of the swap: they hold their places, then swap once the 2 s are up.
        compose.runOnIdle { live.value = kitchen(2900.0, 2800.0) }
        step(300)
        assertEquals(listOf("Kettle", "Oven", "Everything else"), shownNames())
        step(1_000)
        assertEquals(listOf("Oven", "Kettle", "Everything else"), shownNames())
        // Oven's flash still clears although it changed place mid-flash.
        step(800)
        share("Oven").assert(SemanticsMatcher.expectValue(LeagueFlash, null))
        // A falling share flashes red.
        compose.runOnIdle { live.value = kitchen(2900.0, 1000.0) }
        step(200)
        share("Kettle").assert(SemanticsMatcher.expectValue(LeagueFlash, "down"))
    }

    private val places = LivePower(
        watts = 6000.0,
        rooms = listOf(heating.app.data.RoomPower("kitchen", "Kitchen", 2800.0)),
        areas = listOf(heating.app.data.AreaPower("bathroom", "Bathroom", 2500.0)),
        items = listOf(
            ItemPower("Kettle Plug", "Kettle", 2000.0, room = "kitchen", job = "monitored"),
            ItemPower("Toaster Plug", "Toaster", 800.0, room = "kitchen", job = "monitored"),
            ItemPower("Shower Clamp", "Shower", 2500.0, area = "bathroom", job = "area"),
            // Inside the bathroom's clamp already, so not counted again.
            ItemPower("Fan Plug", "Fan", 40.0, area = "bathroom", job = "monitored"),
            ItemPower("Fridge Plug", "Fridge", 90.0, job = "monitored"),
            ItemPower("Garage Clamp", "Garage Clamp", 400.0),
        ),
    )

    // BDD: Toggling the league table to Rooms ranks places instead of items
    @Test
    fun `toggling the league table to Rooms ranks rooms, areas and devices in neither, leaves out meters with no job, and is remembered`() {
        val rooms = leagueRows(places, LeagueView.ROOMS)
        // The garage clamp has no job, so it is in no place: its 400 W sits in everything else.
        assertEquals(listOf("Kitchen", "Bathroom", "Not in a room", "Everything else"), rooms.map { it.name })
        assertEquals(listOf("2.80 kW", "2.50 kW", "90 W", "610 W"), rooms.map { leagueWatts(it) })
        assertEquals(listOf("47%", "42%", "2%", "10%"), rooms.map { leagueShare(it) })
        // An area whose whole-area meter has no reading adds up its devices instead.
        val unmeasured = places.copy(items = places.items.map { if (it.meter == "Shower Clamp") it.copy(watts = null) else it })
        assertEquals(40.0, leagueRows(unmeasured, LeagueView.ROOMS).first { it.name == "Bathroom" }.watts)

        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("league", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val shown = mutableStateOf(true)
        compose.setContent { MaterialTheme { if (shown.value) LeagueTable(places) } }
        compose.onNodeWithTag("league-items").assertIsSelected()
        compose.onNodeWithText("Shower").assertIsDisplayed()
        // A tap; Robolectric does not deliver touches to Material 3 segmented buttons, so the click action is used.
        compose.onNodeWithTag("league-rooms").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.onNodeWithTag("league-rooms").assertIsSelected()
        assertEquals(listOf("Kitchen", "Bathroom", "Not in a room", "Everything else"), shownNames())
        // Remembered on this device: the table comes back showing Rooms.
        compose.runOnIdle { shown.value = false }
        compose.runOnIdle { shown.value = true }
        compose.onNodeWithTag("league-rooms").assertIsSelected()
        assertEquals(listOf("Kitchen", "Bathroom", "Not in a room", "Everything else"), shownNames())
    }

    // BDD: League row for a meter with no job opens its settings
    @Test
    fun `a league row for a meter with no job says so, and opens its settings for an admin to name it and give it a job`() {
        val api = heating.app.data.FakeHeatingApi()
        api.meters["Garage Clamp"] = heating.app.data.Meter("Garage Clamp", power = 400.0)
        val garage = heating.app.data.Room("garage", "Garage", heating.app.data.Control.GROUPED, heating.app.data.Mode.OFF, null, null, false, emptyList(), emptyList(), emptyList(), false)
        val messages = mutableListOf<String>()
        val live = LivePower(watts = 3000.0, items = listOf(ItemPower("Garage Clamp", "Garage Clamp", 400.0), ItemPower("Fridge Plug", "Fridge", 90.0, job = "monitored")))
        compose.setContent {
            MaterialTheme {
                val setUp = rememberMeterSetUp(api, listOf(garage)) { messages += it }
                LeagueTable(live, onSetUp = setUp::open)
                setUp.Dialog()
            }
        }
        compose.onAllNodesWithTag("league-note", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("No job yet", useUnmergedTree = true).assertExists()

        row("Garage Clamp").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.onNodeWithText("What does Garage Clamp measure?").assertIsDisplayed()
        compose.onNodeWithTag("meter-settings-label").performTextInput("Workshop")
        compose.onNodeWithText("Monitor one device").performClick()
        // The room menu opens in a popup window Robolectric does not reach; picking a place is Manage energy's same picker.
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertEquals(listOf("labelMeter Garage Clamp Workshop", "setMeterJob Garage Clamp ${heating.app.data.MeterJob("monitored")}"), api.calls.filter { it.startsWith("labelMeter") || it.startsWith("setMeterJob") })
        assertEquals(listOf("Workshop updated"), messages)
    }

    @Test
    fun `members with energy access see a meter with no job but cannot change it`() {
        compose.setContent { MaterialTheme { LeagueTable(LivePower(watts = 3000.0, items = listOf(ItemPower("Garage Clamp", "Garage Clamp", 400.0)))) } }
        compose.onNodeWithText("No job yet", useUnmergedTree = true).assertExists()
        row("Garage Clamp").assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsActions.OnClick))
    }
}
