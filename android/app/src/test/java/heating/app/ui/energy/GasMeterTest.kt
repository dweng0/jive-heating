package heating.app.ui.energy

import heating.app.data.EnergyDashboard
import heating.app.data.EnergyDay
import heating.app.data.GasDay
import heating.app.data.GasDayTotal
import heating.app.data.GasHalfHour
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GasMeterTest {
    private fun day(date: String, gasKwh: Double, estimated: Double) =
        EnergyDay(date, gasKwh = gasKwh, gasCost = gasKwh * 6.906, gasEstimatedKwh = estimated, standingCost = 77.3, totalCost = gasKwh * 6.906 + 77.3)

    // BDD: The Energy tab says where gas comes from
    @Test
    fun `the energy tab says where gas comes from`() {
        assertEquals("Gas is from the smart meter up to 6 Nov 00:00, estimated from the boiler's hours after.", gasSource("2026-11-06T00:00:00Z", java.time.ZoneId.of("Europe/London")))
        assertEquals("Gas is estimated from the boiler's hours.", gasSource(null, java.time.ZoneId.of("Europe/London")))

        val week = EnergyDashboard(
            period = "week", gasKwh = 378.0, gasCost = 2610.0, gasEstimatedKwh = 90.0, boilerHours = 13.5,
            days = listOf(day("2026-11-05", 68.0, 0.0), day("2026-11-06", 66.0, 66.0), day("2026-11-07", 24.0, 12.0)),
        )
        assertEquals("378.0 kWh · 13.5 h boiler", energyLines(week).first().detail)
        assertEquals("Part estimated", energyLines(week).first().chip)
        assertEquals("Estimated", energyLines(EnergyDashboard(period = "today", gasKwh = 24.0, gasEstimatedKwh = 24.0, boilerHours = 2.0)).first().chip)
        assertNull(energyLines(EnergyDashboard(period = "today", gasKwh = 68.0, gasEstimatedKwh = 0.0, boilerHours = 5.0)).first().chip)

        // The estimated part of each day's gas is its own, hatched part of the bar.
        val bars = dayBars(week)
        assertEquals(0f, bars[0].gasEstimated)
        assertEquals(bars[1].gas + bars[1].gasEstimated, bars[1].gasEstimated, 1e-6f)
        assertEquals(bars[2].gas, bars[2].gasEstimated, 1e-6f)
        // Each day of a week named, with where its gas came from and its total.
        assertEquals("Thu 5", bars[0].label)
        assertEquals("meter", bars[0].source)
        assertEquals("estimate", bars[1].source)
        assertEquals("£5.47", bars[0].totalText)
    }

    // BDD: The car dashboard shows the gas meter's latest day
    @Test
    fun `the car dashboard shows the gas meter's latest day`() {
        val gas = GasDayTotal("2026-11-05", 67.95, 469.26, 63.8)
        assertEquals("GAS · THU 5 NOV", gasDayLabel(gas.date))
        assertEquals("▲ 4.2 kWh vs Wed" to true, gasDelta(gas))
        assertEquals("▼ 3.8 kWh vs Wed" to false, gasDelta(gas.copy(kwh = 60.0)))
        assertNull(gasDelta(gas.copy(previousKwh = null)))
    }

    private fun gasDay(measured: Double? = 12.1) = GasDay(
        date = "2026-11-05", kwh = 53.5, cost = 369.47, currency = "GBP",
        halfHours = (0 until 48).map { GasHalfHour(java.time.Instant.parse("2026-11-05T00:00:00Z").plusSeconds(1800L * it).toString(), if (it == 37) 6.5 else 1.0) },
        previous = "2026-11-04", next = null, measuredBoilerKw = measured, boilerAverageKw = 12.0,
    )

    // BDD: A day of gas, half-hour by half-hour
    @Test
    fun `a day of gas, half-hour by half-hour`() {
        val day = gasDay()
        assertEquals("Thu 5 Nov", gasDayTitle(day.date))
        assertEquals("53.5 kWh · £3.69 · busiest 18:30, 6.5 kWh", gasDayFigures(day, java.time.ZoneId.of("Europe/London")))
        assertEquals("53.5 kWh · £3.69", gasDayFigures(day.copy(halfHours = day.halfHours.map { it.copy(kwh = 0.0) }), java.time.ZoneId.of("Europe/London")))
        // Each bar as a share of the busiest half-hour.
        val bars = gasBars(day)
        assertEquals(48, bars.size)
        assertEquals(1f, bars[37], 1e-6f)
        assertEquals(1f / 6.5f, bars[0], 1e-6f)
    }

    // BDD: The boiler's average burn rate from the meter
    @Test
    fun `the boiler's average burn rate from the meter`() {
        assertEquals("Boiler average from the meter: 12.1 kW", boilerMeasuredLine(gasDay()))
        assertEquals("Use 12.1 kW", useMeasuredLabel(gasDay()))
        assertNull(useMeasuredLabel(gasDay(12.05)))
        assertNull(boilerMeasuredLine(gasDay(null)))
    }
}
