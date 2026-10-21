package heating

import heating.api.ValveView
import heating.testing.TestHeating
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValveDiscoveryTest {

    // BDD: TRVZB valves are discovered from the bridge device list
    @Test
    fun `trvzb valves are discovered from the bridge device list`() {
        val t = TestHeating()
        t.discover("Lounge TRV 1", others = mapOf("Hall Sensor" to "SNZB-02"))
        t.api { client ->
            val names = client.get("/api/valves").body<List<ValveView>>().map { it.name }
            assertTrue("Lounge TRV 1" in names)
            assertFalse("Hall Sensor" in names)
        }
    }

    // BDD: New valves are unassigned
    @Test
    fun `new valves are unassigned`() {
        val t = TestHeating()
        t.discover("Lounge TRV 1")
        t.api { client ->
            val valves = client.get("/api/valves?unassigned=true").body<List<ValveView>>()
            assertEquals(listOf("Lounge TRV 1"), valves.map { it.name })
            assertNull(valves.single().room)
        }
    }

    // BDD: Valve state is ingested from its state topic
    @Test
    fun `valve state is ingested from its state topic`() {
        val t = TestHeating()
        t.discover("Lounge TRV 1")
        t.valveState(
            "Lounge TRV 1",
            """{"local_temperature":19.5,"occupied_heating_setpoint":20,"running_state":"heat","battery":80}""",
        )
        val valve = t.app.valves["Lounge TRV 1"]!!
        assertEquals(19.5, valve.currentTemperature)
        assertEquals(20.0, valve.reportedSetpoint)
        assertEquals("heat", valve.runningState)
        assertEquals(80, valve.battery)
    }

    // BDD: Partial state messages keep previously known fields
    @Test
    fun `partial state messages keep previously known fields`() {
        val t = TestHeating()
        t.discover("Lounge TRV 1")
        t.valveState("Lounge TRV 1", """{"local_temperature":19.5,"battery":80}""")
        t.valveState("Lounge TRV 1", """{"local_temperature":20.0}""")
        val valve = t.app.valves["Lounge TRV 1"]!!
        assertEquals(20.0, valve.currentTemperature)
        assertEquals(80, valve.battery)
    }

    // BDD: Valve goes offline
    @Test
    fun `valve goes offline`() {
        val t = TestHeating()
        t.discover("Lounge TRV 1")
        assertTrue(t.app.valves["Lounge TRV 1"]!!.online)
        t.availability("Lounge TRV 1", online = false)
        assertFalse(t.app.valves["Lounge TRV 1"]!!.online)
    }

    // BDD: Valve removed from Zigbee2MQTT is dropped from its room
    @Test
    fun `valve removed from zigbee2mqtt is dropped from its room`() {
        val t = TestHeating()
        t.discover("Lounge TRV 1")
        val room = t.app.rooms.create("Lounge")
        t.app.rooms.assignValve(room.id, "Lounge TRV 1")

        t.discover()

        val updated = t.app.rooms.get(room.id)
        assertFalse("Lounge TRV 1" in updated.valves)
        assertEquals(listOf("Lounge TRV 1 was removed from Zigbee2MQTT"), updated.notices)
    }
}
