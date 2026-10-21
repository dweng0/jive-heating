package heating.energy

import heating.heatingJson
import heating.testing.MutableClock
import heating.testing.TestHeating
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
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

class RoomEnergyHistoryTest {

    private fun history(t: TestHeating, room: String, range: String): JsonObject {
        var result = JsonObject(emptyMap())
        t.api { client ->
            val response = client.get("/api/rooms/$room/plugs/history?range=$range")
            assertEquals(HttpStatusCode.OK, response.status)
            result = heatingJson.parseToJsonElement(response.bodyAsText()).jsonObject
        }
        return result
    }

    /** Bar start (local, as the API gives it) to its kWh (null: no reading at all) and whether it is incomplete. */
    private fun bars(history: JsonObject) = history["points"]!!.jsonArray.map { it.jsonObject }.associate {
        it["at"]!!.jsonPrimitive.content to (it["kwh"]!!.let { k -> if (k is JsonNull) null else k.jsonPrimitive.double } to it["incomplete"]!!.jsonPrimitive.boolean)
    }

    // BDD: A room's electricity is charted over time
    @Test
    fun `a room's use as hourly and daily bars, marked incomplete where a plug had no reading`() {
        // Midnight in London.
        val start = Instant.parse("2026-10-11T23:00:00Z")
        val t = TestHeating(clock = MutableClock(start))
        val kitchen = t.app.rooms.create("Kitchen").id
        val lounge = t.app.rooms.create("Lounge").id
        val hall = t.app.rooms.create("Hall").id
        val spare = t.app.rooms.create("Spare").id
        // 80 W and 20 W for each five minutes until 16:00; the lamp had no reading 14:20 to 15:00, the hall plug 13:00 to 14:00.
        val tick = Duration.ofMinutes(5)
        fun between(at: Instant, fromMinutes: Long, toMinutes: Long) = at > start.plus(Duration.ofMinutes(fromMinutes)) && at <= start.plus(Duration.ofMinutes(toMinutes))
        var at = start.plus(tick)
        while (at <= start.plus(Duration.ofHours(16))) {
            t.app.energyHistory.addDevice(MeterSample(at, "Fridge Plug", 1.0, 80.0 * 5 / 60_000, kitchen))
            val lampStale = between(at, 14 * 60 + 20, 15 * 60)
            t.app.energyHistory.addDevice(MeterSample(at, "Lamp Plug", if (lampStale) null else 1.0, if (lampStale) null else 20.0 * 5 / 60_000, kitchen))
            // Use recorded in another room never counts here.
            t.app.energyHistory.addDevice(MeterSample(at, "Heater Plug", 1.0, 2000.0 * 5 / 60_000, lounge))
            val hallStale = between(at, 13 * 60, 14 * 60)
            t.app.energyHistory.addDevice(MeterSample(at, "Hall Plug", if (hallStale) null else 1.0, if (hallStale) null else 60.0 * 5 / 60_000, hall))
            at = at.plus(tick)
        }
        t.clock.advance(Duration.ofHours(16))

        // One bar per hour, stamped with its start; a sample counts in the hour it ends.
        val day = bars(history(t, kitchen, "24h"))
        assertEquals(16, day.size)
        assertEquals(0.1 to false, day.getValue("2026-10-12T10:00:00+01:00"))
        // The lamp's missing 40 minutes: only what was recorded, and incomplete.
        assertEquals(0.087 to true, day.getValue("2026-10-12T14:00:00+01:00"))
        assertEquals(0.1 to false, day.getValue("2026-10-12T15:00:00+01:00"))
        // An hour with no reading at all has no use, not 0 kWh.
        assertEquals(null to true, bars(history(t, hall, "24h")).getValue("2026-10-12T13:00:00+01:00"))

        // 7d and 30d: one bar per day from the household's midnight.
        val week = bars(history(t, kitchen, "7d"))
        assertEquals(1, week.size)
        assertEquals(1.587 to true, week.getValue("2026-10-12T00:00:00+01:00"))
        assertEquals(1.587 to true, bars(history(t, kitchen, "30d")).getValue("2026-10-12T00:00:00+01:00"))

        // A room with no plug use in the range has no bars; the card is hidden.
        assertEquals(0, history(t, spare, "24h")["points"]!!.jsonArray.size)

        // Admin only, like the room's plugs.
        t.api { client ->
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/rooms/$kitchen/plugs/history?range=1y").status)
            val sam = memberSam()
            assertEquals(HttpStatusCode.NotFound, send(sam, HttpMethod.Get, "/api/rooms/$kitchen/plugs/history").status)
            val users = t.app.users
            users.setRooms(users.list().single { it.username == "sam" }.id, listOf(kitchen), mapOf(kitchen to "Kitchen"))
            assertEquals(HttpStatusCode.Forbidden, send(sam, HttpMethod.Get, "/api/rooms/$kitchen/plugs/history").status)
        }
    }
}
