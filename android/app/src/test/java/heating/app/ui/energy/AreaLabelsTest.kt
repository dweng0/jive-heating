package heating.app.ui.energy

import heating.app.data.Meter
import heating.app.data.RoomPlug
import kotlin.test.Test
import kotlin.test.assertEquals

class AreaLabelsTest {
    private val meters = listOf(
        Meter("Garage mains A", label = "Office lights"),
        Meter("Garage mains B", label = "Office main feed"),
        Meter("Fridge Plug"),
    )

    // BDD: An admin names a meter
    @Test
    fun `an area's charts and plug table show each meter by its label`() {
        assertEquals(
            listOf(WHOLE to "Office", "Garage mains A" to "Office lights", "Fridge Plug" to "Fridge Plug"),
            areaSlides("Office", listOf("Garage mains A", "Fridge Plug"), meters),
        )
        // A meter not in the list yet shows its name
        assertEquals(listOf(WHOLE to "Office", "New Plug" to "New Plug"), areaSlides("Office", listOf("New Plug"), emptyList()))
        assertEquals("Office lights", plugName(RoomPlug("Garage mains A", label = "Office lights")))
        assertEquals("Fridge Plug", plugName(RoomPlug("Fridge Plug")))
    }
}
