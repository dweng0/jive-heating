package heating.energy

import heating.heatingJson
import heating.testing.MutableClock
import heating.testing.TestHeating
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RoomPlugsTest {

    private fun put(t: TestHeating, path: String) = t.api { client ->
        assertEquals(HttpStatusCode.OK, client.request(path.replace(" ", "%20")) { method = HttpMethod.Put }.status)
    }

    private fun plugs(t: TestHeating, room: String): JsonObject {
        var result = JsonObject(emptyMap())
        t.api { client ->
            val response = client.get("/api/rooms/$room/plugs")
            assertEquals(HttpStatusCode.OK, response.status)
            result = heatingJson.parseToJsonElement(response.bodyAsText()).jsonObject
        }
        return result
    }

    // BDD: A plug is put in a room from the room's settings
    @Test
    fun `a room's plugs show their power now and cost today, with offline times`() {
        // Midnight in London.
        val start = Instant.parse("2026-10-11T23:00:00Z")
        val t = TestHeating(clock = MutableClock(start))
        t.discover(meters = listOf("Main Clamp", "Fridge Plug", "Kettle Plug", "Lamp Plug"))
        listOf("Fridge Plug", "Kettle Plug", "Lamp Plug").forEach { put(t, "/api/meters/$it/monitored") }
        val kitchen = t.app.rooms.create("Kitchen").id
        val lounge = t.app.rooms.create("Lounge").id
        put(t, "/api/rooms/$kitchen/meters/Fridge Plug")
        put(t, "/api/rooms/$kitchen/meters/Kettle Plug")
        put(t, "/api/rooms/$lounge/meters/Lamp Plug")

        val history = t.app.energyHistory
        // Yesterday's use is not today's.
        history.addDevice(MeterSample(start, "Fridge Plug", 99.0, 5.0, kitchen))
        // To 16:00: the fridge is offline 14:20 to 15:00 (the samples at 14:25..15:00 have no reading).
        (1..192).forEach { i ->
            val at = start.plus(Duration.ofMinutes(5L * i))
            history.addDevice(MeterSample(at, "Fridge Plug", 100.0, if (i in 173..180) null else if (i == 1) 0.4 else 0.0, kitchen))
            history.addDevice(MeterSample(at, "Kettle Plug", 200.0, if (i == 2) 4.2 else 0.0, kitchen))
            history.addDevice(MeterSample(at, "Lamp Plug", 300.0, 1.0, lounge))
        }
        t.clock.advance(Duration.ofHours(16))
        t.meterState("Fridge Plug", """{"power":85,"energy":100.4}""")

        val k = plugs(t, kitchen)
        assertEquals("GBP", k["currency"]!!.jsonPrimitive.content)
        // 4.6 kWh at the default 27p per kWh, no standing charge.
        assertEquals(4.6, k["kwh"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(124.2, k["cost"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(true, k["incomplete"]!!.jsonPrimitive.boolean)
        assertEquals(40, k["missingMinutes"]!!.jsonPrimitive.int)

        val plugs = k["plugs"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Fridge Plug", "Kettle Plug"), plugs.map { it["meter"]!!.jsonPrimitive.content })
        val (fridge, kettle) = plugs
        assertEquals(85.0, fridge["power"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(10.8, fridge["cost"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(true, fridge["incomplete"]!!.jsonPrimitive.boolean)
        val offline = fridge["offline"]!!.jsonArray.single().jsonObject
        assertEquals("2026-10-12T14:20:00+01:00", offline["from"]!!.jsonPrimitive.content)
        assertEquals("2026-10-12T15:00:00+01:00", offline["to"]!!.jsonPrimitive.content)
        // The kettle has sent nothing since it was found at midnight, so it is offline now: power unknown, cost so far.
        assertEquals(JsonNull, kettle["power"])
        assertEquals(113.4, kettle["cost"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(true, kettle["incomplete"]!!.jsonPrimitive.boolean)
        assertEquals("2026-10-12T00:00:00+01:00", kettle["offlineSince"]!!.jsonPrimitive.content)
        assertEquals(0, kettle["offline"]!!.jsonArray.size)

        // A room with no plugs has an empty list, and room energy is admin only.
        val hall = t.app.rooms.create("Hall").id
        assertEquals(0, plugs(t, hall)["plugs"]!!.jsonArray.size)
        t.api {
            val sam = memberSam()
            assertEquals(HttpStatusCode.NotFound, send(sam, HttpMethod.Get, "/api/rooms/$kitchen/plugs").status)
            val users = t.app.users
            users.setRooms(users.list().single { it.username == "sam" }.id, listOf(kitchen), mapOf(kitchen to "Kitchen"))
            assertEquals(HttpStatusCode.Forbidden, send(sam, HttpMethod.Get, "/api/rooms/$kitchen/plugs").status)
        }
    }

    // BDD: A plug that is offline now says so
    @Test
    fun `a plug that is offline now has no power and says since when, in the room and on the Dashboard`() {
        // Midnight in London.
        val start = Instant.parse("2026-10-11T23:00:00Z")
        val t = TestHeating(clock = MutableClock(start))
        t.discover(meters = listOf("TV Plug", "Lamp Plug", "Fan Plug"))
        listOf("TV Plug", "Lamp Plug", "Fan Plug").forEach { put(t, "/api/meters/$it/monitored") }
        val bedroom1 = t.app.rooms.create("Bedroom 1").id
        listOf("TV Plug", "Lamp Plug", "Fan Plug").forEach { put(t, "/api/rooms/$bedroom1/meters/$it") }
        t.app.energyHistory.addDevice(MeterSample(start.plus(Duration.ofHours(14)), "TV Plug", 10.0, 0.5, bedroom1))

        // At 14:20 all three report; then Zigbee2MQTT says the TV plug is offline, and the lamp plug goes silent.
        t.clock.advance(Duration.ofMinutes(14 * 60 + 20))
        listOf("TV Plug", "Lamp Plug", "Fan Plug").forEach { t.meterState(it, """{"power":60,"energy":10.5}""") }
        t.availability("TV Plug", online = false)
        t.clock.advance(Duration.ofMinutes(30))
        t.meterState("Fan Plug", """{"power":40,"energy":10.5}""")

        val room = plugs(t, bedroom1)
        // No stale sample yet, but the room's cost is only so far too.
        assertEquals(0, room["missingMinutes"]!!.jsonPrimitive.int)
        assertEquals(true, room["incomplete"]!!.jsonPrimitive.boolean)
        val plugs = room["plugs"]!!.jsonArray.map { it.jsonObject }.associateBy { it["meter"]!!.jsonPrimitive.content }
        // Offline by availability or by 30 minutes of silence: no power, the time it went, and the cost so far is incomplete.
        listOf("TV Plug", "Lamp Plug").forEach { name ->
            val plug = plugs.getValue(name)
            assertEquals(JsonNull, plug["power"], name)
            assertEquals("2026-10-12T14:20:00+01:00", plug["offlineSince"]!!.jsonPrimitive.content, name)
            assertEquals(true, plug["incomplete"]!!.jsonPrimitive.boolean, name)
        }
        assertEquals(13.5, plugs.getValue("TV Plug")["cost"]!!.jsonPrimitive.double, 1e-9)
        val fan = plugs.getValue("Fan Plug")
        assertEquals(40.0, fan["power"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(JsonNull, fan["offlineSince"])
        assertEquals(false, fan["incomplete"]!!.jsonPrimitive.boolean)

        t.api { client ->
            val devices = heatingJson.parseToJsonElement(client.get("/api/dashboard?period=today").bodyAsText()).jsonObject["devices"]!!
                .jsonArray.map { it.jsonObject }.associateBy { it["meter"]!!.jsonPrimitive.content }
            assertEquals("2026-10-12T14:20:00+01:00", devices.getValue("TV Plug")["offlineSince"]!!.jsonPrimitive.content)
            assertEquals(true, devices.getValue("TV Plug")["incomplete"]!!.jsonPrimitive.boolean)
            assertEquals("2026-10-12T14:20:00+01:00", devices.getValue("Lamp Plug")["offlineSince"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, devices.getValue("Fan Plug")["offlineSince"])
        }

        // Back online: no longer offline now.
        t.availability("TV Plug", online = true)
        t.meterState("TV Plug", """{"power":55,"energy":10.6}""")
        assertEquals(JsonNull, plugs(t, bedroom1)["plugs"]!!.jsonArray.map { it.jsonObject }.single { it["meter"]!!.jsonPrimitive.content == "TV Plug" }["offlineSince"])
    }

    // BDD: A plug whose energy total stops going up is flagged
    @Test
    fun `a plug using power while its energy total stays the same is flagged until the total goes up`() {
        // Midnight in London.
        val start = Instant.parse("2026-10-11T23:00:00Z")
        val t = TestHeating(clock = MutableClock(start))
        t.discover(meters = listOf("TV Plug", "Lamp Plug"))
        listOf("TV Plug", "Lamp Plug").forEach { put(t, "/api/meters/$it/monitored") }
        val bedroom1 = t.app.rooms.create("Bedroom 1").id
        listOf("TV Plug", "Lamp Plug").forEach { put(t, "/api/rooms/$bedroom1/meters/$it") }

        // From 14:00 the TV plug reports 60 W and the lamp 5 W every ten minutes, totals never moving.
        t.clock.advance(Duration.ofHours(14))
        fun report() {
            t.meterState("TV Plug", """{"power":60,"energy":10.5}""")
            t.meterState("Lamp Plug", """{"power":5,"energy":3.2}""")
        }
        fun plug(name: String) = plugs(t, bedroom1)["plugs"]!!.jsonArray.map { it.jsonObject }.single { it["meter"]!!.jsonPrimitive.content == name }
        report()
        repeat(4) { t.clock.advance(Duration.ofMinutes(10)); report() }
        // 60 W for 40 minutes is 0.04 kWh: under 0.05, so not yet.
        assertEquals(JsonNull, plug("TV Plug")["totalStuckSince"])
        repeat(2) { t.clock.advance(Duration.ofMinutes(10)); report() }

        // An hour at 60 W should have used 0.06 kWh.
        val tv = plug("TV Plug")
        assertEquals("2026-10-12T14:00:00+01:00", tv["totalStuckSince"]!!.jsonPrimitive.content)
        assertEquals(true, tv["incomplete"]!!.jsonPrimitive.boolean)
        assertEquals(60.0, tv["power"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(true, plugs(t, bedroom1)["incomplete"]!!.jsonPrimitive.boolean)
        t.api { client ->
            val devices = heatingJson.parseToJsonElement(client.get("/api/dashboard?period=today").bodyAsText()).jsonObject["devices"]!!
                .jsonArray.map { it.jsonObject }.associateBy { it["meter"]!!.jsonPrimitive.content }
            assertEquals("2026-10-12T14:00:00+01:00", devices.getValue("TV Plug")["totalStuckSince"]!!.jsonPrimitive.content)
            assertEquals(true, devices.getValue("TV Plug")["incomplete"]!!.jsonPrimitive.boolean)
            assertEquals(JsonNull, devices.getValue("Lamp Plug")["totalStuckSince"])
        }

        // A 5 W lamp rightly shows no change for hours: three hours is only 0.015 kWh.
        repeat(12) { t.clock.advance(Duration.ofMinutes(10)); report() }
        val lamp = plug("Lamp Plug")
        assertEquals(JsonNull, lamp["totalStuckSince"])
        assertEquals(false, lamp["incomplete"]!!.jsonPrimitive.boolean)

        // The total goes up again: the flag goes.
        t.meterState("TV Plug", """{"power":60,"energy":10.7}""")
        assertEquals(JsonNull, plug("TV Plug")["totalStuckSince"])
        assertEquals(false, plug("TV Plug")["incomplete"]!!.jsonPrimitive.boolean)
        t.api { client ->
            val devices = heatingJson.parseToJsonElement(client.get("/api/dashboard?period=today").bodyAsText()).jsonObject["devices"]!!
                .jsonArray.map { it.jsonObject }.associateBy { it["meter"]!!.jsonPrimitive.content }
            assertEquals(JsonNull, devices.getValue("TV Plug")["totalStuckSince"])
        }

        // Offline now wins: no power reading, so no flag.
        repeat(7) { t.clock.advance(Duration.ofMinutes(10)); report() }
        assertNotEquals(JsonNull, plug("TV Plug")["totalStuckSince"])
        t.availability("TV Plug", online = false)
        assertEquals(JsonNull, plug("TV Plug")["totalStuckSince"])
    }

    // BDD: A plug is put in a room from the room's settings
    @Test
    fun `a room's plugs count only the use recorded in the room`() {
        val start = Instant.parse("2026-10-11T23:00:00Z")
        val t = TestHeating(clock = MutableClock(start))
        t.discover(meters = listOf("Fridge Plug", "Kettle Plug", "Heater Plug"))
        listOf("Fridge Plug", "Kettle Plug", "Heater Plug").forEach { put(t, "/api/meters/$it/monitored") }
        val kitchen = t.app.rooms.create("Kitchen").id
        val lounge = t.app.rooms.create("Lounge").id

        val history = t.app.energyHistory
        // Each plug uses 0.1 kWh every five minutes for two hours. The fridge was in the kitchen all along;
        // the kettle came over from the lounge after an hour; the heater went to the lounge after an hour,
        // and in the lounge it was offline for ten minutes.
        (1..24).forEach { i ->
            val at = start.plus(Duration.ofMinutes(5L * i))
            history.addDevice(MeterSample(at, "Fridge Plug", 100.0, 0.1, kitchen))
            history.addDevice(MeterSample(at, "Kettle Plug", 200.0, 0.1, if (i <= 12) lounge else kitchen))
            history.addDevice(MeterSample(at, "Heater Plug", 300.0, if (i in 15..16) null else 0.1, if (i <= 12) kitchen else lounge))
        }
        put(t, "/api/rooms/$kitchen/meters/Fridge Plug")
        put(t, "/api/rooms/$kitchen/meters/Kettle Plug")
        put(t, "/api/rooms/$lounge/meters/Heater Plug")
        t.clock.advance(Duration.ofHours(2))
        // All three are reporting now, so none is offline now.
        listOf("Fridge Plug", "Kettle Plug", "Heater Plug").forEach { t.meterState(it, """{"power":100,"energy":400}""") }

        val k = plugs(t, kitchen)
        // The kitchen's use is what was recorded there, the heater's first hour included, as on the Dashboard.
        assertEquals(2.4 + 1.2 + 1.2, k["kwh"]!!.jsonPrimitive.double, 1e-9)
        // The heater's offline time was in the lounge, so the kitchen is complete.
        assertEquals(false, k["incomplete"]!!.jsonPrimitive.boolean)
        val plugs = k["plugs"]!!.jsonArray.map { it.jsonObject }.associateBy { it["meter"]!!.jsonPrimitive.content }
        // Listed are the plugs in it now, each with its use here only.
        assertEquals(setOf("Fridge Plug", "Kettle Plug"), plugs.keys)
        assertEquals(2.4, plugs.getValue("Fridge Plug")["kwh"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(1.2, plugs.getValue("Kettle Plug")["kwh"]!!.jsonPrimitive.double, 1e-9)

        val l = plugs(t, lounge)
        assertEquals(1.2 + 1.0, l["kwh"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(true, l["incomplete"]!!.jsonPrimitive.boolean)
        assertEquals(10, l["missingMinutes"]!!.jsonPrimitive.int)
        val heater = l["plugs"]!!.jsonArray.single().jsonObject
        assertEquals(1.0, heater["kwh"]!!.jsonPrimitive.double, 1e-9)
        assertEquals("2026-10-12T01:10:00+01:00", heater["offline"]!!.jsonArray.single().jsonObject["from"]!!.jsonPrimitive.content)
        assertEquals("2026-10-12T01:20:00+01:00", heater["offline"]!!.jsonArray.single().jsonObject["to"]!!.jsonPrimitive.content)

        // Dashboard rooms agree.
        t.api { client ->
            val rooms = heatingJson.parseToJsonElement(client.get("/api/dashboard?period=today").bodyAsText()).jsonObject["rooms"]!!.jsonArray
                .associate { it.jsonObject["room"]!!.jsonPrimitive.content to it.jsonObject["kwh"]!!.jsonPrimitive.double }
            assertEquals(k["kwh"]!!.jsonPrimitive.double, rooms.getValue(kitchen), 1e-9)
            assertEquals(l["kwh"]!!.jsonPrimitive.double, rooms.getValue(lounge), 1e-9)
        }
    }
}
