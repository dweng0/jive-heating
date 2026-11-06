package heating.energy

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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The house's use over a day, week, month or year for the history chart. */
class HouseHistoryTest {
    /** 12:00 on Thursday 5 November 2026 in London, which is UTC in November. */
    private val noon = Instant.parse("2026-11-05T12:00:00Z")

    private fun history(t: TestHeating, query: String): Pair<HttpStatusCode, JsonObject?> {
        var result: Pair<HttpStatusCode, JsonObject?> = HttpStatusCode.OK to null
        t.api { client ->
            val response = client.get("/api/energy/history?$query")
            result = response.status to if (response.status == HttpStatusCode.OK) heatingJson.parseToJsonElement(response.bodyAsText()).jsonObject else null
        }
        return result
    }

    private fun ok(t: TestHeating, query: String): JsonObject = history(t, query).let { (status, body) ->
        assertEquals(HttpStatusCode.OK, status, query)
        body!!
    }

    private fun JsonObject.points() = this["points"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.at() = OffsetDateTime.parse(this["at"]!!.jsonPrimitive.content).toInstant()

    // BDD: The house's use over a day, week, month or year
    @Test
    fun `the house's use over a day, week, month or year`() {
        val t = TestHeating(clock = MutableClock(noon))
        t.discover(meters = listOf("Main Clamp"))
        t.api { client ->
            assertEquals(HttpStatusCode.OK, client.put("/api/meters/Main%20Clamp/house").status)
            client.put("/api/settings/energy") { contentType(ContentType.Application.Json); setBody("""{"dailyKwhTarget":10.0}""") }
        }
        // A sample every five minutes for 400 days: 0.05 kWh each (600 W), 14.4 kWh a day.
        val start = noon.minus(Duration.ofDays(400))
        var at = start
        while (at < noon) {
            at = at.plusSeconds(300)
            t.app.energyHistory.add(MeterSample(at, "Main Clamp", null, 0.05, watts = 600.0))
        }

        val month = ok(t, "range=month")
        assertEquals("GBP", month["currency"]!!.jsonPrimitive.content)
        assertEquals(start.plusSeconds(300), OffsetDateTime.parse(month["earliest"]!!.jsonPrimitive.content).toInstant())
        val days = month.points()
        assertEquals(30, days.size)
        assertEquals(Instant.parse("2026-10-06T23:00:00Z"), days.first().at()) // London is on BST then
        assertEquals(Instant.parse("2026-11-05T00:00:00Z"), days.last().at())
        assertEquals(14.4, days.first()["kwh"]!!.jsonPrimitive.double, 1e-6)
        assertEquals(600.0, days.first()["watts"]!!.jsonPrimitive.double, 1e-6)
        assertEquals(10.0, days.first()["targetKwh"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(false, days.first()["incomplete"]!!.jsonPrimitive.boolean)
        // Today so far: 12 hours.
        assertEquals(7.2, days.last()["kwh"]!!.jsonPrimitive.double, 1e-6)
        assertEquals(7.2 * 27.0, days.last()["cost"]!!.jsonPrimitive.double, 1e-6)

        val day = ok(t, "range=day").points()
        assertEquals(288, day.size)
        assertTrue(day.all { it["targetKwh"] is JsonNull })
        val week = ok(t, "range=week").points()
        assertEquals(168, week.size)
        assertEquals(Instant.parse("2026-10-29T12:00:00Z"), week.first().at())
        assertTrue(week.all { it["targetKwh"] is JsonNull })
        val year = ok(t, "range=year").points()
        assertEquals(12, year.size)
        assertEquals(Instant.parse("2025-12-01T00:00:00Z"), year.first().at())
        // December has 31 days.
        assertEquals(310.0, year.first()["targetKwh"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(14.4 * 31, year.first()["kwh"]!!.jsonPrimitive.double, 1e-6)

        // Ending a week ago; a time in the future means now.
        val earlier = ok(t, "range=month&to=2026-10-29T12:00:00Z").points()
        assertEquals(Instant.parse("2026-10-29T00:00:00Z"), earlier.last().at())
        assertEquals(days.last().at(), ok(t, "range=month&to=2027-01-01T00:00:00Z").points().last().at())
        // Before anything was recorded: points with nothing in them.
        val before = ok(t, "range=week&to=2025-01-01T00:00:00Z").points()
        assertTrue(before.all { it["kwh"] is JsonNull })

        assertEquals(HttpStatusCode.BadRequest, history(t, "range=decade").first)
        assertEquals(HttpStatusCode.BadRequest, history(t, "range=day&to=yesterday").first)
    }
}
