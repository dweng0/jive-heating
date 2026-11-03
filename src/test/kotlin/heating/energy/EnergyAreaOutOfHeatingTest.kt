package heating.energy

import heating.testing.TestHeating
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EnergyAreaOutOfHeatingTest {
    // BDD: An energy area stays out of heating
    @Test
    fun `an energy area is not a room, a zone or a place for a valve or sensor, and never asks for heat`() {
        val t = TestHeating()
        t.discover("Lounge TRV 1", sensors = listOf("Hall Smoke"))
        t.api { client ->
            client.post("/api/rooms") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Lounge"}""")
            }
            assertEquals(HttpStatusCode.Created, client.post("/api/energy/areas") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Garage/Office"}""")
            }.status)

            // Rooms screen, boiler views, status (which carries holiday and demand) and holiday on/off
            val holiday = client.put("/api/holiday") {
                contentType(ContentType.Application.Json)
                setBody("""{"until":"2026-10-20"}""")
            }.bodyAsText()
            for ((what, body) in listOf(
                "GET /api/rooms" to client.get("/api/rooms").bodyAsText(),
                "GET /api/boiler" to client.get("/api/boiler").bodyAsText(),
                "GET /api/status" to client.get("/api/status").bodyAsText(),
                "PUT /api/holiday" to holiday,
            )) {
                assertFalse("garage-office" in body || "Garage/Office" in body, "$what mentions the area: $body")
            }
            assert("Lounge" in client.get("/api/rooms").bodyAsText())

            // Not a zone: no schedule, no boost
            assertEquals(HttpStatusCode.NotFound, client.get("/api/zones/garage-office/schedule").status)
            assertEquals(HttpStatusCode.NotFound, client.put("/api/zones/garage-office/schedule/monday") {
                contentType(ContentType.Application.Json)
                setBody("""{"blocks":[{"start":"00:00","target":19.0}]}""")
            }.status)

            // Not a place for a valve or a sensor
            assertEquals(HttpStatusCode.NotFound, client.put("/api/rooms/garage-office/valves/Lounge TRV 1").status)
            assertEquals(HttpStatusCode.NotFound, client.put("/api/rooms/garage-office/sensor/Hall Smoke").status)
        }
        assertEquals(emptyList(), t.sentTo("Lounge TRV 1").map { it.toString() }.filter { "garage" in it.lowercase() })
    }
}
