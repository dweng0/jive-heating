package heating.app.ui.energy

import heating.app.data.EnergyDashboard
import heating.app.data.EnergySettings
import kotlin.test.Test
import kotlin.test.assertEquals

class MoneyFormatTest {
    // BDD: Money is shown in the household's currency
    @Test
    fun moneyIsShownInTheHouseholdsCurrency() {
        assertEquals("£1.25", formatCost(125.0, "GBP"))
        assertEquals("£0.11", formatCost(11.0, "GBP"))
        assertEquals("£0.80", formatCost(80.0, "GBP"))
        assertEquals("€1.25", formatCost(125.0, "EUR"))
        assertEquals("–", formatCost(null, "GBP"))

        val pounds = EnergySettings()
        assertEquals("27.0p per kWh", formatPrice(PriceField.ELECTRICITY, pounds))
        assertEquals("30.0p per day", formatPrice(PriceField.GAS_STANDING, pounds))
        assertEquals("12.0 kW", formatPrice(PriceField.BOILER, pounds))
        assertEquals("27.0c per kWh", formatPrice(PriceField.ELECTRICITY, pounds.copy(currency = "EUR")))
        assertEquals("c per kWh", PriceField.ELECTRICITY.unit("EUR"))

        val euros = EnergyDashboard(period = "today", gasCost = 396.0, standingCost = 80.0, totalCost = 476.0, currency = "EUR")
        assertEquals(listOf("€3.96", "–", "€0.80", "€4.76"), energyLines(euros).map { it.cost })
    }
}
