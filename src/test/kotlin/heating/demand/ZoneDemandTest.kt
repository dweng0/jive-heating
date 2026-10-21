package heating.demand

import heating.api.RoomView
import heating.rooms.Mode
import heating.testing.TestHeating
import io.ktor.client.call.body
import io.ktor.client.request.get
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class ZoneDemandTest {

    private fun lounge(t: TestHeating, target: Double, vararg valves: String): String {
        t.discover(*valves)
        val room = t.app.rooms.create("Lounge")
        valves.forEach { t.app.rooms.assignValve(room.id, it) }
        t.app.zones.setMode(room.id, Mode.MANUAL, target)
        return room.id
    }

    private fun calling(t: TestHeating): Boolean {
        var result = false
        t.api { client -> result = client.get("/api/rooms").body<List<RoomView>>().single().zones.single().callingForHeat }
        return result
    }

    // BDD: Zone calls for heat when below target
    @Test
    fun `zone calls for heat when below target`() {
        val t = TestHeating()
        lounge(t, 21.0, "Lounge TRV 1")
        t.valveState("Lounge TRV 1", """{"local_temperature":20.0}""")
        assertEquals(true, calling(t))
    }

    // BDD: Zone stops calling for heat at target
    @Test
    fun `zone stops calling for heat at target`() {
        val t = TestHeating()
        lounge(t, 21.0, "Lounge TRV 1")
        t.valveState("Lounge TRV 1", """{"local_temperature":20.0}""")
        assertEquals(true, calling(t))
        t.valveState("Lounge TRV 1", """{"local_temperature":21.0}""")
        assertEquals(false, calling(t))
    }

    // BDD: Hysteresis stops chatter just below target
    @Test
    fun `hysteresis stops chatter just below target`() {
        val t = TestHeating()
        lounge(t, 21.0, "Lounge TRV 1")
        t.valveState("Lounge TRV 1", """{"local_temperature":21.0}""")
        assertEquals(false, calling(t))
        t.valveState("Lounge TRV 1", """{"local_temperature":20.8}""")
        assertEquals(false, calling(t))
        t.valveState("Lounge TRV 1", """{"local_temperature":20.5}""")
        assertEquals(true, calling(t))
    }

    // BDD: A zone whose valves cannot open does not call for heat
    @Test
    fun `a zone whose valves cannot open does not call for heat`() {
        val t = TestHeating()
        lounge(t, 26.5, "Lounge TRV 1")
        t.clock.advance(Duration.ofMinutes(2)) // long after the command: the valve really is at 7
        t.valveState("Lounge TRV 1", """{"local_temperature":21.8,"occupied_heating_setpoint":7.0,"running_state":"idle"}""")
        assertEquals(false, calling(t))
        t.valveState("Lounge TRV 1", """{"occupied_heating_setpoint":26.5}""")
        assertEquals(true, calling(t))
    }

    // BDD: A late report of the old setpoint does not stop a zone heating
    @Test
    fun `a late report of the old setpoint does not stop a zone heating`() {
        val t = TestHeating()
        val zoneId = lounge(t, 14.5, "Lounge TRV 1")
        t.valveState("Lounge TRV 1", """{"local_temperature":18.0,"occupied_heating_setpoint":14.5}""")
        t.clock.advance(Duration.ofMinutes(10))

        t.app.zones.setMode(zoneId, Mode.MANUAL, 21.0)
        t.valveState("Lounge TRV 1", """{"occupied_heating_setpoint":21.0}""")
        assertEquals(true, calling(t))
        t.clock.advance(Duration.ofSeconds(1))
        t.valveState("Lounge TRV 1", """{"occupied_heating_setpoint":14.5}""") // late, out of order
        assertEquals(true, calling(t))

        t.clock.advance(Duration.ofSeconds(65))
        t.app.tick()
        assertEquals(false, calling(t))
    }

    // BDD: A valve reporting running state heat counts as demand
    @Test
    fun `running state heat counts as demand`() {
        val t = TestHeating()
        lounge(t, 21.0, "Lounge TRV 1")
        t.valveState("Lounge TRV 1", """{"local_temperature":21.0,"running_state":"heat"}""")
        assertEquals(true, calling(t))
    }

    // BDD: Offline valves never create demand
    @Test
    fun `offline valves never create demand`() {
        val t = TestHeating()
        lounge(t, 21.0, "Lounge TRV 1")
        t.valveState("Lounge TRV 1", """{"local_temperature":15.0,"running_state":"heat"}""")
        t.availability("Lounge TRV 1", false)
        assertEquals(false, calling(t))
    }

    // BDD: Running state is polled every five minutes
    @Test
    fun `running state is polled every five minutes`() {
        val t = TestHeating()
        t.discover("A", "B")
        t.availability("A", true)
        t.availability("B", true)
        t.clock.advance(Duration.ofMinutes(5))
        t.app.tick()
        val expected = """{"running_state":""}"""
        assertEquals(listOf(expected), t.bus.published.filter { it.topic == "zigbee2mqtt/A/get" }.map { it.payload })
        assertEquals(listOf(expected), t.bus.published.filter { it.topic == "zigbee2mqtt/B/get" }.map { it.payload })
    }

    // BDD: A valve that goes silent is treated as offline
    @Test
    fun `a valve that goes silent is treated as offline`() {
        val t = TestHeating()
        lounge(t, 21.0, "Lounge TRV 1")
        t.valveState("Lounge TRV 1", """{"local_temperature":15.0,"running_state":"heat"}""")
        assertEquals(true, calling(t))
        t.clock.advance(Duration.ofMinutes(30))
        t.app.tick()
        assertEquals(false, t.app.valves["Lounge TRV 1"]!!.online)
        assertEquals(false, calling(t))
    }

    // BDD: A silent valve comes back when it reports again
    @Test
    fun `a silent valve comes back when it reports again`() {
        val t = TestHeating()
        lounge(t, 21.0, "Lounge TRV 1")
        t.valveState("Lounge TRV 1", """{"local_temperature":15.0}""")
        t.clock.advance(Duration.ofMinutes(30))
        t.app.tick()
        assertEquals(false, t.app.valves["Lounge TRV 1"]!!.online)
        t.valveState("Lounge TRV 1", """{"local_temperature":15.0}""")
        assertEquals(true, t.app.valves["Lounge TRV 1"]!!.online)
        assertEquals(true, calling(t))
    }
}
