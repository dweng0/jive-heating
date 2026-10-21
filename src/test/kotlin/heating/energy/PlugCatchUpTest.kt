package heating.energy

import heating.heatingJson
import heating.testing.MutableClock
import heating.testing.TestHeating
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class PlugCatchUpTest {
    private val zone = ZoneId.of("Europe/London")

    private fun at(local: String): Instant = LocalDateTime.parse(local).atZone(zone).toInstant()

    private class Setup(val t: TestHeating, val lounge: String)

    /** "TV Plug" monitored in the Lounge, the clock at [start] (local time). */
    private fun setUp(start: String): Setup {
        val t = TestHeating(clock = MutableClock(at(start)))
        t.discover(meters = listOf("TV Plug"))
        val lounge = t.app.rooms.create("Lounge").id
        t.api { client ->
            assertEquals(HttpStatusCode.OK, client.request("/api/meters/TV%20Plug/monitored") { method = HttpMethod.Put }.status)
            assertEquals(HttpStatusCode.OK, client.request("/api/rooms/$lounge/meters/TV%20Plug") { method = HttpMethod.Put }.status)
        }
        return Setup(t, lounge)
    }

    /** Ticks every five minutes until [to] (local time); before each tick the plug reports [energy], if given. */
    private fun until(t: TestHeating, to: String, energy: Double? = null) {
        while (t.clock.instant() < at(to)) {
            t.clock.advance(Duration.ofMinutes(5))
            if (energy != null) t.meterState("TV Plug", """{"energy":$energy,"power":80}""")
            t.app.tick()
        }
    }

    /** Reads 12.40 kWh up to [offlineAt], then goes offline. */
    private fun readsThenGoesOffline(t: TestHeating, offlineAt: String) {
        until(t, offlineAt, energy = 12.40)
        t.availability("TV Plug", online = false)
    }

    private fun lastSample(t: TestHeating) = t.app.energyHistory.deviceSamples("TV Plug", Instant.EPOCH, t.clock.instant()).last()

    private fun get(t: TestHeating, path: String): JsonObject {
        var result = JsonObject(emptyMap())
        t.api { client ->
            val response = client.get(path)
            assertEquals(HttpStatusCode.OK, response.status)
            result = heatingJson.parseToJsonElement(response.bodyAsText()).jsonObject
        }
        return result
    }

    private fun plug(t: TestHeating, room: String) = get(t, "/api/rooms/$room/plugs")["plugs"]!!.jsonArray.single().jsonObject

    private fun dashboardDevice(t: TestHeating, period: String) =
        get(t, "/api/dashboard?period=$period")["devices"]!!.jsonArray.map { it.jsonObject }.single { it["meter"]!!.jsonPrimitive.content == "TV Plug" }

    private fun dashboardRoom(t: TestHeating, period: String, room: String) =
        get(t, "/api/dashboard?period=$period")["rooms"]!!.jsonArray.map { it.jsonObject }.single { it["room"]!!.jsonPrimitive.content == room }

    // BDD: A plug that comes back catches up on its use
    @Test
    fun `a plug that comes back the same day catches up and nothing is missing`() {
        val (t, lounge) = setUp("2026-10-12T13:00:00").let { it.t to it.lounge }
        readsThenGoesOffline(t, "2026-10-12T14:20:00")
        until(t, "2026-10-12T16:00:00")
        // It comes back at 16:00, so the five minutes to 16:05 catch up on its use.
        t.meterState("TV Plug", """{"energy":12.90,"power":80}""")
        until(t, "2026-10-12T16:05:00")

        val sample = lastSample(t)
        assertEquals(at("2026-10-12T16:05:00"), sample.at)
        assertEquals(0.5, sample.kwh!!, 1e-9)

        val plug = plug(t, lounge)
        assertEquals(0.5, plug["kwh"]!!.jsonPrimitive.double, 1e-9)
        // 0.5 kWh at the default 27p per kWh.
        assertEquals(13.5, plug["cost"]!!.jsonPrimitive.double, 1e-9)
        // Nothing is missing, so no triangle.
        assertEquals(false, plug["incomplete"]!!.jsonPrimitive.boolean)
        assertEquals(0, plug["missingMinutes"]!!.jsonPrimitive.int)
        assertEquals(0, plug["offline"]!!.jsonArray.size)

        val device = dashboardDevice(t, "today")
        assertEquals(0.5, device["kwh"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(13.5, device["cost"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(false, device["incomplete"]!!.jsonPrimitive.boolean)
        assertEquals(false, dashboardRoom(t, "today", lounge)["incomplete"]!!.jsonPrimitive.boolean)
    }

    // BDD: A plug that comes back catches up on its use
    @Test
    fun `a plug that comes back reset loses its use while offline and keeps the triangle`() {
        val (t, lounge) = setUp("2026-10-12T13:00:00").let { it.t to it.lounge }
        readsThenGoesOffline(t, "2026-10-12T14:20:00")
        until(t, "2026-10-12T16:00:00")
        // It comes back reading lower than 12.40: its total was reset.
        t.meterState("TV Plug", """{"energy":0.3,"power":80}""")
        until(t, "2026-10-12T16:05:00")
        assertEquals(0.0, lastSample(t).kwh!!, 1e-9)
        // The new reading counts on from there.
        t.meterState("TV Plug", """{"energy":0.4,"power":80}""")
        until(t, "2026-10-12T16:10:00")
        assertEquals(0.1, lastSample(t).kwh!!, 1e-9)

        val plug = plug(t, lounge)
        assertEquals(0.1, plug["kwh"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(true, plug["incomplete"]!!.jsonPrimitive.boolean)
        // "Was offline 14:20 to 16:00. Some use could not be counted."
        val offline = plug["offline"]!!.jsonArray.single().jsonObject
        assertEquals("2026-10-12T14:20:00+01:00", offline["from"]!!.jsonPrimitive.content)
        assertEquals("2026-10-12T16:00:00+01:00", offline["to"]!!.jsonPrimitive.content)
        assertEquals(100, plug["missingMinutes"]!!.jsonPrimitive.int)
        assertEquals(true, dashboardDevice(t, "today")["incomplete"]!!.jsonPrimitive.boolean)
    }

    // BDD: A plug that comes back catches up on its use
    @Test
    fun `a plug offline past midnight catches up on the day it came back and both days keep the triangle`() {
        val (t, lounge) = setUp("2026-10-12T22:00:00").let { it.t to it.lounge }
        readsThenGoesOffline(t, "2026-10-12T23:20:00")
        until(t, "2026-10-13T00:30:00")
        t.meterState("TV Plug", """{"energy":12.90,"power":80}""")
        until(t, "2026-10-13T00:35:00")
        assertEquals(0.5, lastSample(t).kwh!!, 1e-9)

        val today = dashboardDevice(t, "today")
        assertEquals(0.5, today["kwh"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(true, today["incomplete"]!!.jsonPrimitive.boolean)
        val yesterday = dashboardDevice(t, "yesterday")
        assertEquals(0.0, yesterday["kwh"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(true, yesterday["incomplete"]!!.jsonPrimitive.boolean)
        assertEquals(true, dashboardRoom(t, "yesterday", lounge)["incomplete"]!!.jsonPrimitive.boolean)

        val plug = plug(t, lounge)
        assertEquals(0.5, plug["kwh"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(true, plug["incomplete"]!!.jsonPrimitive.boolean)
        val offline = plug["offline"]!!.jsonArray.single().jsonObject
        assertEquals("2026-10-13T00:00:00+01:00", offline["from"]!!.jsonPrimitive.content)
        assertEquals("2026-10-13T00:30:00+01:00", offline["to"]!!.jsonPrimitive.content)
    }
}
