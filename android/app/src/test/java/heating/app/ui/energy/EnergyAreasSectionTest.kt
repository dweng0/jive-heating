package heating.app.ui.energy

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import heating.app.data.EnergyArea
import heating.app.data.FakeHeatingApi
import heating.app.data.HeatingApi
import heating.app.data.HeatingApiException
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class EnergyAreasSectionTest {
    @get:Rule
    val compose = createComposeRule()

    private val areas = mutableListOf<EnergyArea>()
    private val sent = mutableListOf<String>()

    private val service: HeatingApi = object : HeatingApi by FakeHeatingApi() {
        override suspend fun energyAreas() = areas.toList()
        override suspend fun addEnergyArea(name: String): EnergyArea {
            sent += "add $name"
            if (areas.any { it.name.equals(name, ignoreCase = true) }) throw HeatingApiException(409, "an area called $name already exists")
            return EnergyArea(name.lowercase().replace(Regex("[^a-z0-9]+"), "-"), name).also { areas += it }
        }
        override suspend fun renameEnergyArea(id: String, name: String): EnergyArea {
            sent += "rename $id $name"
            return EnergyArea(id, name).also { renamed -> areas.replaceAll { if (it.id == id) renamed else it } }
        }
        override suspend fun deleteEnergyArea(id: String) {
            sent += "delete $id"
            areas.removeAll { it.id == id }
        }
    }

    private fun show() {
        compose.setContent { MaterialTheme { EnergyAreasSection(service) } }
        compose.waitForIdle()
    }

    private fun add(name: String) {
        compose.onNodeWithText("Add area").performClick()
        compose.onNodeWithTag("area-name").performTextInput(name)
        compose.onNodeWithTag("area-save").performClick()
        compose.waitForIdle()
    }

    // BDD: An admin creates an energy area
    @Test
    fun `an admin adds an area, renames it and deletes it`() {
        show()
        add("Garage/Office")
        compose.onNodeWithText("Garage/Office").assertIsDisplayed()
        assertEquals(listOf("add Garage/Office"), sent)

        compose.onNodeWithContentDescription("Rename Garage/Office").performClick()
        compose.onNodeWithTag("area-name").performTextClearance()
        compose.onNodeWithTag("area-name").performTextInput("Workshop")
        compose.onNodeWithTag("area-save").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Workshop").assertIsDisplayed()
        assertEquals("rename garage-office Workshop", sent.last())

        compose.onNodeWithContentDescription("Delete Workshop").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Workshop").assertDoesNotExist()
        assertEquals("delete garage-office", sent.last())
    }

    // BDD: An admin creates an energy area
    @Test
    fun `a name another area uses is refused and the dialog is still open`() {
        areas += EnergyArea("lights", "Lights")
        show()
        add("lights")
        compose.onNodeWithText("An area called lights already exists.").assertIsDisplayed()
        compose.onNodeWithTag("area-name").assertIsDisplayed()
    }
}
