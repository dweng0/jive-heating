package heating.energy

import heating.heatingJson
import heating.testing.MutableClock
import heating.testing.TestHeating
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonElement
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnergyUsageTest {

    // Midnight in London.
    private val start = Instant.parse("2026-10-11T23:00:00Z")

    private fun minutes(t: TestHeating, n: Long) {
        t.clock.advance(Duration.ofMinutes(n))
        t.app.tick()
    }

    private fun read(t: TestHeating, path: String): JsonObject {
        var result = JsonObject(emptyMap())
        t.api { client ->
            val response = client.get(path)
            assertEquals(HttpStatusCode.OK, response.status, path)
            result = heatingJson.parseToJsonElement(response.bodyAsText()).jsonObject
        }
        return result
    }

    private fun status(t: TestHeating, path: String): HttpStatusCode {
        var result = HttpStatusCode.OK
        t.api { client -> result = client.get(path).status }
        return result
    }

    private fun JsonElement.number(name: String): Double? = jsonObject[name]!!.let { if (it is JsonNull) null else it.jsonPrimitive.double }

    private fun JsonElement.at() = jsonObject["at"]!!.jsonPrimitive.content

    private fun JsonElement.incomplete() = jsonObject["incomplete"]!!.jsonPrimitive.boolean

    private fun JsonObject.points(name: String) = this[name]!!.jsonArray

    private fun JsonObject.plug(meter: String) = points("plugs").single { it.jsonObject["meter"]!!.jsonPrimitive.content == meter }.jsonObject.points("points")

    private fun at(minutes: Long) = start.plus(Duration.ofMinutes(minutes))

    /** Fridge 80 W and kettle 20 W in [room], each five minutes for [hours] from [start]. */
    private fun fill(t: TestHeating, room: String, hours: Long) {
        (1..hours * 12).forEach { i ->
            val time = at(5 * i)
            t.app.energyHistory.addDevice(MeterSample(time, "Fridge Plug", 1.0, 80.0 * 5 / 60_000, room, 80.0))
            t.app.energyHistory.addDevice(MeterSample(time, "Kettle Plug", 1.0, 20.0 * 5 / 60_000, room, 20.0))
        }
    }

    /** A Kitchen holding the fridge and kettle plugs, and the price at 25p per kWh. */
    private fun kitchen(start: Instant = this.start): Pair<TestHeating, String> {
        val t = TestHeating(clock = MutableClock(start))
        t.discover(meters = listOf("Fridge Plug", "Kettle Plug"))
        val room = t.app.rooms.create("Kitchen").id
        t.api { client ->
            listOf("Fridge Plug", "Kettle Plug").forEach { plug ->
                val name = plug.replace(" ", "%20")
                assertEquals(HttpStatusCode.OK, client.put("/api/meters/$name/monitored").status)
                assertEquals(HttpStatusCode.OK, client.put("/api/rooms/$room/meters/$name").status)
            }
        }
        t.app.energySettings.update { it.copy(electricityPencePerKwh = 25.0) }
        return t to room
    }

    // BDD: A plug's average power is recorded every five minutes
    @Test
    fun `a plug's five-minute sample keeps its average power, and none without a reading`() {
        val (t, room) = kitchen()
        t.app.tick()
        t.meterState("Fridge Plug", """{"energy":1.0,"power":60}""")
        t.clock.advance(Duration.ofSeconds(150))
        t.meterState("Fridge Plug", """{"energy":1.0,"power":120}""")
        t.clock.advance(Duration.ofSeconds(150))
        t.app.tick()

        val samples = { t.app.energyHistory.roomSamples(room, Instant.EPOCH, t.clock.instant()).filter { it.meter == "Fridge Plug" } }
        assertEquals(90.0, samples().single().watts!!, 1e-6)

        // The next five minutes hold the last power.
        minutes(t, 5)
        assertEquals(120.0, samples().last().watts!!, 1e-6)

        // A plug with no reading has no power, never 0 W.
        t.app.energyHistory.roomSamples(room, Instant.EPOCH, t.clock.instant()).filter { it.meter == "Kettle Plug" }.forEach { assertNull(it.watts) }
        minutes(t, 30)
        repeat(2) { minutes(t, 5) }
        assertNull(samples().last().watts)
        assertNull(samples().last().kwh)

        // A sample from before power was kept keeps its kWh.
        t.app.energyHistory.addDevice(MeterSample(t.clock.instant().plusMillis(1), "Fridge Plug", 1.0, 0.25, room))
        t.clock.advance(Duration.ofSeconds(1))
        assertNull(samples().last().watts)
        assertEquals(0.25, samples().last().kwh!!, 1e-9)
    }

    // BDD: A room's use is read over time
    @Test
    fun `a room's use comes in five-minute, hourly and daily points, for it and each plug`() {
        val (t, room) = kitchen()
        fill(t, room, 3)
        // Use recorded in another room never counts here, and a plug moved out still counts where it was.
        t.app.energyHistory.addDevice(MeterSample(at(5), "Other Plug", 1.0, 1.0, "elsewhere", 12000.0))
        t.clock.advance(Duration.ofHours(3))

        val day = read(t, "/api/rooms/$room/plugs/usage?window=24h")
        assertEquals(36, day.points("room").size)
        val first = day.points("room").first()
        assertEquals("2026-10-12T00:05:00+01:00", first.at())
        assertEquals(100.0, first.number("watts")!!, 1e-6)
        assertEquals(0.0083, first.number("kwh")!!, 1e-6)
        assertEquals(0.21, first.number("cost")!!, 1e-6)
        assertFalse(first.incomplete())
        assertEquals(36, day.plug("Fridge Plug").size)
        assertEquals(80.0, day.plug("Fridge Plug").first().number("watts")!!, 1e-6)
        assertEquals(20.0, day.plug("Kettle Plug").first().number("watts")!!, 1e-6)
        assertEquals("GBP", day["currency"]!!.jsonPrimitive.content)

        // 7d: an hour a point, watts the average over it.
        val week = read(t, "/api/rooms/$room/plugs/usage?window=7d")
        assertEquals(3, week.points("room").size)
        assertEquals("2026-10-12T00:00:00+01:00", week.points("room").first().at())
        assertEquals(100.0, week.points("room").first().number("watts")!!, 1e-6)
        assertEquals(0.1, week.points("room").first().number("kwh")!!, 1e-6)
        assertEquals(2.5, week.points("room").first().number("cost")!!, 1e-6)

        // 30d: a day a point, from the household's midnight.
        val month = read(t, "/api/rooms/$room/plugs/usage?window=30d")
        assertEquals("2026-10-12T00:00:00+01:00", month.points("room").single().at())
        assertEquals(0.3, month.points("room").single().number("kwh")!!, 1e-6)
        assertEquals(80.0, month.plug("Fridge Plug").single().number("watts")!!, 1e-6)

        // Admin only, and a room that is not there.
        assertEquals(HttpStatusCode.NotFound, status(t, "/api/rooms/nowhere/plugs/usage"))
        t.api { client ->
            val sam = memberSam()
            val users = t.app.users
            users.setRooms(users.list().single { it.username == "sam" }.id, listOf(room), mapOf(room to "Kitchen"))
            assertEquals(HttpStatusCode.Forbidden, send(sam, HttpMethod.Get, "/api/rooms/$room/plugs/usage").status)
            assertEquals(HttpStatusCode.OK, client.get("/api/rooms/$room/plugs/usage").status)
        }
    }

    // BDD: A room's use is read further back
    @Test
    fun `a room's use is read up to a chosen time, saying how far back it goes`() {
        val (t, room) = kitchen()
        fill(t, room, 48)
        t.clock.advance(Duration.ofHours(48))

        val back = read(t, "/api/rooms/$room/plugs/usage?window=24h&to=2026-10-12T12:00:00Z")
        assertEquals("2026-10-12T13:00:00+01:00", back.points("room").last().at())
        assertEquals(13 * 12, back.points("room").size)
        assertEquals("2026-10-12T00:05:00+01:00", back["earliest"]!!.jsonPrimitive.content)
        assertEquals("2026-10-12T13:00:00+01:00", back["to"]!!.jsonPrimitive.content)

        // A time to come is now.
        val future = read(t, "/api/rooms/$room/plugs/usage?window=24h&to=2099-01-01T00:00:00Z")
        assertEquals(read(t, "/api/rooms/$room/plugs/usage?window=24h")["to"], future["to"])

        assertEquals(HttpStatusCode.BadRequest, status(t, "/api/rooms/$room/plugs/usage?window=24h&to=soon"))
        assertEquals(HttpStatusCode.BadRequest, status(t, "/api/rooms/$room/plugs/usage?window=1y"))

        // A room with nothing recorded has no earliest.
        val spare = t.app.rooms.create("Spare").id
        assertTrue(read(t, "/api/rooms/$spare/plugs/usage")["earliest"] is JsonNull)
    }

    // BDD: Missing readings are not shown as no use
    @Test
    fun `a plug with no reading has empty points, and the room only what was recorded`() {
        val (t, room) = kitchen()
        fill(t, room, 1)
        val stale = at(65)
        t.app.energyHistory.addDevice(MeterSample(stale, "Fridge Plug", null, null, room))
        t.app.energyHistory.addDevice(MeterSample(stale, "Kettle Plug", 1.0, 20.0 * 5 / 60_000, room, 20.0))
        // The fridge is back: its catch-up stays in the five minutes it came back.
        val back = at(70)
        t.app.energyHistory.addDevice(MeterSample(back, "Fridge Plug", 2.0, 0.5, room, 80.0))
        t.app.energyHistory.addDevice(MeterSample(back, "Kettle Plug", 1.0, 20.0 * 5 / 60_000, room, 20.0))
        t.clock.advance(Duration.ofMinutes(70))

        val day = read(t, "/api/rooms/$room/plugs/usage?window=24h")
        val plugPoint = day.plug("Fridge Plug")[12]
        assertNull(plugPoint.number("watts"))
        assertNull(plugPoint.number("kwh"))
        assertNull(plugPoint.number("cost"))
        assertTrue(plugPoint.incomplete())
        val roomPoint = day.points("room")[12]
        assertEquals(20.0, roomPoint.number("watts")!!, 1e-6)
        assertEquals(0.0017, roomPoint.number("kwh")!!, 1e-6)
        assertTrue(roomPoint.incomplete())
        assertEquals(0.5, day.plug("Fridge Plug")[13].number("kwh")!!, 1e-6)

        // An hour holding the gap is incomplete, its kWh only what was recorded.
        val hour = read(t, "/api/rooms/$room/plugs/usage?window=7d").plug("Fridge Plug")[1]
        assertTrue(hour.incomplete())
        assertEquals(0.5, hour.number("kwh")!!, 1e-6)
    }

    // BDD: Everything else is read over time
    @Test
    fun `everything else is the house less all monitored plugs, over time`() {
        val (t, room) = kitchen()
        t.api { client -> assertEquals(HttpStatusCode.OK, client.put("/api/meters/Kettle%20Plug/monitored").status) }
        val history = t.app.energyHistory
        fun house(minutes: Long, watts: Double?) = history.add(MeterSample(at(minutes), "Main Clamp", 1.0, watts?.let { it * 5 / 60_000 }, watts = watts))
        fun plugs(minutes: Long, fridge: Double?, kettle: Double?) {
            history.addDevice(MeterSample(at(minutes), "Fridge Plug", 1.0, fridge?.let { it * 5 / 60_000 }, room, fridge))
            // A plug in no room counts too.
            history.addDevice(MeterSample(at(minutes), "Kettle Plug", 1.0, kettle?.let { it * 5 / 60_000 }, null, kettle))
        }
        house(5, 1000.0); plugs(5, 200.0, 100.0)
        // Plugs above the house: never below 0.
        house(10, 100.0); plugs(10, 200.0, 100.0)
        // No house reading: not known.
        house(15, null); plugs(15, 200.0, 100.0)
        // A plug with no reading: only what is known, and marked.
        house(20, 1000.0); plugs(20, null, 100.0)
        t.clock.advance(Duration.ofMinutes(20))

        val usage = read(t, "/api/energy/else/usage?window=24h")
        assertFalse("plugs" in usage)
        val points = usage.points("room")
        assertEquals(700.0, points[0].number("watts")!!, 1e-6)
        assertEquals(0.0583, points[0].number("kwh")!!, 1e-6)
        assertEquals(1.46, points[0].number("cost")!!, 1e-6)
        assertFalse(points[0].incomplete())
        assertEquals(0.0, points[1].number("watts")!!, 1e-6)
        assertEquals(0.0, points[1].number("kwh")!!, 1e-6)
        assertNull(points[2].number("watts"))
        assertNull(points[2].number("kwh"))
        assertNull(points[2].number("cost"))
        assertTrue(points[2].incomplete())
        assertEquals(900.0, points[3].number("watts")!!, 1e-6)
        assertTrue(points[3].incomplete())

        // An hour's point adds its five minutes up, and it takes "to" like a room's.
        val hour = read(t, "/api/energy/else/usage?window=7d").points("room").single()
        assertTrue(hour.incomplete())
        assertEquals(0.1333, hour.number("kwh")!!, 1e-6)
        assertEquals(1, read(t, "/api/energy/else/usage?window=24h&to=2026-10-11T23:05:00Z").points("room").size)
        assertEquals("2026-10-12T00:05:00+01:00", usage["earliest"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.BadRequest, status(t, "/api/energy/else/usage?window=1y"))

        t.api { client ->
            assertEquals(HttpStatusCode.Forbidden, send(memberSam(), HttpMethod.Get, "/api/energy/else/usage").status)
            assertEquals(HttpStatusCode.OK, client.get("/api/energy/else/usage").status)
        }
    }
}
