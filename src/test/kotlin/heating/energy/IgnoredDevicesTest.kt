package heating.energy

import heating.heatingJson
import heating.testing.TestHeating
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class IgnoredDevicesTest {

    private fun unassigned(t: TestHeating, kind: String): List<String> {
        var names = emptyList<String>()
        t.api { client ->
            val body = client.get("/api/$kind?unassigned=true").bodyAsText()
            names = heatingJson.parseToJsonElement(body).jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        }
        return names
    }

    private fun ignored(t: TestHeating, method: HttpMethod, device: String): HttpStatusCode {
        var status = HttpStatusCode.OK
        t.api { client -> status = client.request("/api/devices/${device.replace(" ", "%20")}/ignored") { this.method = method }.status }
        return status
    }

    private fun ignoredList(t: TestHeating): List<String> {
        var names = emptyList<String>()
        t.api { client ->
            names = heatingJson.parseToJsonElement(client.get("/api/devices/ignored").bodyAsText()).jsonArray.map { it.jsonPrimitive.content }
        }
        return names
    }

    // BDD: Ignored devices can be brought back
    @Test
    fun `ignored devices can be brought back`() {
        val t = TestHeating()
        t.discover("Spare TRV", meters = listOf("Garage Plug", "Main Clamp"))
        ignored(t, HttpMethod.Put, "Garage Plug")
        ignored(t, HttpMethod.Put, "Spare TRV")
        assertEquals(listOf("Garage Plug", "Spare TRV"), ignoredList(t))

        assertEquals(HttpStatusCode.NoContent, ignored(t, HttpMethod.Delete, "Garage Plug"))
        assertEquals(listOf("Spare TRV"), ignoredList(t))
        assertEquals(listOf("Garage Plug", "Main Clamp"), unassigned(t, "meters"))
        assertEquals(HttpStatusCode.NotFound, ignored(t, HttpMethod.Delete, "Garage Plug"))

        // A device that leaves Zigbee2MQTT is taken off the list, so it comes back as new if it is paired again.
        t.discover(meters = listOf("Main Clamp"))
        assertEquals(emptyList(), ignoredList(t))
        t.discover("Spare TRV", meters = listOf("Garage Plug", "Main Clamp"))
        assertEquals(listOf("Spare TRV"), unassigned(t, "valves"))

        t.api {
            val sam = memberSam()
            assertEquals(HttpStatusCode.Forbidden, send(sam, HttpMethod.Get, "/api/devices/ignored").status)
            assertEquals(HttpStatusCode.Forbidden, send(sam, HttpMethod.Delete, "/api/devices/Garage%20Plug/ignored").status)
        }
    }

    // BDD: An admin ignores a device that needs no setting up
    @Test
    fun `an admin ignores a device that needs no setting up`() {
        val t = TestHeating()
        t.discover("Spare TRV", sensors = listOf("Hall Smoke"), meters = listOf("Garage Plug", "Main Clamp"))
        assertEquals(listOf("Garage Plug", "Main Clamp"), unassigned(t, "meters"))

        assertEquals(HttpStatusCode.NoContent, ignored(t, HttpMethod.Put, "Garage Plug"))
        assertEquals(HttpStatusCode.NoContent, ignored(t, HttpMethod.Put, "Spare TRV"))
        assertEquals(HttpStatusCode.NoContent, ignored(t, HttpMethod.Put, "Hall Smoke"))
        assertEquals(HttpStatusCode.NotFound, ignored(t, HttpMethod.Put, "Nothing Here"))
        assertEquals(listOf("Main Clamp"), unassigned(t, "meters"))
        assertEquals(emptyList(), unassigned(t, "valves"))
        assertEquals(emptyList(), unassigned(t, "sensors"))

        // Still ignored after a restart.
        t.restart()
        assertEquals(listOf("Main Clamp"), unassigned(t, "meters"))

        t.api {
            val sam = memberSam()
            assertEquals(HttpStatusCode.Forbidden, send(sam, HttpMethod.Put, "/api/devices/Main%20Clamp/ignored").status)
        }
        assertEquals(listOf("Main Clamp"), unassigned(t, "meters"))
    }
}
