package heating.app.ui.energy

import heating.app.data.Meter
import heating.app.data.OfflinePeriod
import heating.app.data.RoomPlug
import heating.app.data.RoomPlugs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoomPlugsFormatTest {
    private val fridge = RoomPlug(
        "Fridge Plug", power = 85.0, kwh = 0.4, cost = 10.8, missingMinutes = 40, incomplete = true,
        offline = listOf(OfflinePeriod("2026-10-12T14:20:00+01:00", "2026-10-12T15:00:00+01:00")),
    )
    private val kettle = RoomPlug("Kettle Plug", power = null, kwh = 4.23, cost = 114.2)

    // BDD: A plug is put in a room from the room's settings
    @Test
    fun aRoomsPlugsShowPowerNowAndCostToday() {
        val plugs = RoomPlugs("kitchen", currency = "GBP", kwh = 4.63, cost = 125.0, missingMinutes = 40, incomplete = true, plugs = listOf(fridge, kettle))

        assertEquals("Today £1.25", roomTotal(plugs))
        assertEquals("85 W now · £0.11 today", plugLine(fridge, "GBP"))
        assertEquals("– now · £1.14 today", plugLine(kettle, "GBP"))
        assertEquals("€0.11 today", plugLine(fridge, "EUR").substringAfter(" · "))

        assertEquals("Cost incomplete", COST_INCOMPLETE)
        assertEquals("Was offline 14:20 to 15:00. Some use could not be counted.", plugWarning(fridge))
        assertNull(plugWarning(kettle))
        // The room's triangle says which plugs were affected.
        assertEquals("Fridge Plug was offline 14:20 to 15:00. Some use could not be counted.", roomWarning(plugs))
        assertNull(roomWarning(plugs.copy(incomplete = false, plugs = listOf(kettle))))
    }

    // BDD: A plug that is offline now says so
    @Test
    fun aPlugThatIsOfflineNowSaysSo() {
        val tv = RoomPlug(
            "TV Plug", power = null, kwh = 0.5, cost = 13.5, incomplete = true, offlineSince = "2026-10-12T14:20:00+01:00",
        )
        val plugs = RoomPlugs("bedroom1", currency = "GBP", kwh = 0.5, cost = 13.5, incomplete = true, plugs = listOf(tv))

        // Never its last power.
        assertEquals("Offline · £0.14 today", plugLine(tv, "GBP"))
        assertEquals("Offline since 14:20. Cost will update when it's back.", plugWarning(tv))
        assertEquals("TV Plug offline since 14:20. Some use could not be counted.", roomWarning(plugs))
    }

    // BDD: A plug whose energy total stops going up is flagged
    @Test
    fun aPlugWhoseEnergyTotalStopsGoingUpIsFlagged() {
        val stuck = RoomPlug(
            "TV Plug", power = 60.0, kwh = 0.5, cost = 13.5, incomplete = true, totalStuckSince = "2026-10-12T14:00:00+01:00",
        )
        val plugs = RoomPlugs("bedroom1", currency = "GBP", kwh = 0.5, cost = 13.5, incomplete = true, plugs = listOf(stuck))

        // Its power is real, so it is still shown.
        assertEquals("60 W now · £0.14 today", plugLine(stuck, "GBP"))
        assertEquals("Using power but its energy total isn't going up. Cost may be too low.", plugWarning(stuck))
        assertEquals("TV Plug using power but its energy total isn't going up. Some use could not be counted.", roomWarning(plugs))

        // Offline now wins: no power reading to go by.
        assertEquals("Offline since 14:20. Cost will update when it's back.", plugWarning(stuck.copy(power = null, offlineSince = "2026-10-12T14:20:00+01:00")))

        // The total goes up again: the triangle goes.
        val counting = stuck.copy(incomplete = false, totalStuckSince = null)
        assertEquals(null, plugWarning(counting))
        assertEquals(null, roomWarning(plugs.copy(incomplete = false, plugs = listOf(counting))))
    }

    // BDD: A plug is put in a room from the room's settings
    @Test
    fun addPlugOffersMonitoredPlugsNotInTheRoom() {
        val meters = listOf(
            Meter("Main Clamp", inUse = true),
            Meter("Fridge Plug", monitored = true, room = "kitchen"),
            Meter("Lamp Plug", monitored = true, room = null),
            Meter("Garage Plug", monitored = true, room = "garage"),
            Meter("New Plug"),
        )
        assertEquals(listOf("Garage Plug", "Lamp Plug"), plugsToAdd(meters, "kitchen").map { it.name })
    }
}
