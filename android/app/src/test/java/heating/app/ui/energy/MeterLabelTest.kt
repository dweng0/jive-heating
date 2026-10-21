package heating.app.ui.energy

import heating.app.data.Meter
import kotlin.test.Test
import kotlin.test.assertEquals

class MeterLabelTest {
    // BDD: The main meter is labelled as measuring the whole house
    @Test
    fun theMainMeterIsLabelledAsMeasuringTheWholeHouse() {
        assertEquals("Main meter (whole house)", MAIN_METER_BUTTON)
        assertEquals("Measures all electricity coming in", MAIN_METER_HINT)
        assertEquals("Electricity meter", meterKind(Meter(name = "Main Clamp")))
        assertEquals("Main meter", meterKind(Meter(name = "Main Clamp", inUse = true)))
    }
}
