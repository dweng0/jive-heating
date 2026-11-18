package heating.energy

import heating.heatingJson
import heating.testing.MutableClock
import heating.testing.TestHeating
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
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

/** A correction factor for a meter whose clamp reads too low or too high. */
class MeterScaleTest {
    private val start = Instant.parse("2026-11-05T08:00:00Z")
    private val clamps = listOf("power_a", "energy_a", "power_b", "energy_b")

    private suspend fun HttpClient.meters(): List<JsonObject> =
        heatingJson.parseToJsonElement(get("/api/meters").bodyAsText()).jsonArray.map { it.jsonObject }

    private suspend fun HttpClient.scale(meter: String, body: String): HttpResponse = put("/api/meters/$meter/scale") {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun JsonObject.text(key: String): String? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun List<JsonObject>.named(name: String) = single { it.text("name") == name }

    private fun minutes(t: TestHeating, n: Long) {
        t.clock.advance(Duration.ofMinutes(n))
        t.app.tick()
    }

    // BDD: An admin corrects a meter that reads too low or too high
    @Test
    fun `an admin corrects a meter that reads too low or too high`() {
        val t = TestHeating(clock = MutableClock(start))
        t.discover(exposing = mapOf("Oven and shower" to clamps))
        t.api { client ->
            val raw = client.meters().named("Oven and shower A")
            assertEquals(1.0, raw.text("scale")!!.toDouble())
            assertEquals(HttpStatusCode.OK, client.scale("Oven and shower A", """{"factor":3}""").status)
        }
        t.meterState("Oven and shower", """{"power_a":540,"energy_a":1.0}""")
        t.api { client ->
            val meter = client.meters().named("Oven and shower A")
            assertEquals(3.0, meter.text("scale")!!.toDouble())
            assertEquals("1620.0", meter.text("power"))
            assertEquals("3.0", meter.text("energy"))
            assertEquals("540.0", meter.text("rawPower"))
        }
        assertEquals(1620.0, t.app.meters["Oven and shower A"]!!.power!!, 1e-9)

        // Kept after a restart.
        t.restart()
        t.discover(exposing = mapOf("Oven and shower" to clamps))
        t.meterState("Oven and shower", """{"power_a":100,"energy_a":1.0}""")
        t.api { client ->
            assertEquals(3.0, client.meters().named("Oven and shower A").text("scale")!!.toDouble())
            assertEquals("300.0", client.meters().named("Oven and shower A").text("power"))

            // 1 or DELETE goes back to the raw reading.
            assertEquals(HttpStatusCode.OK, client.scale("Oven and shower A", """{"factor":1}""").status)
            assertEquals(1.0, client.meters().named("Oven and shower A").text("scale")!!.toDouble())
            assertEquals(HttpStatusCode.OK, client.scale("Oven and shower A", """{"factor":2.5}""").status)
            assertEquals(2.5, client.meters().named("Oven and shower A").text("scale")!!.toDouble())
            assertEquals(HttpStatusCode.NoContent, client.delete("/api/meters/Oven and shower A/scale").status)
            assertEquals(1.0, client.meters().named("Oven and shower A").text("scale")!!.toDouble())
            assertEquals(HttpStatusCode.OK, client.scale("Oven and shower A", """{"factor":3}""").status)

            // Nothing sensible changes nothing.
            for (bad in listOf("""{"factor":0}""", """{"factor":-2}""", """{"factor":10.5}""", """{"factor":"lots"}""", """{}""")) {
                assertEquals(HttpStatusCode.BadRequest, client.scale("Oven and shower A", bad).status, bad)
            }
            assertEquals(3.0, client.meters().named("Oven and shower A").text("scale")!!.toDouble())
            assertEquals(HttpStatusCode.NotFound, client.scale("Nothing", """{"factor":3}""").status)

            val sam = memberSam()
            assertEquals(HttpStatusCode.Forbidden, send(sam, HttpMethod.Put, "/api/meters/Oven%20and%20shower%20A/scale", """{"factor":2}""").status)
            assertEquals(HttpStatusCode.Forbidden, send(sam, HttpMethod.Delete, "/api/meters/Oven%20and%20shower%20A/scale", "").status)
        }

        // Dropped when the meter leaves Zigbee2MQTT.
        t.discover(meters = listOf("Fridge Plug"))
        t.discover(exposing = mapOf("Oven and shower" to clamps))
        t.api { client -> assertEquals(1.0, client.meters().named("Oven and shower A").text("scale")!!.toDouble()) }
    }

    // BDD: A corrected reading flows into use, cost and "Everything else"
    @Test
    fun `a corrected reading flows into use, cost and everything else`() {
        val t = TestHeating(clock = MutableClock(start))
        t.discover(exposing = mapOf("Mains and lights" to clamps, "Oven and shower" to clamps))
        t.api { client ->
            assertEquals(HttpStatusCode.OK, client.put("/api/meters/Mains and lights A/house").status)
            assertEquals(HttpStatusCode.OK, client.put("/api/meters/Oven and shower A/monitored").status)
            assertEquals(HttpStatusCode.OK, client.scale("Oven and shower A", """{"factor":3}""").status)
        }
        t.meterState("Mains and lights", """{"power_a":5200,"energy_a":10.0}""")
        t.meterState("Oven and shower", """{"power_a":1700,"energy_a":2.0}""")
        minutes(t, 5)
        t.meterState("Mains and lights", """{"power_a":5200,"energy_a":10.4}""")
        // 1700 W for five minutes is 0.1417 kWh on the clamp, 0.425 kWh corrected.
        t.meterState("Oven and shower", """{"power_a":1700,"energy_a":2.1417}""")
        minutes(t, 5)

        t.meterState("Mains and lights", """{"power_a":5200,"energy_a":10.4}""")
        t.meterState("Oven and shower", """{"power_a":1700,"energy_a":2.1417}""")
        val live = t.app.livePower.now()
        assertEquals(100.0, live.elseWatts!!, 1e-6)
        t.api { client ->
            val dashboard = heatingJson.parseToJsonElement(client.get("/api/dashboard?period=today").bodyAsText()).jsonObject
            // Two samples of 5200 W for five minutes, 0.4333 kWh each. The clamp's first sample has nothing before it to count from.
            assertEquals(0.8667, dashboard["electricityKwh"]!!.jsonPrimitive.content.toDouble(), 0.01)
            val device = dashboard["devices"]!!.jsonArray.map { it.jsonObject }.single { it.text("meter") == "Oven and shower A" }
            assertEquals(0.425, device["kwh"]!!.jsonPrimitive.content.toDouble(), 0.01)
            assertEquals(1700.0, client.meters().named("Oven and shower A").text("rawPower")!!.toDouble(), 1e-6)
        }

        // Changing the factor does not rewrite what was recorded, and does not make the next sample count a jump.
        t.api { client -> assertEquals(HttpStatusCode.OK, client.scale("Oven and shower A", """{"factor":2}""").status) }
        t.meterState("Oven and shower", """{"power_a":1700,"energy_a":2.2834}""")
        minutes(t, 5)
        t.api { client ->
            val dashboard = heatingJson.parseToJsonElement(client.get("/api/dashboard?period=today").bodyAsText()).jsonObject
            val device = dashboard["devices"]!!.jsonArray.map { it.jsonObject }.single { it.text("meter") == "Oven and shower A" }
            // 0.425 before; then 0.1417 x 2 = 0.2834 at the new factor.
            assertEquals(0.425 + 0.2834, device["kwh"]!!.jsonPrimitive.content.toDouble(), 0.01)
            assertTrue(device["kwh"]!!.jsonPrimitive.content.toDouble() < 1.0)
        }
    }

    @Test
    fun `a meter with no scale set is not corrected`() {
        val t = TestHeating(clock = MutableClock(start))
        t.discover(meters = listOf("Fridge Plug"))
        t.meterState("Fridge Plug", """{"energy":1.0,"power":50}""")
        t.api { client ->
            val meter = client.meters().named("Fridge Plug")
            assertEquals("50.0", meter.text("power"))
            assertEquals(1.0, meter.text("scale")!!.toDouble())
            assertNull(meter.text("nothing"))
        }
    }
}
