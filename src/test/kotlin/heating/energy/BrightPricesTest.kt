package heating.energy

import heating.gas.BrightTariff
import heating.gas.GasMeter
import heating.heatingJson
import heating.testing.MutableClock
import heating.testing.TestHeating
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A Bright account whose tariff the test sets; [fails] makes every fetch fail. */
class FakeBright(var tariff: BrightTariff?) : GasMeter {
    var fails = false
    var asked = 0
    override fun reported(): ClosedRange<Instant>? = null
    override fun readings(from: Instant, to: Instant): Map<Instant, Double> = emptyMap()
    override fun tariff(): BrightTariff? {
        asked++
        if (fails) throw java.io.IOException("Glowmarkt unreachable")
        return tariff
    }
}

class BrightPricesTest {
    private val bright = BrightTariff(gasPencePerKwh = 6.906, gasStandingPencePerDay = 27.324, electricityPencePerKwh = 24.04, electricityStandingPencePerDay = 41.19)

    private fun settings(t: TestHeating): JsonObject {
        var result: JsonObject? = null
        t.api { client -> result = heatingJson.parseToJsonElement(client.get("/api/settings/energy").bodyAsText()).jsonObject }
        return result!!
    }

    private fun put(t: TestHeating, body: String): HttpStatusCode {
        var status = HttpStatusCode.OK
        t.api { client ->
            status = client.put("/api/settings/energy") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }.status
        }
        return status
    }

    private fun JsonObject.number(key: String) = this[key]!!.jsonPrimitive.double

    // BDD: Prices can follow the Bright tariff
    @Test
    fun `prices can follow the Bright tariff`() {
        val meter = FakeBright(bright)
        val t = TestHeating(clock = MutableClock(Instant.parse("2026-11-11T19:00:00Z")), gasMeter = meter)
        assertTrue(settings(t)["brightAvailable"]!!.jsonPrimitive.boolean)
        assertEquals(HttpStatusCode.OK, put(t, """{"dailyKwhTarget":16.0,"boilerAverageKw":10.1}"""))

        // Turned on: the four prices become Bright's at once.
        assertEquals(HttpStatusCode.OK, put(t, """{"syncPricesWithBright":true}"""))
        settings(t).let {
            assertTrue(it["syncPricesWithBright"]!!.jsonPrimitive.boolean)
            assertEquals(6.906, it.number("gasPencePerKwh"))
            assertEquals(27.324, it.number("gasStandingPencePerDay"))
            assertEquals(24.04, it.number("electricityPencePerKwh"))
            assertEquals(41.19, it.number("electricityStandingPencePerDay"))
            // Never touched by it.
            assertEquals(10.1, it.number("boilerAverageKw"))
            assertEquals(16.0, it.number("dailyKwhTarget"))
            assertEquals("GBP", it["currency"]!!.jsonPrimitive.content)
        }
        // A change is logged in the activity feed.
        val logged = t.app.activity.recent(10).filter { it.kind == "prices" }
        assertEquals(1, logged.size)
        assertEquals("Bright", logged.single().reason)

        // While on, the prices cannot be edited.
        assertEquals(HttpStatusCode.BadRequest, put(t, """{"gasPencePerKwh":5.0}"""))
        assertEquals(6.906, settings(t).number("gasPencePerKwh"))

        // Fetched again every day; a fetch that fails keeps the prices and is tried the next day.
        meter.tariff = bright.copy(gasPencePerKwh = 7.1)
        meter.fails = true
        t.clock.advance(Duration.ofDays(1).plusMinutes(1))
        t.app.tick()
        assertEquals(6.906, settings(t).number("gasPencePerKwh"))
        meter.fails = false
        t.clock.advance(Duration.ofHours(1))
        t.app.tick()
        assertEquals(6.906, settings(t).number("gasPencePerKwh"))
        t.clock.advance(Duration.ofDays(1))
        t.app.tick()
        assertEquals(7.1, settings(t).number("gasPencePerKwh"))
        assertEquals(2, t.app.activity.recent(10).count { it.kind == "prices" })

        // Turned off: the last prices stay, and can be edited.
        assertEquals(HttpStatusCode.OK, put(t, """{"syncPricesWithBright":false}"""))
        assertEquals(7.1, settings(t).number("gasPencePerKwh"))
        assertEquals(HttpStatusCode.OK, put(t, """{"gasPencePerKwh":5.0}"""))
        assertEquals(5.0, settings(t).number("gasPencePerKwh"))
        val asked = meter.asked
        t.clock.advance(Duration.ofDays(2))
        t.app.tick()
        assertEquals(asked, meter.asked)
    }

    @Test
    fun `without Bright the switch is not offered`() {
        val t = TestHeating()
        assertEquals(false, settings(t)["brightAvailable"]!!.jsonPrimitive.boolean)
        assertEquals(HttpStatusCode.BadRequest, put(t, """{"syncPricesWithBright":true}"""))
        assertEquals(false, settings(t)["syncPricesWithBright"]!!.jsonPrimitive.boolean)
    }
}
