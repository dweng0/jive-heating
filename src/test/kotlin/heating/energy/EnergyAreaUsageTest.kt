package heating.energy

import heating.heatingJson
import heating.testing.MutableClock
import heating.testing.TestHeating
import io.ktor.client.request.delete
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An energy area's use over time, for its energy dashboard. */
class EnergyAreaUsageTest {
    // 14:00 in London (BST).
    private val two = Instant.parse("2026-10-12T13:00:00Z")
    private val three = two.plus(Duration.ofHours(1))

    private fun at(minutes: Long) = two.plus(Duration.ofMinutes(minutes))

    /** House meter "Main Clamp", "Desk Plug" in "Garage/Office", which "Garage Clamp" measures. 25p per kWh. */
    private fun garage(clock: Instant = three): TestHeating {
        val t = TestHeating(clock = MutableClock(clock))
        t.discover(meters = listOf("Main Clamp", "Desk Plug", "Garage Clamp"))
        t.api { client ->
            client.put("/api/meters/Main Clamp/house")
            client.put("/api/settings/energy") {
                contentType(ContentType.Application.Json)
                setBody("""{"electricityPencePerKwh":25.0}""")
            }
            client.put("/api/meters/Desk Plug/monitored")
            client.post("/api/energy/areas") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Garage/Office"}""")
            }
            assertEquals(HttpStatusCode.OK, client.put("/api/energy/areas/garage-office/whole-meter/Garage Clamp").status)
            assertEquals(HttpStatusCode.OK, client.put("/api/energy/areas/garage-office/meters/Desk Plug").status)
        }
        return t
    }

    private fun get(t: TestHeating, path: String): JsonObject {
        var result: JsonObject? = null
        t.api { client ->
            val response = client.get(path)
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            result = heatingJson.parseToJsonElement(response.bodyAsText()).jsonObject
        }
        return result!!
    }

    private fun JsonObject.list(key: String) = this[key]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.kwh() = this["kwh"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.double

    private fun JsonObject.incomplete() = this["incomplete"]!!.jsonPrimitive.boolean

    private fun JsonObject.time() = Instant.parse(this["at"]!!.jsonPrimitive.content)

    // BDD: A clamp with no reading leaves the area incomplete
    @Test
    fun `a clamp with no reading leaves the area incomplete`() {
        val t = garage()
        // The clamp reads 0.1 kWh a five minutes until 14:20, then nothing until 15:00; the plug keeps reporting.
        (1..12L).forEach { i ->
            val time = at(5 * i)
            t.app.energyHistory.add(MeterSample(time, "Main Clamp", 1.0, 0.5, watts = 6000.0))
            t.app.energyHistory.addDevice(MeterSample(time, "Desk Plug", 1.0, 0.02, watts = 240.0, area = "garage-office"))
            val clamped = time <= at(20)
            t.app.energyHistory.addArea(
                MeterSample(time, "Garage Clamp", if (clamped) 1.0 else null, if (clamped) 0.1 else null, "garage-office", if (clamped) 1200.0 else null),
            )
        }

        val usage = get(t, "/api/energy/areas/garage-office/usage?window=24h&to=$three")
        val (before, gap) = usage.list("room").partition { it.time() <= at(20) }
        assertEquals(4, before.size)
        assertTrue(before.all { it.kwh() == 0.1 && !it.incomplete() }, "$before")
        assertEquals(8, gap.size)
        // Only what the clamp recorded: nothing, never the plug's figures instead.
        assertTrue(gap.all { it.incomplete() && it.kwh() == null }, "$gap")
        // The plug's own chart keeps its readings.
        val desk = usage.list("plugs").single()
        assertEquals("Desk Plug", desk["meter"]!!.jsonPrimitive.content)
        assertEquals(12, desk.list("points").count { it.kwh() == 0.02 })
        assertEquals("GBP", usage["currency"]!!.jsonPrimitive.content)
        assertEquals(at(5), Instant.parse(usage["earliest"]!!.jsonPrimitive.content))

        // "Everything else" is marked incomplete for that time.
        val rest = get(t, "/api/energy/else/usage?window=24h&to=$three").list("room")
        assertTrue(rest.filter { it.time() > at(20) }.all { it.incomplete() })
        assertTrue(rest.filter { it.time() <= at(20) }.none { it.incomplete() })

        // "Now" says no reading, never the clamp's last power.
        val live = garage(clock = two)
        live.meterState("Garage Clamp", """{"energy":1.0,"power":1200}""")
        live.meterState("Desk Plug", """{"energy":1.0,"power":240}""")
        repeat(8) {
            live.clock.advance(Duration.ofMinutes(5))
            live.meterState("Desk Plug", """{"energy":1.0,"power":240}""")
        }
        assertNull(live.app.livePower.now().areas.single().watts)
    }

    @Test
    fun `an area with no clamp is its plugs over time, and an unknown area is not found`() {
        val t = garage()
        t.api { client -> client.delete("/api/energy/areas/garage-office/whole-meter") }
        (1..12L).forEach { i -> t.app.energyHistory.addDevice(MeterSample(at(5 * i), "Desk Plug", 1.0, 0.02, watts = 240.0, area = "garage-office")) }
        val points = get(t, "/api/energy/areas/garage-office/usage?window=7d&to=$three").list("room")
        assertEquals(0.24, points.sumOf { it.kwh() ?: 0.0 }, 1e-9)
        t.api { client -> assertEquals(HttpStatusCode.NotFound, client.get("/api/energy/areas/shed/usage").status) }
    }
}
