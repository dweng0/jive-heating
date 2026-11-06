package heating.energy

import heating.heatingJson
import heating.testing.MutableClock
import heating.testing.TestHeating
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
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
import kotlin.test.assertTrue

/** A daily kWh target for the house meter, and how today (and each day) compares with it. */
class DailyTargetTest {
    /** Midnight on Thursday 5 November in London, which is UTC in November. */
    private val midnight = Instant.parse("2026-11-05T00:00:00Z")

    private fun put(t: TestHeating, body: String): HttpStatusCode {
        var status = HttpStatusCode.OK
        t.api { client ->
            status = client.put("/api/settings/energy") { contentType(ContentType.Application.Json); setBody(body) }.status
        }
        return status
    }

    private fun settings(t: TestHeating): JsonObject {
        var body: JsonObject? = null
        t.api { client -> body = heatingJson.parseToJsonElement(client.get("/api/settings/energy").bodyAsText()).jsonObject }
        return body!!
    }

    private fun dashboard(t: TestHeating, period: String): JsonObject {
        var body: JsonObject? = null
        t.api { client ->
            val response = client.get("/api/dashboard?period=$period")
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            body = heatingJson.parseToJsonElement(response.bodyAsText()).jsonObject
        }
        return body!!
    }

    private fun JsonObject.number(key: String) = this[key]!!.jsonPrimitive.double

    /** House meter "Main Clamp", recording [kwh] evenly in five-minute samples from [from] to [to], leaving out [gap]. */
    private fun record(t: TestHeating, from: Instant, to: Instant, kwh: Double, gap: ClosedRange<Instant>? = null) {
        val samples = generateSequence(from.plusSeconds(300)) { it.plusSeconds(300) }.takeWhile { it <= to }.toList()
        samples.forEach { at ->
            val missing = gap != null && at > gap.start && at <= gap.endInclusive
            t.app.energyHistory.add(MeterSample(at, "Main Clamp", null, if (missing) null else kwh / samples.size))
        }
    }

    private fun houseMeter(t: TestHeating) {
        t.discover(meters = listOf("Main Clamp"))
        t.api { client -> assertEquals(HttpStatusCode.OK, client.put("/api/meters/Main%20Clamp/house").status) }
    }

    // BDD: An admin sets a daily target
    @Test
    fun `an admin sets a daily target`() {
        val t = TestHeating(clock = MutableClock(midnight))
        assertTrue(settings(t)["dailyKwhTarget"] is JsonNull)
        assertEquals(HttpStatusCode.OK, put(t, """{"dailyKwhTarget":12.0}"""))
        assertEquals(12.0, settings(t).number("dailyKwhTarget"))
        t.restart()
        assertEquals(12.0, settings(t).number("dailyKwhTarget"))

        for (bad in listOf("0", "-1", "1000.5", "\"twelve\"")) {
            assertEquals(HttpStatusCode.BadRequest, put(t, """{"dailyKwhTarget":$bad}"""), bad)
        }
        assertEquals(12.0, settings(t).number("dailyKwhTarget"))
        // Other settings leave it alone; null clears it.
        assertEquals(HttpStatusCode.OK, put(t, """{"gasPencePerKwh":6.5}"""))
        assertEquals(12.0, settings(t).number("dailyKwhTarget"))
        assertEquals(HttpStatusCode.OK, put(t, """{"dailyKwhTarget":null}"""))
        assertTrue(settings(t)["dailyKwhTarget"] is JsonNull)

        t.api {
            val sam = memberSam()
            assertEquals(HttpStatusCode.Forbidden, send(sam, HttpMethod.Put, "/api/settings/energy", """{"dailyKwhTarget":12.0}""").status)
        }
    }

