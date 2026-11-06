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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** Today's peak: the highest five-minute house power since midnight, and what was using it. */
class TodayPeakTest {
    /** Midnight on Thursday 5 November in London, which is UTC in November. */
    private val midnight = Instant.parse("2026-11-05T00:00:00Z")

    private fun power(t: TestHeating): JsonObject {
        var body: JsonObject? = null
        t.api { client ->
            val response = client.get("/api/power")
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            body = heatingJson.parseToJsonElement(response.bodyAsText()).jsonObject
        }
        return body!!
    }

    private fun houseMeter(t: TestHeating) {
        t.discover(meters = listOf("Main Clamp"))
        t.api { client -> assertEquals(HttpStatusCode.OK, client.put("/api/meters/Main%20Clamp/house").status) }
    }

    private fun house(t: TestHeating, at: Instant, watts: Double) =
        t.app.energyHistory.add(MeterSample(at, "Main Clamp", null, watts / 12_000, watts = watts))

    // BDD: Today's peak is the highest five-minute power since midnight
    @Test
    fun `today's peak is the highest five-minute power since midnight`() {
        val t = TestHeating(clock = MutableClock(midnight.plus(Duration.ofHours(20))))
        houseMeter(t)
        // Yesterday's last sample, at midnight, counts in yesterday however high.
        house(t, midnight, 9000.0)
        house(t, midnight.plus(Duration.ofHours(8)), 1500.0)
        house(t, midnight.plus(Duration.ofMinutes(18 * 60 + 5)), 6200.0)
        house(t, midnight.plus(Duration.ofHours(19)), 4000.0)

        val peak = power(t)["todayPeak"]!!.jsonObject
        assertEquals(6200.0, peak["watts"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(Instant.parse("2026-11-05T18:05:00Z"), java.time.OffsetDateTime.parse(peak["at"]!!.jsonPrimitive.content).toInstant())

        // Just after midnight: nothing recorded in the new day yet.
        val early = TestHeating(clock = MutableClock(midnight.plus(Duration.ofMinutes(2))))
        houseMeter(early)
        house(early, midnight, 9000.0)
        assertEquals(JsonNull, power(early)["todayPeak"])
    }

    // BDD: Today's peak says what was using the power
    @Test
    fun `today's peak says what was using the power`() {
        val t = TestHeating(clock = MutableClock(midnight.plus(Duration.ofHours(20))))
        houseMeter(t)
        val kitchen = t.app.rooms.create("Kitchen").id
        val lights = t.app.energyAreas.create("Lights").id
        val garage = t.app.energyAreas.create("Garage").id
        val at = midnight.plus(Duration.ofMinutes(18 * 60 + 5))
        house(t, at.minus(Duration.ofHours(2)), 1000.0)
        house(t, at, 6200.0)
        val h = t.app.energyHistory
        h.addDevice(MeterSample(at, "Kettle Plug", null, 0.2, room = kitchen, watts = 2500.0))
        h.addDevice(MeterSample(at, "Oven Plug", null, 0.1, room = kitchen, watts = 1000.0))
        // Lights has no clamp: its plugs count.
        h.addDevice(MeterSample(at, "Lamp Plug", null, 0.03, watts = 400.0, area = lights))
        // Garage's clamp counts, and the plug behind it does not count again.
        h.addArea(MeterSample(at, "Garage Clamp", null, 0.05, room = garage, watts = 600.0))
        h.addDevice(MeterSample(at, "Freezer Plug", null, 0.01, watts = 150.0, area = garage))
        // A plug in no room or area.
        h.addDevice(MeterSample(at, "Fridge Plug", null, 0.01, watts = 100.0))
        // Another time's samples are not part of the peak.
        h.addDevice(MeterSample(at.minus(Duration.ofHours(2)), "Kettle Plug", null, 0.2, room = kitchen, watts = 900.0))

        val parts = power(t)["todayPeak"]!!.jsonObject["parts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("Kitchen" to 3500.0, "Everything else" to 1600.0, "Garage" to 600.0, "Lights" to 400.0, "Fridge Plug" to 100.0),
            parts.map { it["name"]!!.jsonPrimitive.content to it["watts"]!!.jsonPrimitive.double },
        )
        assertEquals(listOf("room", "else", "area", "area", "device"), parts.map { it["kind"]!!.jsonPrimitive.content })
    }
}
