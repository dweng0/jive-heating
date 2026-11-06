package heating.app.ui.energy

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import heating.app.data.EnergyArea
import heating.app.data.EnergySettings
import heating.app.data.FakeHeatingApi
import heating.app.data.HeatingApi
import heating.app.data.Meter
import heating.app.data.MeterJob
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The energy management screen, against a service that keeps the meters, areas and settings it is given. */
@RunWith(RobolectricTestRunner::class)
class EnergyManageScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<String>()
    private var settings = EnergySettings(electricityMeter = "CCU Clamp")
    private val meters = linkedMapOf<String, Meter>()
    private val areas = mutableListOf<EnergyArea>()

    private val service: HeatingApi = object : HeatingApi by FakeHeatingApi() {
        override suspend fun energySettings() = settings
        override suspend fun meters(unassignedOnly: Boolean) = meters.values.map { it.copy(inUse = it.name == settings.electricityMeter) }
        override suspend fun energyAreas() = areas.map { a -> a.copy(wholeMeter = meters.values.firstOrNull { it.measures == a.id }?.name) }
        override suspend fun addEnergyArea(name: String) = EnergyArea(name.lowercase(), name).also { areas += it; sent += "add $name" }
        override suspend fun useMeterForHouse(meter: String): EnergySettings {
            sent += "house $meter"
            settings = settings.copy(electricityMeter = meter)
            return settings
        }
        override suspend fun stopUsingMeterForHouse(meter: String): EnergySettings {
            sent += "stop house $meter"
            settings = settings.copy(electricityMeter = null)
            return settings
        }
        override suspend fun labelMeter(meter: String, label: String): Meter {
            sent += "label $meter $label"
            meters[meter] = meters.getValue(meter).copy(label = label.ifBlank { meter })
            return meters.getValue(meter)
        }
        override suspend fun setMeterJob(meter: String, job: MeterJob): Meter {
            sent += "job $meter ${job.job} ${job.room ?: ""}${job.area ?: ""}".trim()
            if (job.job == "house") settings = settings.copy(electricityMeter = meter)
            else if (settings.electricityMeter == meter) settings = settings.copy(electricityMeter = null)
            meters[meter] = meters.getValue(meter).copy(
                monitored = job.job == "monitored", room = job.room.takeIf { job.job == "monitored" },
                area = job.area.takeIf { job.job == "monitored" }, measures = job.area.takeIf { job.job == "area" },
            )
            return meters.getValue(meter)
        }
        override suspend fun stopMonitoringMeter(meter: String) {
            sent += "stop monitoring $meter"
            meters[meter] = meters.getValue(meter).copy(monitored = false, room = null, area = null)
        }
        override suspend fun setDailyTarget(kwh: Double?): EnergySettings {
            sent += "target $kwh"
            settings = settings.copy(dailyKwhTarget = kwh)
            return settings
        }
    }

    private fun meter(name: String, power: Double = 50.0, more: (Meter) -> Meter = { it }) {
        meters[name] = more(Meter(name, power = power, label = name, device = name))
    }

    private fun show() {
        compose.setContent { MaterialTheme { EnergyManageScreen(service, emptyList(), androidx.compose.foundation.layout.PaddingValues()) } }
        compose.waitForIdle()
    }

    private fun tap(text: String) {
        val node = compose.onAllNodesWithText(text)[0]
        // Nodes in a dialog are not in the screen's scrolling column.
        runCatching { node.performScrollTo() }
        node.performClick()
        compose.waitForIdle()
    }

    // BDD: Admins have an energy management page
    @Test
    fun `the management screen has every section and the main meter's power`() {
        meter("CCU Clamp", 757.0)
        show()
        compose.onNodeWithText("757 W now").assertIsDisplayed()
        for (title in listOf("Main meter", "Daily target", "Areas", "Monitored devices", "Waiting to be set up")) {
            compose.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        }
    }

    // BDD: A clamp fitted the wrong way round is still counted as use
    @Test
    fun `a clamp that reads backwards says its use is still counted`() {
        meter("CCU Clamp")
        meter("Mains and lights B") { it.copy(reversed = true, clamp = "B", device = "Mains and lights") }
        show()
        compose.onNodeWithText("This clamp reads backwards. Its use is still counted.").performScrollTo().assertIsDisplayed()
    }

    // BDD: An admin names a meter
    @Test
    fun `an admin renames a clamp and its label is shown`() {
        meter("Mains and lights A") { it.copy(clamp = "A", device = "Mains and lights") }
        settings = settings.copy(electricityMeter = "Mains and lights A")
        show()
        tap("Rename")
        compose.onNodeWithTag("meter-label").performTextInput("Mains")
        tap("Save")
        compose.onNodeWithText("Mains").assertIsDisplayed()
        compose.onNodeWithText("Mains and lights A · 50 W now").assertIsDisplayed()
        assertEquals("label Mains and lights A Mains", sent.last())
    }

    // BDD: An admin changes or removes the main meter from the page
    @Test
    fun `the main meter is changed, then stopped after a confirmation`() {
        meter("CCU Clamp")
        meter("Mains and lights A")
        meter("Lights CT") { it.copy(reportsEnergy = false) }
        show()
        tap("Change")
        // Only meters that report energy and do nothing else are offered.
        assertEquals(0, compose.onAllNodesWithText("Lights CT · 50 W now").fetchSemanticsNodes().size)
        tap("Mains and lights A · 50 W now")
        assertEquals("house Mains and lights A", sent.last())
        tap("Stop using")
        compose.onAllNodesWithText("Stop using")[1].performClick()
        compose.waitForIdle()
        assertEquals("stop house Mains and lights A", sent.last())
        compose.onNodeWithText("No main meter chosen.").assertIsDisplayed()
    }

    // BDD: An admin sets up areas from the page
    @Test
    fun `an area gets a whole-area clamp and shows its power`() {
        meter("CCU Clamp")
        meter("Mains and lights B", 25.5)
        areas += EnergyArea("lights", "Lights")
        show()
        compose.onNodeWithTag("area-now-lights").performScrollTo()
        compose.onNodeWithText("Sum of its plugs").assertIsDisplayed()
        tap("Choose a clamp")
        compose.onAllNodesWithText("Mains and lights B").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        compose.onAllNodes(hasText("Mains and lights B")).let { it[it.fetchSemanticsNodes().size - 1].performClick() }
        compose.waitForIdle()
        assertEquals("job Mains and lights B area lights", sent.last())
        compose.onNodeWithText("26 W now on Mains and lights B").performScrollTo().assertIsDisplayed()
    }

    // BDD: A meter is given a new job in one step
    @Test
    fun `change job moves the main meter to monitoring one device`() {
        meter("CCU Clamp")
        show()
        tap("Change job")
        tap("Monitor one device")
        tap("Save")
        assertEquals("job CCU Clamp monitored", sent.last())
        compose.onNodeWithText("No main meter chosen.").assertIsDisplayed()
        compose.onNodeWithText("Stop monitoring").performScrollTo().assertIsDisplayed()
    }

    // BDD: A meter is given a new job in one step
    @Test
    fun `a meter with no energy total cannot be the main meter or monitored`() {
        meter("CCU Clamp")
        meter("Lights CT") { it.copy(reportsEnergy = false) }
        show()
        compose.onAllNodesWithText("Change job")[1].performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(2, compose.onAllNodesWithText("This meter does not report energy").fetchSemanticsNodes().size)
    }

    // BDD: An admin stops monitoring a device
    @Test
    fun `stop monitoring asks first`() {
        meter("CCU Clamp")
        meter("Desk Plug") { it.copy(monitored = true) }
        show()
        tap("Stop monitoring")
        compose.onAllNodesWithText("Stop monitoring")[1].performClick()
        compose.waitForIdle()
        assertEquals("stop monitoring Desk Plug", sent.last())
    }

    // BDD: An admin sets a daily target
    @Test
    fun `the daily target is set with its cost`() {
        meter("CCU Clamp")
        show()
        tap("Set a target")
        compose.onNodeWithTag("target-input").performTextClearance()
        compose.onNodeWithTag("target-input").performTextInput("12")
        tap("Save")
        assertEquals("target 12.0", sent.last())
        compose.onNodeWithText("12.0 kWh a day").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("£3.24 a day at today's price").assertIsDisplayed()
        tap("Clear")
        assertEquals("target null", sent.last())
    }
}
