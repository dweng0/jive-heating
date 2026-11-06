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
import kotlin.test.assertTrue

/** Labels and jobs for meters, as the energy management page sets them. */
class MeterManagementTest {
    private val start = Instant.parse("2026-11-05T08:00:00Z")
    private val clamps = listOf("power_a", "energy_a", "power_b", "energy_b")

    private suspend fun HttpClient.meters(): List<JsonObject> =
        heatingJson.parseToJsonElement(get("/api/meters").bodyAsText()).jsonArray.map { it.jsonObject }

    private suspend fun HttpClient.settings(): JsonObject =
        heatingJson.parseToJsonElement(get("/api/settings/energy").bodyAsText()).jsonObject

    private suspend fun HttpClient.label(meter: String, label: String): HttpResponse = put("/api/meters/$meter/label") {
        contentType(ContentType.Application.Json)
        setBody("""{"label":"$label"}""")
    }

    private suspend fun HttpClient.job(meter: String, body: String): HttpResponse = put("/api/meters/$meter/job") {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun JsonObject.text(key: String): String? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun List<JsonObject>.named(name: String) = single { it.text("name") == name }

    private fun minutes(t: TestHeating, n: Long) {
        t.clock.advance(Duration.ofMinutes(n))
        t.app.tick()
    }

    // BDD: An admin names a meter
    @Test
    fun `an admin names a meter`() {
        val t = TestHeating(clock = MutableClock(start))
        t.discover(exposing = mapOf("Mains and lights" to clamps))
        t.api { client ->
            assertEquals("Mains and lights A", client.meters().named("Mains and lights A").text("label"))
            assertEquals(HttpStatusCode.OK, client.label("Mains and lights A", "Mains").status)
            assertEquals("Mains", client.meters().named("Mains and lights A").text("label"))
            assertEquals(HttpStatusCode.BadRequest, client.label("Mains and lights A", "x".repeat(41)).status)
            assertEquals(HttpStatusCode.NotFound, client.label("Nothing", "Oven").status)
        }
        t.restart()
        t.discover(exposing = mapOf("Mains and lights" to clamps))
        t.api { client ->
            assertEquals("Mains", client.meters().named("Mains and lights A").text("label"))
            // Empty goes back to the Zigbee2MQTT name.
            assertEquals(HttpStatusCode.OK, client.label("Mains and lights A", "  ").status)
            assertEquals("Mains and lights A", client.meters().named("Mains and lights A").text("label"))
            assertEquals(HttpStatusCode.OK, client.label("Mains and lights B", "Lights").status)
        }
        // A meter that leaves Zigbee2MQTT loses its label.
        t.discover(meters = listOf("Fridge Plug"))
        t.discover(exposing = mapOf("Mains and lights" to clamps))
        t.api { client ->
            assertEquals("Mains and lights B", client.meters().named("Mains and lights B").text("label"))
            val sam = memberSam()
            assertEquals(HttpStatusCode.Forbidden, send(sam, HttpMethod.Put, "/api/meters/Mains%20and%20lights%20A/label", """{"label":"x"}""").status)
        }
    }

    // BDD: A meter is given a new job in one step
    @Test
    fun `a meter is given a new job in one step`() {
        val t = TestHeating(clock = MutableClock(start))
        t.discover(meters = listOf("CCU Clamp"), exposing = mapOf("Lamp" to listOf("power")))
        t.api { client ->
            val kitchen = heatingJson.parseToJsonElement(client.post("/api/rooms") {
                contentType(ContentType.Application.Json); setBody("""{"name":"Kitchen"}""")
            }.bodyAsText()).jsonObject.text("id")!!
            client.post("/api/energy/areas") { contentType(ContentType.Application.Json); setBody("""{"name":"Lights"}""") }

            assertEquals(HttpStatusCode.OK, client.job("CCU Clamp", """{"job":"house"}""").status)
            assertEquals("CCU Clamp", client.settings().text("electricityMeter"))

            assertEquals(HttpStatusCode.OK, client.job("CCU Clamp", """{"job":"monitored","room":"$kitchen"}""").status)
            assertNull(client.settings().text("electricityMeter"))
            client.meters().named("CCU Clamp").let {
                assertEquals("true", it.text("monitored"))
                assertEquals(kitchen, it.text("room"))
            }

            assertEquals(HttpStatusCode.OK, client.job("CCU Clamp", """{"job":"area","area":"lights"}""").status)
            client.meters().named("CCU Clamp").let {
                assertEquals("false", it.text("monitored"))
                assertEquals("lights", it.text("measures"))
            }

            assertEquals(HttpStatusCode.OK, client.job("CCU Clamp", """{"job":"monitored","area":"lights"}""").status)
            client.meters().named("CCU Clamp").let {
                assertNull(it.text("measures"))
                assertEquals("lights", it.text("area"))
            }

            assertEquals(HttpStatusCode.OK, client.job("CCU Clamp", """{"job":"none"}""").status)
            client.meters().named("CCU Clamp").let {
                assertEquals("false", it.text("monitored"))
                assertNull(it.text("area"))
            }

            // A job it cannot do changes nothing: a meter with no energy total cannot be the house meter or monitored.
            assertEquals(HttpStatusCode.OK, client.job("Lamp", """{"job":"area","area":"lights"}""").status)
            assertEquals(HttpStatusCode.UnprocessableEntity, client.job("Lamp", """{"job":"house"}""").status)
            assertEquals(HttpStatusCode.UnprocessableEntity, client.job("Lamp", """{"job":"monitored"}""").status)
            assertEquals("lights", client.meters().named("Lamp").text("measures"))
            assertEquals(HttpStatusCode.BadRequest, client.job("CCU Clamp", """{"job":"oven"}""").status)
            assertEquals(HttpStatusCode.NotFound, client.job("CCU Clamp", """{"job":"monitored","room":"attic"}""").status)
            assertEquals(HttpStatusCode.NotFound, client.job("CCU Clamp", """{"job":"area","area":"attic"}""").status)
            assertEquals(HttpStatusCode.NotFound, client.job("Nothing", """{"job":"none"}""").status)

            val sam = memberSam()
            assertEquals(HttpStatusCode.Forbidden, send(sam, HttpMethod.Put, "/api/meters/CCU%20Clamp/job", """{"job":"none"}""").status)
        }
    }

    // BDD: A meter's new job does not rewrite what it recorded
    @Test
    fun `a meter's new job does not rewrite what it recorded`() {
        val t = TestHeating(clock = MutableClock(start))
        t.discover(meters = listOf("CCU Clamp"), exposing = mapOf("Mains and lights" to clamps))
        var kitchen = ""
        t.api { client ->
            kitchen = heatingJson.parseToJsonElement(client.post("/api/rooms") {
                contentType(ContentType.Application.Json); setBody("""{"name":"Kitchen"}""")
            }.bodyAsText()).jsonObject.text("id")!!
            assertEquals(HttpStatusCode.OK, client.job("CCU Clamp", """{"job":"house"}""").status)
        }
        // 60 kW for five minutes is 5.0 kWh as the house meter.
        t.meterState("CCU Clamp", """{"energy":10.0,"power":60000}""")
        minutes(t, 5)
        t.api { client ->
            assertEquals(HttpStatusCode.OK, client.job("Mains and lights A", """{"job":"house"}""").status)
            assertEquals(HttpStatusCode.OK, client.job("CCU Clamp", """{"job":"monitored","room":"$kitchen"}""").status)
        }
        t.meterState("Mains and lights", """{"power_a":0,"energy_a":0}""")
        t.meterState("CCU Clamp", """{"energy":15.0,"power":1200}""")
        minutes(t, 5)
        t.meterState("CCU Clamp", """{"energy":15.1,"power":1200}""")
        minutes(t, 5)
        t.api { client ->
            val body = heatingJson.parseToJsonElement(client.get("/api/dashboard?period=today").bodyAsText()).jsonObject
            assertEquals(5.0, body["electricityKwh"]!!.jsonPrimitive.content.toDouble(), 0.01)
            val room = body["rooms"]!!.jsonArray.map { it.jsonObject }.single { it.text("room") == kitchen }
            assertTrue(room["kwh"]!!.jsonPrimitive.content.toDouble() < 1.0)
        }
    }

    // BDD: An admin stops monitoring a device
    @Test
    fun `an admin stops monitoring a device`() {
        val t = TestHeating(clock = MutableClock(start))
        t.discover(meters = listOf("Desk Plug"))
        t.api { client ->
            client.post("/api/energy/areas") { contentType(ContentType.Application.Json); setBody("""{"name":"Garage/Office"}""") }
            assertEquals(HttpStatusCode.OK, client.job("Desk Plug", """{"job":"monitored","area":"garage-office"}""").status)
        }
        t.meterState("Desk Plug", """{"energy":1.0,"power":100}""")
        minutes(t, 5)
        t.meterState("Desk Plug", """{"energy":1.2,"power":100}""")
        minutes(t, 5)
        t.api { client ->
            assertEquals(HttpStatusCode.NoContent, client.delete("/api/meters/Desk Plug/monitored").status)
            val unassigned = heatingJson.parseToJsonElement(client.get("/api/meters?unassigned=true").bodyAsText()).jsonArray
            assertEquals(listOf("Desk Plug"), unassigned.map { it.jsonObject.text("name") })
            val area = heatingJson.parseToJsonElement(client.get("/api/dashboard?period=today").bodyAsText()).jsonObject["areas"]!!
                .jsonArray.single().jsonObject
            assertTrue(area["kwh"]!!.jsonPrimitive.content.toDouble() > 0.0)
        }
    }
}
