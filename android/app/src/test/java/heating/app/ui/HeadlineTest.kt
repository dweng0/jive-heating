package heating.app.ui

import heating.app.data.Boiler
import heating.app.data.Boost
import heating.app.data.Control
import heating.app.data.Group
import heating.app.data.Holiday
import heating.app.data.Mode
import heating.app.data.Room
import heating.app.data.Status
import heating.app.data.Zone
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class HeadlineTest {

    private fun zone(
        id: String = "z", mode: Mode = Mode.SCHEDULE, target: Double? = 20.0,
        calling: Boolean = false, boost: Boost? = null,
    ) = Zone(id, listOf("v"), mode, 20.0, target, 19.0, calling, calling, null, boost, null)

    private fun room(
        id: String, name: String = id, target: Double? = 20.0, heating: Boolean = false,
        zones: List<Zone> = listOf(zone(target = target, calling = heating)), control: Control = Control.GROUPED,
        mode: Mode = Mode.SCHEDULE,
    ) = Room(id, name, control, mode, 19.0, target, heating, listOf("v"), zones, emptyList(), true)

    private fun status(boiler: String? = null, until: String? = null, holiday: Holiday? = null, demand: Boolean = false) =
        Status(demand, holiday, "2026-10-17T08:00:00+01:00", "Europe/London", boiler?.let { Boiler(it, until) })

    private fun group(name: String, vararg rooms: String) = Group(name.lowercase(), name, rooms.toList(), 19.0, 21.0, "schedule", true)

    @Test
    fun `one room heating names the room and its target`() {
        assertEquals("Heating Bedroom 3 to 20°", headline(listOf(room("m", "Bedroom 3", heating = true), room("k")), emptyList(), status("heating")))
    }

    @Test
    fun `a room counts as heating when a zone calls for heat`() {
        val r = room("m", "Bedroom 3", zones = listOf(zone(calling = true)))
        assertEquals("Heating Bedroom 3 to 20°", headline(listOf(r.copy(heating = false)), emptyList(), null))
    }

    @Test
    fun `a boosted zone heats to the boost target`() {
        val r = room("m", "Bedroom 3", heating = true, zones = listOf(zone(calling = true, boost = Boost(23.0, "2026-10-17T09:00:00+01:00"))))
        assertEquals("Heating Bedroom 3 to 23°", headline(listOf(r), emptyList(), null))
    }

    @Test
    fun `independent room with mixed zone targets omits the target`() {
        val r = room(
            "m", "Bedroom 3", target = null, heating = true, control = Control.INDEPENDENT,
            zones = listOf(zone("a", target = 19.0, calling = true), zone("b", target = 21.0)),
        )
        assertEquals("Heating Bedroom 3", headline(listOf(r), emptyList(), null))
        val same = r.copy(zones = listOf(zone("a", target = 21.0, calling = true), zone("b", target = 21.0)))
        assertEquals("Heating Bedroom 3 to 21°", headline(listOf(same), emptyList(), null))
    }

    @Test
    fun `exactly a group's rooms heating names the group`() {
        val rooms = listOf(room("a", target = 21.0, heating = true), room("b", target = 21.0, heating = true), room("c"))
        val groups = listOf(group("Upstairs", "a", "b", "c"), group("Kids", "b", "a"))
        assertEquals("Heating the Kids group to 21°", headline(rooms, groups, null))
        val mixed = listOf(room("a", target = 21.0, heating = true), room("b", target = 19.0, heating = true))
        assertEquals("Heating the Kids group", headline(mixed, groups, null))
    }

    @Test
    fun `several rooms heating are counted`() {
        val rooms = listOf(room("a", target = 21.0, heating = true), room("b", target = 21.0, heating = true), room("c", target = 21.0, heating = true))
        assertEquals("Heating 3 rooms to 21°", headline(rooms, emptyList(), null))
        val mixed = rooms.mapIndexed { i, r -> r.copy(target = 19.0 + i) }
        assertEquals("Heating 3 rooms", headline(mixed, emptyList(), null))
    }

    @Test
    fun `nothing heating follows the boiler and the house`() {
        val rooms = listOf(room("a"), room("b"))
        assertEquals("Boiler finishing its run", headline(rooms, emptyList(), status("cycling", "2026-10-17T08:03:00+01:00")))
        assertEquals("Boiler resting", headline(rooms, emptyList(), status("resting", "2026-10-17T08:03:00+01:00")))
        assertEquals("All rooms comfortable", headline(rooms, emptyList(), status("idle")))
        assertEquals("All rooms comfortable", headline(rooms, emptyList(), null))
        val off = listOf(room("a", zones = listOf(zone(mode = Mode.OFF, target = null))), room("b", zones = emptyList(), mode = Mode.OFF))
        assertEquals("Heating is off", headline(off, emptyList(), status("idle")))
        val holiday = Holiday("2026-10-16", "2026-10-20", 12.0, active = true)
        assertEquals("On holiday", headline(rooms, emptyList(), status("idle", holiday = holiday)))
        assertEquals("All rooms comfortable", headline(rooms, emptyList(), status("idle", holiday = holiday.copy(active = false))))
    }

    @Test
    fun `countdown reads minutes and seconds`() {
        assertEquals("3:12", formatCountdown(192))
        assertEquals("0:05", formatCountdown(5))
        assertEquals("5:00", formatCountdown(300))
        assertEquals("any moment", formatCountdown(0))
        assertEquals("any moment", formatCountdown(-4))
    }

    @Test
    fun `seconds until an iso time round up`() {
        val now = Instant.parse("2026-10-17T07:00:00.500Z")
        assertEquals(192L, secondsUntil("2026-10-17T08:03:12+01:00", now))
        assertEquals(0L, secondsUntil("2026-10-17T06:59:00Z", now))
        assertEquals(null, secondsUntil(null, now))
        assertEquals(null, secondsUntil("soon", now))
    }

    @Test
    fun `chip labels for each boiler state`() {
        assertEquals("Heating", boilerChipLabel(BoilerState.HEATING, null))
        assertEquals("Finishing run · 3:12", boilerChipLabel(BoilerState.CYCLING, 192))
        assertEquals("Resting · 2:40", boilerChipLabel(BoilerState.RESTING, 160))
        assertEquals("Resting · any moment", boilerChipLabel(BoilerState.RESTING, 0))
        assertEquals("Idle", boilerChipLabel(BoilerState.IDLE, null))
    }

    @Test
    fun `boiler state falls back to heat demand for an older service`() {
        assertEquals(BoilerState.HEATING, boilerState(status(demand = true)))
        assertEquals(BoilerState.IDLE, boilerState(status(demand = false)))
        assertEquals(BoilerState.IDLE, boilerState(null))
        assertEquals(BoilerState.CYCLING, boilerState(status("cycling", demand = false)))
        assertEquals(BoilerState.RESTING, boilerState(status("resting", demand = true)))
    }
}