    // BDD: Today's dashboard says whether we are on target
    @Test
    fun `today's dashboard says whether we are on target`() {
        val noon = midnight.plus(Duration.ofHours(12))
        val t = TestHeating(clock = MutableClock(noon))
        houseMeter(t)
        put(t, """{"dailyKwhTarget":12.0}""")
        record(t, midnight, noon, 5.0)

        val target = dashboard(t, "today")["target"]!!.jsonObject
        assertEquals(12.0, target.number("kwh"))
        assertEquals(5.0, target.number("usedKwh"), 1e-9)
        assertEquals(6.0, target.number("expectedKwh"), 1e-9)
        assertEquals(-1.0, target.number("deltaKwh"), 1e-9)
        assertEquals(-27.0, target.number("deltaCost"), 1e-9)
        assertEquals(true, target["onTarget"]!!.jsonPrimitive.boolean)
        assertEquals(10.0, target.number("projectedKwh"), 1e-9)
        assertEquals(false, target["incomplete"]!!.jsonPrimitive.boolean)

        // 7.0 kWh by noon is over.
        val over = TestHeating(clock = MutableClock(noon))
        houseMeter(over)
        put(over, """{"dailyKwhTarget":12.0}""")
        record(over, midnight, noon, 7.0)
        val overTarget = dashboard(over, "today")["target"]!!.jsonObject
        assertEquals(1.0, overTarget.number("deltaKwh"), 1e-9)
        assertEquals(false, overTarget["onTarget"]!!.jsonPrimitive.boolean)

        // Too early in the day to say where it is heading.
        val early = TestHeating(clock = MutableClock(midnight.plus(Duration.ofMinutes(20))))
        houseMeter(early)
        put(early, """{"dailyKwhTarget":12.0}""")
        assertTrue(dashboard(early, "today")["target"]!!.jsonObject["projectedKwh"] is JsonNull)
    }

    // BDD: No target or no house meter means no target figures
    @Test
    fun `no target or no house meter means no target figures`() {
        val noon = midnight.plus(Duration.ofHours(12))
        val noTarget = TestHeating(clock = MutableClock(noon))
        houseMeter(noTarget)
        assertTrue(dashboard(noTarget, "today")["target"] is JsonNull)

        val noMeter = TestHeating(clock = MutableClock(noon))
        put(noMeter, """{"dailyKwhTarget":12.0}""")
        assertTrue(dashboard(noMeter, "today")["target"] is JsonNull)
    }

    // BDD: Missing readings are said, not guessed
    @Test
    fun `missing readings are said, not guessed`() {
        val noon = midnight.plus(Duration.ofHours(12))
        val t = TestHeating(clock = MutableClock(noon))
        houseMeter(t)
        put(t, """{"dailyKwhTarget":12.0}""")
        // 144 samples of 5/144 kWh, the twelve from 09:00 to 10:00 with no reading.
        record(t, midnight, noon, 5.0, gap = midnight.plus(Duration.ofHours(9))..midnight.plus(Duration.ofHours(10)))
        val target = dashboard(t, "today")["target"]!!.jsonObject
        assertEquals(true, target["incomplete"]!!.jsonPrimitive.boolean)
        assertEquals(5.0 * 132 / 144, target.number("usedKwh"), 0.0005)
    }

    // BDD: Days in a week or month are compared with the target
    @Test
    fun `days in a week or month are compared with the target`() {
        // Tuesday 10 November at noon; Monday the 9th used 10.0 kWh and Tuesday 14.0 kWh.
        val monday = Instant.parse("2026-11-09T00:00:00Z")
        val tuesday = monday.plus(Duration.ofDays(1))
        val t = TestHeating(clock = MutableClock(tuesday.plus(Duration.ofHours(23))))
        houseMeter(t)
        put(t, """{"dailyKwhTarget":12.0}""")
        record(t, monday, tuesday, 10.0)
        record(t, tuesday, tuesday.plus(Duration.ofHours(23)), 14.0)

        val week = dashboard(t, "week")
        val days = week["days"]!!.jsonArray.map { it.jsonObject }.associateBy { it["date"]!!.jsonPrimitive.content }
        assertEquals(-2.0, days.getValue("2026-11-09").number("targetDeltaKwh"), 1e-9)
        // Today is compared with the share of today gone: 23 hours of 12.0 kWh is 11.5 kWh.
        assertEquals(14.0 - 11.5, days.getValue("2026-11-10").number("targetDeltaKwh"), 1e-9)
        val target = week["target"]!!.jsonObject
        assertEquals(84.0, target.number("kwh"))
        assertEquals(6 * 12.0 + 11.5, target.number("expectedKwh"), 1e-9)
        assertEquals(24.0, target.number("usedKwh"), 1e-9)
    }
}
