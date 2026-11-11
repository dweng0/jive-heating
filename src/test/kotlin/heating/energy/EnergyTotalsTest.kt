package heating.energy

import heating.heatingJson
import heating.testing.MutableClock
import heating.testing.TestHeating
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** The dashboard's odometer and trip counter: today, this week, month and year, and yesterday up to now. */
class EnergyTotalsTest {
    /** 18:00 on Thursday 5 November 2026 in London (UTC in November). Monday was 2 November. */
    private val now = Instant.parse("2026-11-05T18:00:00Z")

    private fun totals(t: TestHeating): JsonObject {
        var body: JsonObject? = null
        t.api { client ->
            val response = client.get("/api/energy/totals")
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            body = heatingJson.parseToJsonElement(response.bodyAsText()).jsonObject
        }
        return body!!
    }

    private fun setUp(): TestHeating {
        val t = TestHeating(clock = MutableClock(now))
        t.discover(meters = listOf("Main Clamp"))
        t.api { client -> assertEquals(HttpStatusCode.OK, client.put("/api/meters/Main%20Clamp/house").status) }
        return t
    }

    private fun sample(t: TestHeating, at: Instant, kwh: Double) = t.app.energyHistory.add(MeterSample(at, "Main Clamp", null, kwh, watts = kwh * 12_000))

    private fun JsonObject.kwh(key: String) = this[key]!!.jsonObject["kwh"]!!.jsonPrimitive.double

    // BDD: The odometer totals the day, week, month or year
    @Test
    fun `the odometer totals the day, week, month or year`() {
        val t = setUp()
        sample(t, Instant.parse("2025-12-31T23:55:00Z"), 100.0) // last year
        sample(t, Instant.parse("2026-01-01T00:00:00Z"), 50.0) // covers 23:55-00:00 on 31 December: last year too
        sample(t, Instant.parse("2026-03-10T12:00:00Z"), 1000.0) // this year
        sample(t, Instant.parse("2026-10-31T12:00:00Z"), 200.0) // last month
        sample(t, Instant.parse("2026-11-01T12:00:00Z"), 30.0) // this month, last week
        sample(t, Instant.parse("2026-11-03T12:00:00Z"), 20.0) // this week
        sample(t, Instant.parse("2026-11-05T09:00:00Z"), 8.0) // today
        sample(t, Instant.parse("2026-11-05T18:00:00Z"), 0.2) // today, just now

        val body = totals(t)
        assertEquals(8.2, body.kwh("today"), 1e-9)
        assertEquals(28.2, body.kwh("week"), 1e-9)
        assertEquals(58.2, body.kwh("month"), 1e-9)
        assertEquals(1258.2, body.kwh("year"), 1e-9)
        assertEquals(8.2 * 27.0, body["today"]!!.jsonObject["cost"]!!.jsonPrimitive.double, 1e-6)
        assertEquals(27.0, body["pencePerKwh"]!!.jsonPrimitive.double, 1e-9)
        assertEquals("GBP", body["currency"]!!.jsonPrimitive.content)
    }

    // BDD: Today is compared with yesterday at the same time
    @Test
    fun `today is compared with yesterday at the same time`() {
        val t = setUp()
        val yesterday = now.minus(Duration.ofDays(1))
        sample(t, yesterday.minus(Duration.ofHours(3)), 7.0)
        // After 18:00 yesterday: not part of yesterday so far.
        sample(t, yesterday.plus(Duration.ofHours(2)), 5.0)
        sample(t, now.minus(Duration.ofHours(1)), 8.2)
        assertEquals(7.0, totals(t).kwh("yesterdaySoFar"), 1e-9)

        val quiet = setUp()
        sample(quiet, now.minus(Duration.ofHours(1)), 1.0)
        assertEquals(JsonNull, totals(quiet)["yesterdaySoFar"])
    }

    // BDD: Energy totals say which household day they are for
    @Test
    fun `energy totals say which household day they are for`() {
        // The server's own clock is in New York; the household is in London.
        val clock = MutableClock(Instant.parse("2026-11-10T23:58:00Z"), java.time.ZoneId.of("America/New_York"))
        val t = TestHeating(clock = clock)
        t.discover(meters = listOf("Main Clamp"))
        t.api { client -> assertEquals(HttpStatusCode.OK, client.put("/api/meters/Main%20Clamp/house").status) }
        sample(t, Instant.parse("2026-11-10T23:55:00Z"), 19.4)

        val evening = totals(t)
        assertEquals("2026-11-10", evening["day"]!!.jsonPrimitive.content)
        assertEquals("2026-11-11T00:00:00Z", evening["dayEndsAt"]!!.jsonPrimitive.content)
        assertEquals(19.4, evening.kwh("today"), 1e-9)
        assertEquals("2026-11-10T23:58:00Z", evening["at"]!!.jsonPrimitive.content)

        clock.set(Instant.parse("2026-11-11T00:00:30Z"))
        val morning = totals(t)
        assertEquals("2026-11-11", morning["day"]!!.jsonPrimitive.content)
        assertEquals("2026-11-12T00:00:00Z", morning["dayEndsAt"]!!.jsonPrimitive.content)
        assertEquals(0.0, morning.kwh("today"), 1e-9)

        // Clocks go back at 02:00 BST on Sunday 25 October: that day is 25 hours long.
        clock.set(Instant.parse("2026-10-24T12:00:00Z"))
        assertEquals("2026-10-25T00:00:00+01:00", totals(t)["dayEndsAt"]!!.jsonPrimitive.content)
        clock.set(Instant.parse("2026-10-25T12:00:00Z"))
        val longDay = totals(t)
        assertEquals("2026-10-25", longDay["day"]!!.jsonPrimitive.content)
        assertEquals("2026-10-26T00:00:00Z", longDay["dayEndsAt"]!!.jsonPrimitive.content)
    }
}
