package heating.app.ui.energy

import heating.app.data.DevicePower
import heating.app.data.DeviceEnergy
import heating.app.data.EnergyDashboard
import heating.app.data.EverythingElse
import heating.app.data.LivePower
import heating.app.data.PowerBands
import heating.app.data.PowerPoint
import heating.app.data.RoomEnergy
import heating.app.data.RoomPower
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LivePowerFormatTest {
    private val bands = PowerBands(green = 350.0, red = 4000.0)
    private val today = EnergyDashboard(
        "today",
        electricityMeter = "Main Clamp",
        electricityKwh = 8.0,
        electricityCost = 216.0,
        currency = "GBP",
        devices = listOf(DeviceEnergy("Fridge Plug", kwh = 1.2, cost = 32.4)),
        rooms = listOf(RoomEnergy("lounge", "Lounge", kwh = 1.2, cost = 32.4)),
        everythingElse = EverythingElse(kwh = 5.6, cost = 151.2),
    )

    private fun live(watts: Double?, lounge: Double? = 500.0, fridge: Double? = 100.0, rest: Double? = watts?.let { it - 600 }) =
        LivePower(watts, "2026-10-24T09:00:00+01:00", listOf(RoomPower("lounge", "Lounge", lounge)), listOf(DevicePower("Fridge Plug", fridge)), rest)

    // BDD: The dial moves smoothly
    @Test
    fun theNeedleEasesToANewReadingWithoutPassingIt() {
        val steps = (0..60).map { sweep(500.0, 3000.0, it * SWEEP_MS / 60.0) }
        assertEquals(500.0, steps.first(), 1e-9)
        assertEquals(3000.0, steps.last(), 1e-9)
        // Many steps on the way, always rising, never past the reading.
        assertTrue(steps.count { it > 500 && it < 3000 } > 30)
        steps.zipWithNext().forEach { (a, b) -> assertTrue(b >= a) }
        assertTrue(steps.all { it <= 3000.0 })
        // Falling works the same way, and it is done after about a second.
        assertTrue((0..60).map { sweep(3000.0, 500.0, it * SWEEP_MS / 60.0) }.all { it in 500.0..3000.0 })
        assertEquals(500.0, sweep(3000.0, 500.0, SWEEP_MS * 2.0), 1e-9)
        assertEquals(1000, SWEEP_MS)
    }

    // BDD: The dial colours follow the household's own use
    @Test
    fun theDialIsColouredByTheHouseholdsOwnBands() {
        assertEquals(PowerZone.LOW, powerZone(300.0, bands))
        assertEquals(PowerZone.LOW, powerZone(350.0, bands))
        assertEquals(PowerZone.MID, powerZone(1500.0, bands))
        assertEquals(PowerZone.HIGH, powerZone(4000.0, bands))
        // Under a day of readings: one neutral colour.
        assertEquals(PowerZone.NEUTRAL, powerZone(300.0, null))
        assertEquals(PowerZone.NEUTRAL, powerZone(4500.0, null))
    }

    // BDD: The dial shows today's use
    @Test
    fun underTheDialAreTodaysUseAndTheLast30Minutes() {
        assertEquals("Today 8.0 kWh · £2.16", todayLine(today))
        assertEquals("2.35 kW", dialReading(2350.0))
        assertEquals("850 W", dialReading(850.0))
        // Readings older than 30 minutes drop off; a newer reading is added once.
        val recent = listOf(PowerPoint("2026-10-24T08:25:00+01:00", 400.0), PowerPoint("2026-10-24T08:45:00+01:00", 600.0))
        val kept = withReading(recent, live(900.0).copy(at = "2026-10-24T09:00:00+01:00"))
        assertEquals(listOf(600.0, 900.0), kept.map { it.watts })
        assertEquals(kept, withReading(kept, live(900.0).copy(at = "2026-10-24T09:00:00+01:00")))
    }

    // BDD: Room cards show live and daily use
    @Test
    fun roomCardsShowLiveWattsTodayAndShare() {
        val cards = liveCards(live(2000.0, lounge = 500.0, fridge = 100.0, rest = 1400.0), today)
        val lounge = cards.first { it.key == "room-lounge" }
        assertEquals("Lounge", lounge.name)
        assertEquals("500 W", lounge.now)
        assertEquals(0.25, lounge.share!!, 1e-9)
        assertEquals("25% of the house", lounge.shareText)
        assertEquals("Today 1.2 kWh · £0.32", lounge.today)
        // A monitored device in no room has its own card; rooms come first, everything else last.
        assertEquals(listOf("room-lounge", "device-Fridge Plug", "rest"), cards.map { it.key })
        assertEquals("Not in a room", cards[1].detail)
    }

    // BDD: Everything else is the unattributed remainder
    @Test
    fun everythingElseIsTheRemainderAndGoesWhileStale() {
        val rest = liveCards(live(2000.0, rest = 800.0), today).last()
        assertEquals("Everything else", rest.name)
        assertEquals("800 W", rest.now)
        assertEquals("Today 5.6 kWh · £1.51", rest.today)
        assertTrue(liveCards(live(null, rest = null), today).none { it.key == "rest" })
    }

    // BDD: A stale house reading is not shown as live
    @Test
    fun aStaleHouseReadingIsNotShownAsLive() {
        assertEquals("No reading", dialReading(null))
        val cards = liveCards(live(null, lounge = null, fridge = null, rest = null), today)
        assertEquals("–", cards.first().now)
        assertEquals(null, cards.first().share)
        assertEquals("", cards.first().shareText)
    }

    // BDD: The dial's scale fits the household's biggest appliances
    @Test
    fun theDialRunsTo8KwOnASquareRootScale() {
        assertEquals(8000.0, dialMax(500.0))
        assertEquals(8000.0, dialMax(null))
        assertEquals(8000.0, dialMax(8000.0))
        // Above 8 kW the scale grows in whole 2 kW steps.
        assertEquals(10000.0, dialMax(8100.0))
        assertEquals(12000.0, dialMax(11000.0))
        assertEquals(listOf(0.0, 500.0, 1000.0, 2000.0, 4000.0, 6000.0, 8000.0), dialTicks(8000.0))
        assertEquals(listOf(0.0, 500.0, 1000.0, 2000.0, 4000.0, 6000.0, 8000.0, 10000.0), dialTicks(10000.0))
        assertEquals(listOf("0", "0.5", "1", "2", "4", "6", "8"), dialTicks(8000.0).map(::tickLabel))
        assertEquals(0.25, dialFraction(500.0, 8000.0), 1e-9)
        assertEquals(0.5, dialFraction(2000.0, 8000.0), 1e-9)
        assertEquals(0.75, dialFraction(4500.0, 8000.0), 1e-9)
        assertEquals(1.0, dialFraction(8000.0, 8000.0), 1e-9)
        assertEquals(0.0, dialFraction(-5.0, 8000.0), 1e-9)
        assertEquals(1.0, dialFraction(9000.0, 8000.0), 1e-9)
    }
}
