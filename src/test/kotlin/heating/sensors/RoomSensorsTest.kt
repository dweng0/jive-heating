package heating.sensors

import heating.api.RoomView
import heating.api.SensorView
import heating.api.heatingModule
import heating.heatingJson
import heating.rooms.Control
import heating.rooms.Mode
import heating.testing.TEST_TOKEN
import heating.testing.TestHeating
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomSensorsTest {

    private fun external(reading: Double) = buildJsonObject {
        put("temperature_sensor_select", JsonPrimitive("external"))
        put("external_temperature_input", JsonPrimitive(reading))
    }

    private fun input(reading: Double) = buildJsonObject { put("external_temperature_input", JsonPrimitive(reading)) }

    private val internal = buildJsonObject { put("temperature_sensor_select", JsonPrimitive("internal")) }

    private fun sensors(t: TestHeating, query: String = ""): List<SensorView> {
        var result = emptyList<SensorView>()
        t.api { client -> result = client.get("/api/sensors$query").body() }
        return result
    }

    private fun room(t: TestHeating, name: String): RoomView {
        var result: RoomView? = null
        t.api { client -> result = client.get("/api/rooms").body<List<RoomView>>().single { it.name == name } }
        return result!!
    }

    private fun putSensor(t: TestHeating, roomId: String, sensor: String): HttpStatusCode {
        var status = HttpStatusCode.OK
        t.api { client -> status = client.put("/api/rooms/$roomId/sensor/${sensor.replace(" ", "%20")}").status }
        return status
    }

    /** Room "Bedroom 3" with valve "Bedroom 3 TRV" and sensor "Landing Smoke" assigned; the sensor has reported [reading]. */
    private fun bedroom3(t: TestHeating, reading: Double? = 20.9, valveReading: Double? = null): String {
        t.discover("Bedroom 3 TRV", sensors = listOf("Landing Smoke"))
        val room = t.app.rooms.create("Bedroom 3")
        t.app.rooms.assignValve(room.id, "Bedroom 3 TRV")
        valveReading?.let { t.valveState("Bedroom 3 TRV", """{"local_temperature":$it}""") }
        reading?.let { t.sensorState("Landing Smoke", """{"temperature":$it,"battery":90}""") }
        assertEquals(HttpStatusCode.OK, putSensor(t, room.id, "Landing Smoke"))
        return room.id
    }

    // BDD: Temperature sensors are discovered from the bridge device list
    @Test
    fun `temperature sensors are discovered from the bridge device list`() {
        val t = TestHeating()
        t.discover("Bedroom 3 TRV", sensors = listOf("Landing Smoke"), others = mapOf("Hall Plug" to "SA-003-Zigbee"))
        val names = sensors(t).map { it.name }
        assertEquals(listOf("Landing Smoke"), names)
        assertTrue("Bedroom 3 TRV" !in names)
        assertTrue("Hall Plug" !in names)
    }

    // BDD: Sensor readings are ingested from its state topic
    @Test
    fun `sensor readings are ingested from its state topic`() {
        val t = TestHeating()
        t.discover(sensors = listOf("Landing Smoke"))
        t.bus.receive("zigbee2mqtt/Landing Smoke", """{"temperature":20.9,"battery":90}""")
        val sensor = sensors(t).single()
        assertEquals("Landing Smoke", sensor.name)
        assertEquals(20.9, sensor.temperature)
        assertEquals(90, sensor.battery)
        assertEquals(true, sensor.online)
        assertNull(sensor.room)
    }

    // BDD: Unassigned sensors are listed the same way as unassigned valves
    @Test
    fun `unassigned sensors are listed the same way as unassigned valves`() {
        val t = TestHeating()
        t.discover(sensors = listOf("Landing Smoke", "Hall Smoke"))
        val hall = t.app.rooms.create("Hall")
        assertEquals(HttpStatusCode.OK, putSensor(t, hall.id, "Hall Smoke"))
        assertEquals(listOf("Landing Smoke"), sensors(t, "?unassigned=true").map { it.name })
        assertEquals("Hall", sensors(t).single { it.name == "Hall Smoke" }.room)
    }

    // BDD: Sensors assigned to a room switch its valves to external
    @Test
    fun `sensors assigned to a room switch its valves to external`() {
        val t = TestHeating()
        bedroom3(t, reading = 20.9)
        val bedroom3 = room(t, "Bedroom 3")
        assertEquals("Landing Smoke", bedroom3.sensor)
        assertEquals("sensor", bedroom3.temperatureSource)
        assertEquals(external(20.9), t.sentTo("Bedroom 3 TRV").last())
    }

    // BDD: A sensor can only be in one room
    @Test
    fun `a sensor can only be in one room`() {
        val t = TestHeating()
        bedroom3(t)
        val lounge = t.app.rooms.create("Lounge")
        assertEquals(HttpStatusCode.Conflict, putSensor(t, lounge.id, "Landing Smoke"))
        assertEquals("Landing Smoke", room(t, "Bedroom 3").sensor)
    }

    // BDD: Adding a second sensor to a room replaces the first
    @Test
    fun `adding a second sensor to a room replaces the first`() {
        val t = TestHeating()
        t.discover("Bedroom 3 TRV", sensors = listOf("Landing Smoke", "Bedroom 3 Smoke"))
        val room = t.app.rooms.create("Bedroom 3")
        assertEquals(HttpStatusCode.OK, putSensor(t, room.id, "Landing Smoke"))
        assertEquals(HttpStatusCode.OK, putSensor(t, room.id, "Bedroom 3 Smoke"))
        assertEquals("Bedroom 3 Smoke", room(t, "Bedroom 3").sensor)
        assertNull(sensors(t).single { it.name == "Landing Smoke" }.room)
        assertEquals(listOf("Landing Smoke"), sensors(t, "?unassigned=true").map { it.name })
    }

    // BDD: A room with a sensor shows the sensor's temperature
    @Test
    fun `a room with a sensor shows the sensor's temperature`() {
        val t = TestHeating()
        bedroom3(t, reading = 20.9, valveReading = 23.0)
        assertEquals(20.9, room(t, "Bedroom 3").current)
    }

    // BDD: Heat demand uses the sensor's temperature
    @Test
    fun `heat demand uses the sensor's temperature`() {
        val t = TestHeating()
        val id = bedroom3(t, reading = 20.4, valveReading = 23.0)
        t.app.zones.setMode(id, Mode.MANUAL, 21.0)
        assertEquals(true, room(t, "Bedroom 3").zones.single().callingForHeat)
        t.sensorState("Landing Smoke", """{"temperature":21.0}""")
        assertEquals(false, room(t, "Bedroom 3").zones.single().callingForHeat)
    }

    // BDD: Independent rooms use the sensor for every zone
    @Test
    fun `independent rooms use the sensor for every zone`() {
        val t = TestHeating()
        t.discover("K1", "K2", sensors = listOf("Kitchen Smoke"))
        val kitchen = t.app.rooms.create("Kitchen")
        t.app.rooms.assignValve(kitchen.id, "K1")
        t.app.rooms.assignValve(kitchen.id, "K2")
        t.app.rooms.switchControl(kitchen.id, Control.INDEPENDENT, null)
        t.app.zones.setMode("${kitchen.id}:K1", Mode.MANUAL, 21.0)
        t.app.zones.setMode("${kitchen.id}:K2", Mode.MANUAL, 21.0)
        t.sensorState("Kitchen Smoke", """{"temperature":20.0}""")
        assertEquals(HttpStatusCode.OK, putSensor(t, kitchen.id, "Kitchen Smoke"))
        val zones = room(t, "Kitchen").zones
        assertEquals(listOf(true, true), zones.map { it.callingForHeat })
        assertEquals(listOf(20.0, 20.0), zones.map { it.current })
        assertEquals(external(20.0), t.sentTo("K1").last())
        assertEquals(external(20.0), t.sentTo("K2").last())
    }

    // BDD: A sensor reading that moved is passed on to the room's valves
    @Test
    fun `a sensor reading that moved is passed on to the room's valves`() {
        val t = TestHeating()
        bedroom3(t)
        t.sensorState("Landing Smoke", """{"temperature":21.5}""")
        assertEquals(input(21.5), t.sentTo("Bedroom 3 TRV").last())
    }

    // BDD: The sensor reading is re-sent to the valves every ten minutes
    @Test
    fun `the sensor reading is re-sent to the valves every ten minutes`() {
        val t = TestHeating()
        bedroom3(t, reading = 20.9)
        val before = t.sentTo("Bedroom 3 TRV").size
        t.clock.advance(Duration.ofMinutes(10))
        t.app.tick()
        val after = t.sentTo("Bedroom 3 TRV")
        assertEquals(before + 1, after.size)
        assertEquals(input(20.9), after.last())
    }

    // BDD: A valve added to a room with a sensor is switched to it
    @Test
    fun `a valve added to a room with a sensor is switched to it`() {
        val t = TestHeating()
        t.discover("Bedroom 3 TRV", "Bedroom 3 TRV 2", sensors = listOf("Landing Smoke"))
        val room = t.app.rooms.create("Bedroom 3")
        t.sensorState("Landing Smoke", """{"temperature":20.9}""")
        assertEquals(HttpStatusCode.OK, putSensor(t, room.id, "Landing Smoke"))
        t.api { client -> client.put("/api/rooms/${room.id}/valves/Bedroom 3%20TRV%202") }
        assertEquals(external(20.9), t.sentTo("Bedroom 3 TRV 2").last())
    }

    // BDD: Unassigned valves return to their internal sensor
    @Test
    fun `unassigned valves return to their internal sensor`() {
        val t = TestHeating()
        val id = bedroom3(t)
        t.api { client -> client.delete("/api/rooms/$id/valves/Bedroom 3%20TRV") }
        assertTrue(internal in t.sentTo("Bedroom 3 TRV"))
        assertEquals(internal, t.sentTo("Bedroom 3 TRV").last { "temperature_sensor_select" in it })
    }

    // BDD: Removing the sensor from a room returns it to its valves
    @Test
    fun `removing the sensor from a room returns it to its valves`() {
        val t = TestHeating()
        val id = bedroom3(t, reading = 20.9, valveReading = 23.0)
        var status = HttpStatusCode.OK
        t.api { client -> status = client.delete("/api/rooms/$id/sensor/Landing%20Smoke").status }
        assertEquals(HttpStatusCode.OK, status)
        assertEquals(internal, t.sentTo("Bedroom 3 TRV").last())
        val bedroom3 = room(t, "Bedroom 3")
        assertEquals(23.0, bedroom3.current)
        assertEquals("valves", bedroom3.temperatureSource)
        assertNull(bedroom3.sensor)
    }

    // BDD: A silent sensor falls back to the valves
    @Test
    fun `a silent sensor falls back to the valves`() {
        val t = TestHeating()
        bedroom3(t, reading = 20.9, valveReading = 23.0)
        // the valve keeps reporting; the sensor sends nothing for 60 minutes
        t.clock.advance(Duration.ofMinutes(30))
        t.valveState("Bedroom 3 TRV", """{"local_temperature":23.0}""")
        t.app.tick()
        t.clock.advance(Duration.ofMinutes(30))
        t.valveState("Bedroom 3 TRV", """{"local_temperature":23.0}""")
        t.app.tick()
        assertEquals(false, sensors(t).single { it.name == "Landing Smoke" }.online)
        assertEquals(internal, t.sentTo("Bedroom 3 TRV").last())
        val bedroom3 = room(t, "Bedroom 3")
        assertEquals(23.0, bedroom3.current)
        assertEquals("valves", bedroom3.temperatureSource)
        assertEquals(listOf("Landing Smoke is offline"), bedroom3.notices)
    }

    // BDD: An offline sensor falls back to the valves
    @Test
    fun `an offline sensor falls back to the valves`() {
        val t = TestHeating()
        bedroom3(t)
        t.bus.receive("zigbee2mqtt/Landing Smoke/availability", """{"state":"offline"}""")
        assertEquals(internal, t.sentTo("Bedroom 3 TRV").last())
        assertEquals("valves", room(t, "Bedroom 3").temperatureSource)
    }

    // BDD: A sensor that reports again takes over again
    @Test
    fun `a sensor that reports again takes over again`() {
        val t = TestHeating()
        bedroom3(t, reading = 20.9)
        t.clock.advance(Duration.ofMinutes(60))
        t.app.tick()
        assertEquals(internal, t.sentTo("Bedroom 3 TRV").last())
        t.sensorState("Landing Smoke", """{"temperature":20.5}""")
        assertEquals(external(20.5), t.sentTo("Bedroom 3 TRV").last())
        val bedroom3 = room(t, "Bedroom 3")
        assertEquals(20.5, bedroom3.current)
        assertEquals("sensor", bedroom3.temperatureSource)
        assertEquals(emptyList(), bedroom3.notices)
    }

    // BDD: Sensors that leave Zigbee2MQTT are dropped from their room
    @Test
    fun `sensors that leave zigbee2mqtt are dropped from their room`() {
        val t = TestHeating()
        bedroom3(t)
        t.discover("Bedroom 3 TRV")
        assertNull(room(t, "Bedroom 3").sensor)
        assertEquals(internal, t.sentTo("Bedroom 3 TRV").last())
        assertEquals(emptyList(), sensors(t))
    }

    // BDD: Room sensors survive a restart and are resent to valves
    @Test
    fun `room sensors survive a restart and are resent to valves`() {
        val t = TestHeating()
        bedroom3(t, reading = 20.9)
        t.restart()
        t.bus.clearPublished()
        t.sensorState("Landing Smoke", """{"temperature":20.9}""")
        assertEquals("Landing Smoke", room(t, "Bedroom 3").sensor)
        assertEquals(external(20.9), t.sentTo("Bedroom 3 TRV").last())
    }

    // BDD: Sensor temperature change is pushed to connected clients
    @Test
    fun `sensor temperature change is pushed to connected clients`() {
        val t = TestHeating()
        bedroom3(t)
        testApplication {
            application { heatingModule(t.app) }
            val client = createClient { install(WebSockets) }
            val ws = client.webSocketSession("/ws") { bearerAuth(TEST_TOKEN) }
            // The server may still be subscribing when the session opens, so report until it is heard.
            var event: JsonObject? = null
            repeat(10) {
                if (event != null) return@repeat
                t.sensorState("Landing Smoke", """{"temperature":21.2}""")
                event = withTimeoutOrNull(500) {
                    var found: JsonObject? = null
                    while (found == null) {
                        val frame = ws.incoming.receive()
                        if (frame !is Frame.Text) continue
                        val e = heatingJson.parseToJsonElement(frame.readText()).jsonObject
                        val room = e["room"] as? JsonObject
                        if (e["type"]!!.jsonPrimitive.content == "room.updated" &&
                            room!!["name"]!!.jsonPrimitive.content == "Bedroom 3" &&
                            room["current"]?.jsonPrimitive?.content == "21.2"
                        ) found = e
                    }
                    found
                }
            }
            assertEquals(21.2, event!!["room"]!!.jsonObject["current"]!!.jsonPrimitive.double)
        }
    }
}
