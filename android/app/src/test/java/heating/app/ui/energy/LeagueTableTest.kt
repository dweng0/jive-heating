package heating.app.ui.energy

import androidx.compose.material3.MaterialTheme
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
}
