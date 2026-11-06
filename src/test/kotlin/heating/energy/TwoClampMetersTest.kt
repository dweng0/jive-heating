package heating.energy

import heating.heatingJson
import heating.testing.MutableClock
import heating.testing.TestHeating
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
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

/** A Zigbee meter with two clamps, A and B, each a meter of its own. */
class TwoClampMetersTest {
    private val start = Instant.parse("2026-11-05T23:00:00Z")
    private val clamps = listOf("power_a", "energy_a", "power_b", "energy_b", "power_ab", "voltage")

    private suspend fun HttpClient.meters(): List<JsonObject> =
        heatingJson.parseToJsonElement(get("/api/meters").bodyAsText()).jsonArray.map { it.jsonObject }

    private fun JsonObject.text(key: String): String? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun List<JsonObject>.named(name: String) = single { it.text("name") == name }

    private fun minutes(t: TestHeating, n: Long) {
        t.clock.advance(Duration.ofMinutes(n))
        t.app.tick()
    }

    /** Clamp A the house meter, clamp B measuring "Lights". */
    private fun mainsAndLights(t: TestHeating) {
        t.discover(exposing = mapOf("Mains and lights" to clamps))
        t.api { client ->
            assertEquals(HttpStatusCode.OK, client.put("/api/meters/Mains and lights A/house").status)
            client.post("/api/energy/areas") { contentType(ContentType.Application.Json); setBody("""{"name":"Lights"}""") }
            assertEquals(HttpStatusCode.OK, client.put("/api/energy/areas/lights/whole-meter/Mains and lights B").status)
        }
    }

    // BDD: A two-clamp meter is discovered as two meters
    @Test
    fun `a two-clamp meter is discovered as two meters`() {
        val t = TestHeating(clock = MutableClock(start))
        t.discover(meters = listOf("Fridge Plug"), exposing = mapOf("Mains and lights" to clamps))
        t.api { client ->
            val meters = client.meters()
            assertEquals(listOf("Fridge Plug", "Mains and lights A", "Mains and lights B"), meters.map { it.text("name") }.sortedBy { it })
            assertEquals("Mains and lights", meters.named("Mains and lights A").text("device"))
            assertEquals("A", meters.named("Mains and lights A").text("clamp"))
            assertEquals("Mains and lights", meters.named("Mains and lights B").text("device"))
            assertEquals("B", meters.named("Mains and lights B").text("clamp"))
            assertEquals("Fridge Plug", meters.named("Fridge Plug").text("device"))
            assertNull(meters.named("Fridge Plug").text("clamp"))
        }
    }

    // BDD: Each clamp's readings come from the device's state
    @Test
    fun `each clamp's readings come from the device's state`() {
        val t = TestHeating(clock = MutableClock(start))
        t.discover(exposing = mapOf("Mains and lights" to clamps))
        t.meterState("Mains and lights", """{"power_a":535.2,"energy_a":0.04,"power_b":25.5,"energy_b":0,"power_ab":509.7}""")
        t.api { client ->
            val meters = client.meters()
            assertEquals("535.2", meters.named("Mains and lights A").text("power"))
            assertEquals("0.04", meters.named("Mains and lights A").text("energy"))
            assertEquals("25.5", meters.named("Mains and lights B").text("power"))
            assertEquals("0.0", meters.named("Mains and lights B").text("energy"))
        }
        // Only clamp A's readings: clamp B's stay as they were.
        t.meterState("Mains and lights", """{"power_a":700.0,"energy_a":0.05}""")
        t.api { client ->
            val meters = client.meters()
            assertEquals("700.0", meters.named("Mains and lights A").text("power"))
            assertEquals("25.5", meters.named("Mains and lights B").text("power"))
        }
    }

    // BDD: A clamp fitted the wrong way round is still counted as use
    @Test
    fun `a clamp fitted the wrong way round is still counted as use`() {
        for (state in listOf("""{"power_b":25.5,"energy_flow_b":"producing"}""", """{"power_b":-25.5}""")) {
            val t = TestHeating(clock = MutableClock(start))
            mainsAndLights(t)
            t.meterState("Mains and lights", state)
            minutes(t, 5)
            val sample = t.app.energyHistory.areaSamples("lights", Instant.EPOCH, t.clock.instant()).single()
            assertEquals(25.5 * 5 / 60 / 1000, sample.kwh!!, 1e-9)
            assertEquals(25.5, sample.watts!!, 1e-6)
            t.api { client ->
                val b = client.meters().named("Mains and lights B")
                assertEquals("true", b.text("reversed"))
                assertEquals("25.5", b.text("power"))
                assertEquals("false", client.meters().named("Mains and lights A").text("reversed"))
            }
        }
    }

    // BDD: Each clamp does its own job
    @Test
    fun `each clamp does its own job`() {
        val t = TestHeating(clock = MutableClock(start))
        mainsAndLights(t)
        t.meterState("Mains and lights", """{"power_a":600,"energy_a":1.0,"power_b":60,"energy_b":0.1}""")
        minutes(t, 5)
        val house = t.app.energyHistory.samples(Instant.EPOCH, t.clock.instant()).single()
        assertEquals(0.05, house.kwh!!, 1e-9)
        val lights = t.app.energyHistory.areaSamples("lights", Instant.EPOCH, t.clock.instant()).single()
        assertEquals(0.005, lights.kwh!!, 1e-9)
        t.api { client ->
            val body = heatingJson.parseToJsonElement(client.get("/api/dashboard?period=today").bodyAsText()).jsonObject
            assertEquals(0.05, body["electricityKwh"]!!.jsonPrimitive.content.toDouble(), 1e-9)
            val rest = body["everythingElse"]!!.jsonObject
            // The dashboard rounds to 0.01 kWh.
            assertEquals(0.045, rest["kwh"]!!.jsonPrimitive.content.toDouble(), 0.0051)
        }
    }

    // BDD: Both clamps go offline or leave together
    @Test
    fun `both clamps go offline or leave together`() {
        val t = TestHeating(clock = MutableClock(start))
        mainsAndLights(t)
        t.meterState("Mains and lights", """{"power_a":600,"power_b":60}""")
        t.availability("Mains and lights", false)
        t.api { client ->
            val meters = client.meters()
            assertEquals("false", meters.named("Mains and lights A").text("online"))
            assertEquals("false", meters.named("Mains and lights B").text("online"))
        }
        minutes(t, 5)
        assertNull(t.app.energyHistory.samples(Instant.EPOCH, t.clock.instant()).lastOrNull()?.kwh)

        t.discover(meters = listOf("Fridge Plug"))
        t.api { client ->
            val settings = heatingJson.parseToJsonElement(client.get("/api/settings/energy").bodyAsText()).jsonObject
            assertTrue(settings["electricityMeter"] is JsonNull)
            assertEquals(listOf("Mains and lights was removed from Zigbee2MQTT"), settings["notices"]!!.jsonArray.map { it.jsonPrimitive.content })
            val lights = heatingJson.parseToJsonElement(client.get("/api/energy/areas").bodyAsText()).jsonArray.single().jsonObject
            assertTrue(lights["wholeMeter"] == null || lights["wholeMeter"] is JsonNull)
        }
    }
}
