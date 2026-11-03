package heating.energy

import heating.heatingJson
import heating.testing.MutableClock
import heating.testing.TestHeating
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Whole-area meters (clamps) and plugs in energy areas. */
class EnergyAreaMetersTest {
    private val start = Instant.parse("2026-10-11T23:00:00Z")

    private suspend fun HttpClient.addArea(name: String) = post("/api/energy/areas") {
        contentType(ContentType.Application.Json)
        setBody("""{"name":"$name"}""")
    }

    private suspend fun HttpClient.areas(): List<JsonObject> =
        heatingJson.parseToJsonElement(get("/api/energy/areas").bodyAsText()).jsonArray.map { it.jsonObject }

    private suspend fun HttpClient.meters(query: String = ""): List<JsonObject> =
        heatingJson.parseToJsonElement(get("/api/meters$query").bodyAsText()).jsonArray.map { it.jsonObject }

    private suspend fun HttpClient.measure(area: String, meter: String): HttpResponse = put("/api/energy/areas/$area/whole-meter/$meter")

    private fun JsonObject.text(key: String): String? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun List<JsonObject>.named(name: String) = single { it.text("name") == name }

    private fun List<JsonObject>.withId(id: String) = single { it.text("id") == id }

    private fun minutes(t: TestHeating, n: Long) {
        t.clock.advance(Duration.ofMinutes(n))
        t.app.tick()
    }

    /** "Garage Clamp" measuring "Garage/Office", and its samples so far. */
    private fun garageClamp(t: TestHeating) {
        t.discover(meters = listOf("Garage Clamp"))
        t.api { client ->
            client.addArea("Garage/Office")
            assertEquals(HttpStatusCode.OK, client.measure("garage-office", "Garage Clamp").status)
        }
    }

    private fun garageSamples(t: TestHeating) = t.app.energyHistory.areaSamples("garage-office", Instant.EPOCH, t.clock.instant())

    // BDD: A whole-area meter is recorded from its power
    @Test
    fun `a whole-area meter is recorded from its power`() {
        val t = TestHeating(clock = MutableClock(start))
        garageClamp(t)

        t.meterState("Garage Clamp", """{"energy":12.0,"power":1200}""")
        minutes(t, 5)
        val first = garageSamples(t).single()
        assertEquals(0.1, first.kwh!!, 1e-9)
        assertEquals(1200.0, first.watts!!, 1e-6)

        // Each power reading holds until the next; the total jumping up and down changes nothing.
        t.meterState("Garage Clamp", """{"energy":3.0,"power":600}""")
        t.clock.advance(Duration.ofSeconds(150))
        t.meterState("Garage Clamp", """{"energy":40.0,"power":1800}""")
        t.clock.advance(Duration.ofSeconds(150))
        t.app.tick()
        assertEquals(0.1, garageSamples(t).last().kwh!!, 1e-9)
        assertEquals(1200.0, garageSamples(t).last().watts!!, 1e-6)

        // Minutes with no reading are missing, never 0 kWh.
        minutes(t, 30)
        assertNull(garageSamples(t).last().kwh)
        assertNull(garageSamples(t).last().watts)
        t.meterState("Garage Clamp", """{"power":600}""")
        minutes(t, 5)
        assertEquals(0.05, garageSamples(t).last().kwh!!, 1e-9)
    }

    // BDD: A clamp measures a whole area
    @Test
    fun `a clamp measures a whole area`() {
        val t = TestHeating(clock = MutableClock(start))
        t.discover(
            meters = listOf("Main Clamp", "Garage Clamp", "Office Clamp", "Fridge Plug"),
            exposing = mapOf("Lamp Plug" to listOf("state"), "Lights Clamp" to listOf("power")),
        )
        t.api { client ->
            client.put("/api/meters/Main Clamp/house")
            client.put("/api/meters/Fridge Plug/monitored")
            client.addArea("Garage/Office")
            client.addArea("Lights")

            assertEquals(HttpStatusCode.OK, client.measure("garage-office", "Garage Clamp").status)
            assertEquals("Garage Clamp", client.areas().withId("garage-office").text("wholeMeter"))
            // It leaves "Waiting to be set up", and says which area it measures.
            assertEquals(listOf("Office Clamp"), client.meters("?unassigned=true").map { it.text("name") })
            assertEquals("garage-office", client.meters().named("Garage Clamp").text("area"))

            // Power without energy will do; neither will not.
            assertEquals(HttpStatusCode.OK, client.measure("lights", "Lights Clamp").status)
            assertEquals("Lights Clamp", client.areas().withId("lights").text("wholeMeter"))
            assertEquals(HttpStatusCode.UnprocessableEntity, client.measure("lights", "Lamp Plug").status)
            assertEquals(HttpStatusCode.NotFound, client.measure("lights", "Hall Clamp").status)
            assertEquals(HttpStatusCode.NotFound, client.measure("shed", "Office Clamp").status)

            // Not the house meter, not a monitored plug, and one area at most.
            assertEquals(HttpStatusCode.Conflict, client.measure("lights", "Main Clamp").status)
            assertEquals(HttpStatusCode.Conflict, client.measure("lights", "Fridge Plug").status)
            assertEquals(HttpStatusCode.Conflict, client.measure("lights", "Garage Clamp").status)
            assertEquals(HttpStatusCode.Conflict, client.put("/api/meters/Garage Clamp/monitored").status)
            assertEquals(HttpStatusCode.Conflict, client.put("/api/meters/Garage Clamp/house").status)
            assertEquals("Lights Clamp", client.areas().withId("lights").text("wholeMeter"))
        }

        // Each five-minute record keeps the area it was measuring when recorded.
        t.meterState("Garage Clamp", """{"energy":1.0,"power":1200}""")
        t.meterState("Office Clamp", """{"energy":1.0,"power":600}""")
        minutes(t, 5)
        t.api { client ->
            assertEquals(HttpStatusCode.OK, client.measure("garage-office", "Office Clamp").status)
            assertEquals("Office Clamp", client.areas().withId("garage-office").text("wholeMeter"))
            assertNull(client.meters().named("Garage Clamp").text("area"))
            assertEquals(listOf("Garage Clamp"), client.meters("?unassigned=true").map { it.text("name") })
        }
        minutes(t, 5)
        val samples = t.app.energyHistory.areaSamples("garage-office", start, t.clock.instant())
        assertEquals(listOf("Garage Clamp", "Office Clamp"), samples.map { it.meter })

        t.api { client ->
            assertEquals(HttpStatusCode.NoContent, client.delete("/api/energy/areas/garage-office/whole-meter").status)
            assertNull(client.areas().withId("garage-office").text("wholeMeter"))
            assertEquals(HttpStatusCode.NotFound, client.delete("/api/energy/areas/garage-office/whole-meter").status)
            val sam = memberSam()
            assertEquals(HttpStatusCode.Forbidden, send(sam, HttpMethod.Put, "/api/energy/areas/lights/whole-meter/Office%20Clamp").status)
        }
    }
}
