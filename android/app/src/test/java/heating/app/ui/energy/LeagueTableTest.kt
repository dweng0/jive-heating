package heating.app.ui.energy

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
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
            ItemPower("Shower Clamp", "Shower", 9500.0),
            ItemPower("Lamp Plug", "Lamp", 3.0, job = "monitored"),
            ItemPower("Oven Clamp", "Oven", 1200.0, area = "kitchen-ring", job = "area"),
            ItemPower("Garage Plug", "Garage", null),
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
}
