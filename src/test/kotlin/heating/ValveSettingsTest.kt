package heating

import heating.api.ValveView
import heating.testing.TestHeating
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValveSettingsTest {
    private fun json(s: String) = Json.parseToJsonElement(s)

    // BDD: Toggle open window detection
    @Test
    fun `toggle open window detection`() {
        val t = TestHeating()
        t.discover("Lounge TRV 1")
        t.api { client ->
            val response = client.patch("/api/valves/Lounge%20TRV%201") {
                contentType(ContentType.Application.Json)
                setBody<String>("""{"openWindow":false}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
        assertEquals(json("""{"open_window":"OFF"}"""), t.sentTo("Lounge TRV 1").last())
    }

    // BDD: Toggle child lock
    @Test
    fun `toggle child lock`() {
        val t = TestHeating()
        t.discover("Lounge TRV 1")
        t.api { client ->
            client.patch("/api/valves/Lounge%20TRV%201") {
                contentType(ContentType.Application.Json)
                setBody<String>("""{"childLock":true}""")
            }
        }
        assertEquals(json("""{"child_lock":"LOCK"}"""), t.sentTo("Lounge TRV 1").last())
    }

    @Test
    fun `settings for an unknown valve are not found`() {
        val t = TestHeating()
        t.api { client ->
            val response = client.patch("/api/valves/Nope") {
                contentType(ContentType.Application.Json)
                setBody<String>("""{"childLock":true}""")
            }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    // BDD: Toggle smart temperature control per room
    @Test
    fun `toggle smart temperature control per room`() {
        val t = TestHeating()
        t.discover("A", "B")
        val room = t.app.rooms.create("Lounge")
        t.app.rooms.assignValve(room.id, "A")
        t.app.rooms.assignValve(room.id, "B")
        val before = t.sentTo("A").size
        t.api { client ->
            client.patch("/api/rooms/${room.id}") {
                contentType(ContentType.Application.Json)
                setBody<String>("""{"smartTemperatureControl":false}""")
            }
        }
        assertEquals(listOf(json("""{"smart_temperature_control":false}""")), t.sentTo("A").drop(before))
        assertEquals(json("""{"smart_temperature_control":false}"""), t.sentTo("B").last())
        assertEquals(false, t.app.rooms.get(room.id).smartTemperatureControl)
    }

    // BDD: Low battery is flagged
    @Test
    fun `low battery is flagged`() {
        val t = TestHeating()
        t.discover("Lounge TRV 1")
        t.valveState("Lounge TRV 1", """{"battery":15}""")
        t.api { client ->
            val valve = client.get("/api/valves").body<List<ValveView>>().single { it.name == "Lounge TRV 1" }
            assertTrue(valve.lowBattery)
            assertEquals(15, valve.battery)
        }
        t.valveState("Lounge TRV 1", """{"battery":21}""")
        t.api { client ->
            assertEquals(false, client.get("/api/valves").body<List<ValveView>>().single().lowBattery)
        }
    }
}
