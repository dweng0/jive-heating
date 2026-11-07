package heating.app.data

import heating.app.ui.energy.NOT_CONVERTED
import heating.app.ui.energy.PriceField
import heating.app.ui.energy.currencyChoices
import heating.app.ui.energy.currencyLine
import heating.app.ui.energy.formatPrice
import heating.app.ui.energy.formatPower
import heating.app.ui.energy.dayBars
import heating.app.ui.energy.deviceLines
import heating.app.ui.energy.energyLines
import heating.app.ui.energy.noMeterNote
import heating.app.ui.energy.plugRoomName
import heating.app.ui.energy.plugsWithNoRoom
import heating.app.ui.energy.priceDraft
import heating.app.ui.energy.roomLines
import heating.app.ui.energy.toRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EnergyStoreTest {

    private val api = FakeHeatingApi()

    // BDD: An admin edits prices in the app
    @Test
    fun `an admin sees each price with its unit and saves a change`() = runTest {
        val store = EnergyStore(api, backgroundScope)
        store.start(admin = true)
        runCurrent()

        val settings = store.state.value.settings!!
        assertEquals(
            listOf("11.0p per kWh", "30.0p per day", "27.0p per kWh", "50.0p per day", "12.0 kW"),
            PriceField.entries.map { formatPrice(it, settings) },
        )

        val draft = priceDraft(settings) + (PriceField.ELECTRICITY to "24.5")
        store.savePrices(draft.toRequest()!!)
        runCurrent()

        assertTrue(api.calls.any { it.startsWith("saveEnergyPrices") && "electricityPencePerKwh=24.5" in it })
        assertEquals("24.5p per kWh", formatPrice(PriceField.ELECTRICITY, store.state.value.settings!!))
        assertEquals("11.0p per kWh", formatPrice(PriceField.GAS, store.state.value.settings!!))
    }

    @Test
    fun `impossible prices are not sent`() {
        val draft = priceDraft(EnergySettings())
        assertNull((draft + (PriceField.GAS to "-1")).toRequest())
        assertNull((draft + (PriceField.BOILER to "0")).toRequest())
        assertNull((draft + (PriceField.BOILER to "101")).toRequest())
        assertNull((draft + (PriceField.ELECTRICITY to "cheap")).toRequest())
        assertEquals(6.5, (draft + (PriceField.GAS to "6.5")).toRequest()!!.gasPencePerKwh)
    }

    // BDD: An admin chooses the household's currency
    @Test
    fun `an admin chooses the household's currency`() {
        val gbp = EnergySettings(currency = "GBP")
        assertEquals("Currency: £ Pound (GBP)", currencyLine(gbp))
        assertEquals(listOf("£ Pound (GBP)", "€ Euro (EUR)", "$ US dollar (USD)"), currencyChoices("GBP").map { it.second })
        // Another currency already set is offered by its code.
        assertEquals("SEK", currencyChoices("SEK").last().second)
        assertEquals("Prices are not converted. Check them for the new currency.", NOT_CONVERTED)

        // Only a changed currency is sent; prices are never converted.
        val draft = priceDraft(gbp)
        assertEquals(null, draft.toRequest()!!.currency)
        assertEquals(null, draft.toRequest(currency = "GBP", current = "GBP")!!.currency)
        val eur = draft.toRequest(currency = "EUR", current = "GBP")!!
        assertEquals("EUR", eur.currency)
        assertEquals(gbp.electricityPencePerKwh, eur.electricityPencePerKwh)
        assertEquals("c per kWh", PriceField.ELECTRICITY.unit("EUR").trim())
        assertEquals("Currency: € Euro (EUR)", currencyLine(gbp.copy(currency = "EUR")))
    }

    // BDD: New meters are offered in the app like new valves and sensors
    @Test
    fun `new meters are offered with the new valves and sensors and one is chosen for the house`() = runTest {
        api.meters["Main Clamp"] = Meter("Main Clamp", energy = 1523.4, power = 412.0)
        api.newValves += "Spare TRV"
        api.sensors["Hall Smoke"] = Sensor("Hall Smoke", room = null, online = true, temperature = 20.0, battery = 90, lowBattery = false)
        val store = EnergyStore(api, backgroundScope)
        store.start(admin = true)
        runCurrent()

        val waiting = store.state.value.waiting
        assertEquals(listOf("Spare TRV"), waiting.valves)
        assertEquals(listOf("Hall Smoke"), waiting.sensors)
        assertEquals(listOf("Main Clamp"), waiting.meters.map { it.name })
        assertEquals("412 W", formatPower(waiting.meters.single().power))

        store.useForHouse("Main Clamp")
        runCurrent()

        assertTrue("useMeterForHouse Main Clamp" in api.calls)
        assertEquals("Main Clamp", store.state.value.settings?.electricityMeter)
        assertEquals(emptyList(), store.state.value.waiting.meters)
    }

    // BDD: An admin monitors a plug
    @Test
    fun `an admin monitors a plug waiting to be set up`() = runTest {
        api.meters["Main Clamp"] = Meter("Main Clamp", energy = 1523.4, power = 412.0)
        api.meters["Fridge Plug"] = Meter("Fridge Plug", energy = 12.3, power = 85.0)
        val store = EnergyStore(api, backgroundScope)
        store.start(admin = true)
        runCurrent()

        store.monitor("Fridge Plug")
        runCurrent()

        assertTrue("monitorMeter Fridge Plug" in api.calls)
        assertEquals(listOf("Main Clamp"), store.state.value.waiting.meters.map { it.name })
        assertEquals(listOf("Fridge Plug"), api.meters(unassignedOnly = false).filter { it.monitored }.map { it.name })
    }

    // BDD: A plug is put in a room straight after monitoring it
    @Test
    fun `a plug is put in a room straight after monitoring it`() = runTest {
        listOf("Kettle Plug", "Fridge Plug", "Lamp Plug").forEach { api.meters[it] = Meter(it, energy = 3.1, power = 0.0) }
        val store = EnergyStore(api, backgroundScope)
        store.start(admin = true)
        runCurrent()

        store.monitor("Kettle Plug")
        runCurrent()
        assertEquals("Kettle Plug", store.state.value.placing)
        store.placeIn("kitchen")
        runCurrent()
        assertEquals(listOf("monitorMeter Kettle Plug", "putMeterInRoom kitchen Kettle Plug"), api.calls.takeLast(2))
        assertEquals(null, store.state.value.placing)
        assertEquals("kitchen", store.state.value.monitored.single { it.name == "Kettle Plug" }.room)

        // Not now: monitored, in no room.
        store.monitor("Fridge Plug")
        runCurrent()
        store.notNow()
        runCurrent()
        assertEquals(null, store.state.value.placing)
        assertEquals("monitorMeter Fridge Plug", api.calls.last())
        assertEquals(null, store.state.value.monitored.single { it.name == "Fridge Plug" }.room)

        // Putting it in the room fails: still monitored, the error is shown.
        store.monitor("Lamp Plug")
        runCurrent()
        api.fail = HeatingApiException(500, "The store is not answering")
        store.placeIn("lounge")
        runCurrent()
        assertEquals("The store is not answering.", store.state.value.message)
        assertEquals(null, store.state.value.placing)
        assertEquals(null, store.state.value.monitored.single { it.name == "Lamp Plug" }.room)
        assertTrue("Lamp Plug" in api.monitoredMeters)
    }

    // BDD: An admin puts a monitored plug in a room
    @Test
    fun `an admin puts a monitored plug in a room`() = runTest {
        api.meters["Fridge Plug"] = Meter("Fridge Plug", energy = 12.3, power = 85.0)
        api.meters["Kettle Plug"] = Meter("Kettle Plug", energy = 3.1, power = 0.0)
        api.monitoredMeters += listOf("Fridge Plug", "Kettle Plug")
        val store = EnergyStore(api, backgroundScope)
        store.start(admin = true)
        runCurrent()
        assertEquals(listOf("Fridge Plug", "Kettle Plug"), store.state.value.monitored.map { it.name })

        store.chooseRoom("Fridge Plug", "kitchen")
        store.chooseRoom("Kettle Plug", "kitchen")
        runCurrent()
        assertTrue("putMeterInRoom kitchen Fridge Plug" in api.calls)
        // A room holds any number of plugs.
        assertEquals(listOf("kitchen", "kitchen"), store.state.value.monitored.map { it.room })

        // Another room moves it.
        store.chooseRoom("Kettle Plug", "lounge")
        runCurrent()
        assertEquals("lounge", store.state.value.monitored.single { it.name == "Kettle Plug" }.room)

        // No room takes it out; it stays monitored.
        store.chooseRoom("Fridge Plug", null)
        runCurrent()
        assertTrue("takeMeterOutOfRoom kitchen Fridge Plug" in api.calls)
        assertEquals(null, api.meters(unassignedOnly = false).single { it.name == "Fridge Plug" }.room)
        assertEquals(listOf("Fridge Plug", "Kettle Plug"), store.state.value.monitored.map { it.name })
        assertEquals(null, store.state.value.monitored.single { it.name == "Fridge Plug" }.room)

        val rooms = mapOf("kitchen" to "Kitchen", "lounge" to "Lounge")
        assertEquals("Lounge", plugRoomName("lounge", rooms))
        assertEquals("No room", plugRoomName(null, rooms))
    }

    // BDD: Only plugs in no room are listed on the Rooms screen
    @Test
    fun `only plugs in no room are listed on the rooms screen`() = runTest {
        api.meters["Fridge Plug"] = Meter("Fridge Plug", energy = 12.3, power = 85.0)
        api.meters["Garage Plug"] = Meter("Garage Plug", energy = 3.1, power = 0.0)
        api.monitoredMeters += listOf("Fridge Plug", "Garage Plug")
        api.putMeterInRoom("kitchen", "Fridge Plug")
        val store = EnergyStore(api, backgroundScope)
        store.start(admin = true)
        runCurrent()
        assertEquals(listOf("Garage Plug"), plugsWithNoRoom(store.state.value.monitored).map { it.name })

        // Choosing a room takes it off the card; with every plug in a room there is nothing to show.
        store.chooseRoom("Garage Plug", "garage")
        runCurrent()
        assertEquals(emptyList(), plugsWithNoRoom(store.state.value.monitored))

        // Taken out in the room's settings: back on the card when the Rooms screen loads again.
        api.takeMeterOutOfRoom("kitchen", "Fridge Plug")
        store.loadWaiting()
        assertEquals(listOf("Fridge Plug"), plugsWithNoRoom(store.state.value.monitored).map { it.name })
    }

    // BDD: An admin stops monitoring a plug from the Rooms screen
    @Test
    fun `an admin stops monitoring a plug from the rooms screen`() = runTest {
        api.meters["Garage Plug"] = Meter("Garage Plug", energy = 3.1, power = 0.0)
        api.monitoredMeters += "Garage Plug"
        val store = EnergyStore(api, backgroundScope)
        store.start(admin = true)
        runCurrent()
        assertEquals(listOf("Garage Plug"), plugsWithNoRoom(store.state.value.monitored).map { it.name })

        store.stopMonitoring("Garage Plug")
        runCurrent()
        assertTrue("stopMonitoringMeter Garage Plug" in api.calls)
        assertTrue("ignoreDevice Garage Plug" in api.calls)
        // Off the card, not waiting to be set up, and listed with the ignored devices.
        assertEquals(emptyList(), store.state.value.monitored)
        assertEquals(emptyList(), store.state.value.waiting.meters)
        assertEquals(listOf("Garage Plug"), store.state.value.ignored)
    }

    // BDD: An admin ignores a device that needs no setting up
    @Test
    fun `an admin ignores a device that needs no setting up`() = runTest {
        api.meters["Garage Plug"] = Meter("Garage Plug", energy = 10.0, power = 5.0)
        api.meters["Main Clamp"] = Meter("Main Clamp", energy = 1523.4, power = 412.0)
        api.newValves += "Spare TRV"
        val store = EnergyStore(api, backgroundScope)
        store.start(admin = true)
        runCurrent()

        store.ignore("Garage Plug")
        store.ignore("Spare TRV")
        runCurrent()

        assertTrue("ignoreDevice Garage Plug" in api.calls)
        assertEquals(listOf("Main Clamp"), store.state.value.waiting.meters.map { it.name })
        assertEquals(emptyList(), store.state.value.waiting.valves)
    }

    // BDD: Ignored devices can be brought back
    @Test
    fun `ignored devices can be brought back`() = runTest {
        api.meters["Garage Plug"] = Meter("Garage Plug", energy = 10.0, power = 5.0)
        val store = EnergyStore(api, backgroundScope)
        store.start(admin = true)
        runCurrent()
        store.ignore("Garage Plug")
        runCurrent()
        assertEquals(listOf("Garage Plug"), store.state.value.ignored)
        assertEquals(emptyList(), store.state.value.waiting.meters)

        store.showAgain("Garage Plug")
        runCurrent()

        assertTrue("showDeviceAgain Garage Plug" in api.calls)
        assertEquals(emptyList(), store.state.value.ignored)
        assertEquals(listOf("Garage Plug"), store.state.value.waiting.meters.map { it.name })
    }

    private fun day(date: String, incomplete: Boolean = false, electricity: Boolean = true) = EnergyDay(
        date, boilerHours = 3.0, gasKwh = 36.0, gasCost = 396.0,
        electricityKwh = if (electricity) 8.0 else null, electricityCost = if (electricity) 216.0 else null,
        standingCost = if (electricity) 80.0 else 30.0, totalCost = if (electricity) 692.0 else 426.0,
        missingMinutes = if (incomplete) 120 else 0, incomplete = incomplete,
    )

    // BDD: The Energy tab shows energy use and cost
    @Test
    fun `the dashboard shows gas, electricity, standing charges and total with a bar per day`() = runTest {
        api.dashboards["today"] = EnergyDashboard(
            "today", boilerHours = 3.0, gasKwh = 36.0, gasCost = 396.0, electricityMeter = "Main Clamp",
            electricityKwh = 8.0, electricityCost = 216.0, gasStandingCost = 30.0, electricityStandingCost = 50.0,
            standingCost = 80.0, totalCost = 692.0, days = listOf(day("2026-10-12")),
        )
        api.dashboards["week"] = EnergyDashboard(
            "week", electricityMeter = "Main Clamp", totalCost = 4844.0, incomplete = true,
            days = (6..12).map { day("2026-10-%02d".format(it), incomplete = it == 11) },
        )
        val store = EnergyStore(api, backgroundScope)
        store.loadDashboard("today")
        runCurrent()

        val today = energyLines(store.state.value.dashboard!!)
        assertEquals(listOf("Gas", "Electricity", "Standing charges", "Total"), today.map { it.label })
        assertEquals(listOf("£3.96", "£2.16", "£0.80", "£6.92"), today.map { it.cost })
        assertEquals("36.0 kWh · 3.0 h boiler", today[0].detail)
        assertEquals("8.0 kWh", today[1].detail)
        assertNull(noMeterNote(store.state.value.dashboard!!))
        assertEquals(emptyList(), dayBars(store.state.value.dashboard!!))

        store.loadDashboard("week")
        runCurrent()
        val bars = dayBars(store.state.value.dashboard!!)
        assertEquals(7, bars.size)
        assertEquals(listOf("2026-10-11"), bars.filter { it.incomplete }.map { it.date })
        assertEquals(1.0f, bars.first().total)
        assertEquals("£48.44", energyLines(store.state.value.dashboard!!).last().cost)
    }

    // BDD: The Energy tab shows each monitored device
    @Test
    fun `the dashboard shows each monitored device under electricity`() {
        val d = EnergyDashboard(
            "today", electricityMeter = "Main Clamp", electricityKwh = 8.0, electricityCost = 216.0, gasCost = 396.0,
            standingCost = 80.0, totalCost = 692.0,
            devices = listOf(
                DeviceEnergy("Fridge Plug", kwh = 1.2, cost = 32.4),
                DeviceEnergy("Garage Plug", kwh = 0.6, cost = 16.2, missingMinutes = 10, incomplete = true),
            ),
        )
        val lines = deviceLines(d)
        assertEquals(listOf("Fridge Plug", "Garage Plug"), lines.map { it.name })
        assertEquals(listOf("£0.32", "£0.16"), lines.map { it.cost })
        assertEquals(listOf("1.2 kWh", "0.6 kWh"), lines.map { it.detail })
        assertNull(lines[0].warning)
        // Stale for a while: the cost so far, with the warning triangle.
        assertEquals("Incomplete: 10 min with no reading, so this is the cost so far", lines[1].warning)
        // Plugs are part of the house meter, never added to the total.
        assertEquals(listOf("£2.16", "£6.92"), energyLines(d).filter { it.label in setOf("Electricity", "Total") }.map { it.cost })
        assertEquals(emptyList(), deviceLines(d.copy(devices = emptyList())))
    }

    // BDD: A plug that is offline now says so
    @Test
    fun `a plug that is offline now says so on the dashboard`() {
        val d = EnergyDashboard(
            "today", electricityMeter = "Main Clamp", electricityKwh = 8.0, electricityCost = 216.0,
            devices = listOf(
                DeviceEnergy("Fridge Plug", kwh = 1.2, cost = 32.4),
                DeviceEnergy("TV Plug", kwh = 0.5, cost = 13.5, incomplete = true, offlineSince = "2026-10-12T14:20:00+01:00"),
            ),
        )
        val (fridge, tv) = deviceLines(d)
        assertEquals("1.2 kWh", fridge.detail)
        assertEquals(false, fridge.tapToRead)
        assertEquals("Offline · 0.5 kWh", tv.detail)
        assertEquals("£0.14", tv.cost)
        assertEquals("Offline since 14:20. Cost will update when it's back.", tv.warning)
        assertEquals(true, tv.tapToRead)
    }

    // BDD: A plug whose energy total stops going up is flagged
    @Test
    fun `a plug whose energy total stops going up is flagged on the dashboard`() {
        val d = EnergyDashboard(
            "today", electricityMeter = "Main Clamp", electricityKwh = 8.0, electricityCost = 216.0,
            devices = listOf(
                DeviceEnergy("Fridge Plug", kwh = 1.2, cost = 32.4),
                DeviceEnergy("Heater Plug", kwh = 0.2, cost = 5.4, incomplete = true, totalStuckSince = "2026-10-12T15:10:00+01:00"),
            ),
        )
        val (fridge, heater) = deviceLines(d)
        assertEquals(null, fridge.warning)
        assertEquals("0.2 kWh", heater.detail)
        assertEquals("Using power but its energy total isn't going up. Cost may be too low.", heater.warning)
        assertEquals(true, heater.tapToRead)
    }

    // BDD: The Energy tab shows each room's electricity
    @Test
    fun `the dashboard shows each room's electricity under electricity`() {
        val d = EnergyDashboard(
            "today", electricityMeter = "Main Clamp", electricityKwh = 8.0, electricityCost = 216.0, gasCost = 396.0,
            standingCost = 80.0, totalCost = 692.0, currency = "EUR",
            devices = listOf(
                DeviceEnergy("Charger Plug", kwh = 2.0, cost = 54.0),
                DeviceEnergy("Fridge Plug", kwh = 1.2, cost = 32.4, room = "kitchen"),
                DeviceEnergy("Garage Plug", kwh = 0.6, cost = 16.2, missingMinutes = 10, incomplete = true),
                DeviceEnergy("Lamp Plug", kwh = 0.3, cost = 8.1, missingMinutes = 15, incomplete = true, room = "lounge"),
            ),
            rooms = listOf(
                RoomEnergy("kitchen", "Kitchen", kwh = 1.2, cost = 32.4),
                RoomEnergy("lounge", "Lounge", kwh = 0.3, cost = 8.1, missingMinutes = 15, incomplete = true),
            ),
            everythingElse = EverythingElse(kwh = 3.9, cost = 105.3, missingMinutes = 15, incomplete = true),
        )
        val lines = roomLines(d)
        // Rooms by name, then plugs in no room, then everything else last.
        assertEquals(listOf("Kitchen", "Lounge", "Not in a room", "Everything else"), lines.map { it.name })
        assertEquals(listOf("€0.32", "€0.08", "€0.70", "€1.05"), lines.map { it.cost })
        assertEquals(listOf("1.2 kWh", "0.3 kWh", "2.6 kWh · Charger Plug, Garage Plug", "3.9 kWh"), lines.map { it.detail })
        // Everything else is incomplete while the main meter or any plug is.
        assertEquals("Incomplete: 15 min with no reading, so this is the cost so far", lines[3].warning)
        assertNull(lines[0].warning)
        // A stale plug: the room's cost so far, with the same warning triangle as a plug.
        assertEquals("Incomplete: 15 min with no reading, so this is the cost so far", lines[1].warning)
        assertEquals("Incomplete: 10 min with no reading, so this is the cost so far", lines[2].warning)
        // Rooms are "of which", never added to the house figures.
        assertEquals(listOf("€2.16", "€6.92"), energyLines(d).filter { it.label in setOf("Electricity", "Total") }.map { it.cost })
        // No rooms card without plugs in rooms.
        assertEquals(emptyList(), roomLines(d.copy(rooms = emptyList())))
    }

    @Test
    fun `without a house meter the dashboard asks for one`() {
        val gasOnly = EnergyDashboard("today", gasCost = 396.0, gasStandingCost = 30.0, standingCost = 30.0, totalCost = 426.0, days = listOf(day("2026-10-12", electricity = false)))
        assertEquals("Choose an electricity meter to see electricity use", noMeterNote(gasOnly))
        assertEquals("–", energyLines(gasOnly)[1].cost)
        assertEquals("£4.26", energyLines(gasOnly).last().cost)
    }

    @Test
    fun `members never ask for energy`() = runTest {
        api.me = AccountUser("u-sam", "sam", admin = false)
        val store = EnergyStore(api, backgroundScope)
        store.start(admin = false)
        runCurrent()

        assertNull(store.state.value.settings)
        assertEquals(emptyList(), api.calls)
    }
}
