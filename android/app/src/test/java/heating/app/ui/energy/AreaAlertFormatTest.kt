package heating.app.ui.energy

import heating.app.data.AreaAlert
import heating.app.data.FakeHeatingApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AreaAlertFormatTest {
    // BDD: An admin sets up an area notification in the app
    @Test
    fun `an admin sets up an area notification in the app`() = runTest {
        assertEquals("Notifies you after 2 h", alertLine(AreaAlert(2.0, "me", "jane", mine = true)))
        assertEquals("Notifies everyone after 1 h 30 min", alertLine(AreaAlert(1.5, "everyone", "jane", mine = true)))
        assertEquals("Notifies john after 30 min", alertLine(AreaAlert(0.5, "me", "john", mine = false)))
        assertNull(alertLine(AreaAlert()))
        assertEquals(2.0, alertHoursOrNull("2"))
        assertEquals(1.5, alertHoursOrNull("1,5"))
        listOf("0", "0.2", "1.2", "25", "x").forEach { assertNull(alertHoursOrNull(it), it) }

        // Save sends hours and who; Turn off clears it.
        val api = FakeHeatingApi()
        assertEquals("Notifies you after 2 h", alertLine(api.setAreaAlert("lights", 2.0, "me")))
        api.clearAreaAlert("lights")
        assertNull(alertLine(api.areaAlert("lights")))
        assertEquals(listOf("setAreaAlert lights 2.0 me", "clearAreaAlert lights", "areaAlert lights"), api.calls.takeLast(3))
    }
}
