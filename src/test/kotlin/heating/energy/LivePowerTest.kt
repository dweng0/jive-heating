package heating.energy

import heating.api.heatingModule
import heating.heatingJson
import heating.testing.TEST_TOKEN
import heating.testing.TestHeating
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import heating.testing.MutableClock
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LivePowerTest {

    private fun live(t: TestHeating, block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { heatingModule(t.app) }
        block(createClient { install(WebSockets) })
    }

    private suspend fun HttpClient.connect(): DefaultClientWebSocketSession =
        webSocketSession("/ws/power") { bearerAuth(TEST_TOKEN) }

    private suspend fun DefaultClientWebSocketSession.nextMessage(): JsonObject = withTimeout(5000) {
        var found: JsonObject? = null
        while (found == null) {
            val frame = incoming.receive()
            if (frame is Frame.Text) found = heatingJson.parseToJsonElement(frame.readText()).jsonObject
        }
        found
    }

    // BDD: The house power streams live
    @Test
    fun `the house power streams live`() {
        val t = TestHeating()
        t.discover(meters = listOf("Main Clamp"))
        t.api { client -> assertEquals(HttpStatusCode.OK, client.put("/api/meters/Main%20Clamp/house").status) }
        t.meterState("Main Clamp", """{"energy":1523.4,"power":1200}""")
        val readAt = t.clock.instant().toString()

        live(t) { client ->
            val socket = client.connect()
            val first = socket.nextMessage()
            assertEquals(1200.0, first["watts"]!!.jsonPrimitive.double, 1e-9)
            assertEquals(readAt, first["at"]!!.jsonPrimitive.content)

            t.meterState("Main Clamp", """{"energy":1523.5,"power":3400}""")
            assertEquals(3400.0, socket.nextMessage()["watts"]!!.jsonPrimitive.double, 1e-9)
            t.meterState("Main Clamp", """{"energy":1523.6,"power":90}""")
            assertEquals(90.0, socket.nextMessage()["watts"]!!.jsonPrimitive.double, 1e-9)
        }
    }

    /** The status a WebSocket handshake to /ws/power on [port] is refused with, signed in with [token] if any. */
    private fun refusal(port: Int, token: String?): Int {
        val builder = java.net.http.HttpClient.newHttpClient().newWebSocketBuilder()
        token?.let { builder.header("Authorization", "Bearer $it") }
        val e = assertFailsWith<ExecutionException> {
            builder.buildAsync(URI("ws://127.0.0.1:$port/ws/power"), object : WebSocket.Listener {}).get(5, TimeUnit.SECONDS)
        }
        return (e.cause as WebSocketHandshakeException).response.statusCode()
    }

    // BDD: The live power channel is admin only
    @Test
    fun `the live power channel is admin only`() {
        val t = TestHeating()
        t.discover(meters = listOf("Main Clamp"))
        var sam = ""
        testApplication {
            application { heatingModule(t.app) }
            sam = memberSam()
        }
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { heatingModule(t.app) }.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            assertEquals(403, refusal(port, sam))
            assertEquals(401, refusal(port, null))
        } finally {
            server.stop(0, 0)
        }
    }

    // BDD: A stale house reading is not shown as live
    @Test
    fun `a stale house reading is not shown as live`() {
        val t = TestHeating()
        t.discover(meters = listOf("Main Clamp"))
        t.api { client -> assertEquals(HttpStatusCode.OK, client.put("/api/meters/Main%20Clamp/house").status) }
        t.meterState("Main Clamp", """{"energy":1523.4,"power":1200}""")

        live(t) { client ->
            val socket = client.connect()
            assertEquals(1200.0, socket.nextMessage()["watts"]!!.jsonPrimitive.double, 1e-9)

            // Silence for two minutes: the channel says there is no live reading.
            t.clock.advance(Duration.ofSeconds(119))
            t.app.tick()
            t.clock.advance(Duration.ofSeconds(1))
            t.app.tick()
            val stale = socket.nextMessage()
            assertEquals(JsonNull, stale["watts"])
            // Said once, not on every tick.
            t.clock.advance(Duration.ofSeconds(5))
            t.app.tick()
            t.meterState("Main Clamp", """{"energy":1523.5,"power":800}""")
            assertEquals(800.0, socket.nextMessage()["watts"]!!.jsonPrimitive.double, 1e-9)
        }

        // A late joiner gets null too.
        t.clock.advance(Duration.ofMinutes(3))
        live(t) { client -> assertEquals(JsonNull, client.connect().nextMessage()["watts"]) }
    }

    private fun put(t: TestHeating, path: String) = t.api { client ->
        assertEquals(HttpStatusCode.OK, client.put(path.replace(" ", "%20")).status)
    }

    private fun power(t: TestHeating): JsonObject {
        var result = JsonObject(emptyMap())
        t.api { client ->
            val response = client.get("/api/power")
            assertEquals(HttpStatusCode.OK, response.status)
            result = heatingJson.parseToJsonElement(response.bodyAsText()).jsonObject
        }
        return result
    }

    // BDD: The dial colours follow the household's own use
    @Test
    fun `the dial colours follow the household's own use`() {
        // Midnight in London.
        val start = Instant.parse("2026-10-11T23:00:00Z")
        val t = TestHeating(clock = MutableClock(start))
        t.discover(meters = listOf("Main Clamp"))
        put(t, "/api/meters/Main Clamp/house")
        val history = t.app.energyHistory
        // kWh in five minutes for an average of [watts].
        fun sample(at: Instant, watts: Double?) = history.add(MeterSample(at, "Main Clamp", 1.0, watts?.let { it * 5 / 60_000 }, watts = watts))

        // Under a day of readings: one neutral colour.
        (1..12).forEach { sample(start.plus(Duration.ofMinutes(5L * it)), 300.0) }
        t.clock.advance(Duration.ofHours(1))
        assertEquals(JsonNull, power(t)["bands"])

        // Two days: nights (00:00-05:00) at 300 W, then 400 W; days from 1000 W up to 5000 W.
        val days = 2 * 24 * 12
        (13..days).forEach { i ->
            val at = start.plus(Duration.ofMinutes(5L * i))
            val hour = at.atZone(t.timezone).hour
            val watts = when {
                hour < 5 -> if (i % 2 == 0) 300.0 else 400.0
                else -> 1000.0 + (i % 100) * 40.0
            }
            sample(at, if (i % 50 == 0) null else watts)
        }
        t.clock.advance(Duration.ofHours(47))
        val bands = power(t)["bands"]!!.jsonObject
        val known = (1..days).filter { it % 50 != 0 }.map { i ->
            val at = start.plus(Duration.ofMinutes(5L * i))
            val night = at.atZone(t.timezone).hour < 5
            night to when {
                i <= 12 -> 300.0
                night -> if (i % 2 == 0) 300.0 else 400.0
                else -> 1000.0 + (i % 100) * 40.0
            }
        }
        // Green: the median overnight reading. Red: the 90th percentile of every known reading.
        val nights = known.filter { it.first }.map { it.second }.sorted()
        val median = if (nights.size % 2 == 1) nights[nights.size / 2] else (nights[nights.size / 2 - 1] + nights[nights.size / 2]) / 2
        assertEquals(median, bands["green"]!!.jsonPrimitive.double, 1e-6)
        val all = known.map { it.second }.sorted()
        assertEquals(all[kotlin.math.ceil(0.9 * all.size).toInt() - 1], bands["red"]!!.jsonPrimitive.double, 1e-6)
        assertTrue(bands["red"]!!.jsonPrimitive.double > bands["green"]!!.jsonPrimitive.double)

        // Readings older than seven days do not count.
        t.clock.advance(Duration.ofDays(8))
        assertEquals(JsonNull, power(t)["bands"])
    }

    // BDD: The dial shows today's use
    @Test
    fun `the dial shows the last 30 minutes of watts`() {
        val t = TestHeating()
        t.discover(meters = listOf("Main Clamp"))
        put(t, "/api/meters/Main Clamp/house")
        listOf(500, 700, 900, 1100).forEach { watts ->
            t.meterState("Main Clamp", """{"energy":1.0,"power":$watts}""")
            t.clock.advance(Duration.ofMinutes(10))
        }
        // The oldest reading is 40 minutes old now: only the last three are in the sparkline.
        val recent = power(t)["recent"]!!.jsonArray.map { it.jsonObject["watts"]!!.jsonPrimitive.double }
        assertEquals(listOf(700.0, 900.0, 1100.0), recent)
    }

    private fun roomWith(t: TestHeating): String {
        t.discover(meters = listOf("Main Clamp", "TV Plug", "Lamp Plug", "Fridge Plug", "Spare Plug"))
        put(t, "/api/meters/Main Clamp/house")
        listOf("TV Plug", "Lamp Plug", "Fridge Plug").forEach { put(t, "/api/meters/$it/monitored") }
        val lounge = t.app.rooms.create("Lounge").id
        t.app.rooms.create("Hall")
        put(t, "/api/rooms/$lounge/meters/TV Plug")
        put(t, "/api/rooms/$lounge/meters/Lamp Plug")
        return lounge
    }

    // BDD: Room cards show live and daily use
    @Test
    fun `room cards show live watts`() {
        val t = TestHeating()
        val lounge = roomWith(t)
        t.meterState("Main Clamp", """{"energy":1.0,"power":2000}""")
        t.meterState("TV Plug", """{"energy":1.0,"power":150}""")
        t.meterState("Lamp Plug", """{"energy":1.0,"power":50}""")
        t.meterState("Fridge Plug", """{"energy":1.0,"power":90}""")
        t.meterState("Spare Plug", """{"energy":1.0,"power":999}""")

        live(t) { client ->
            val socket = client.connect()
            val first = socket.nextMessage()
            // Only rooms with monitored plugs: the Hall has none.
            val rooms = first["rooms"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf(lounge), rooms.map { it["room"]!!.jsonPrimitive.content })
            assertEquals("Lounge", rooms[0]["name"]!!.jsonPrimitive.content)
            assertEquals(200.0, rooms[0]["watts"]!!.jsonPrimitive.double, 1e-9)
            // A monitored device in no room is listed on its own; an unmonitored plug is not.
            val devices = first["devices"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("Fridge Plug"), devices.map { it["meter"]!!.jsonPrimitive.content })
            assertEquals(90.0, devices[0]["watts"]!!.jsonPrimitive.double, 1e-9)

            // A plug's reading is pushed too.
            t.meterState("TV Plug", """{"energy":1.0,"power":350}""")
            val next = socket.nextMessage()
            assertEquals(400.0, next["rooms"]!!.jsonArray[0].jsonObject["watts"]!!.jsonPrimitive.double, 1e-9)
            // An unmonitored plug's reading is not.
            t.meterState("Spare Plug", """{"energy":1.0,"power":5}""")
            t.meterState("Main Clamp", """{"energy":1.0,"power":2100}""")
            assertEquals(2100.0, socket.nextMessage()["watts"]!!.jsonPrimitive.double, 1e-9)
        }

        // A plug that went offline adds nothing live; a room with no live plug has watts null.
        t.availability("TV Plug", online = false)
        t.availability("Lamp Plug", online = false)
        live(t) { client ->
            assertEquals(JsonNull, client.connect().nextMessage()["rooms"]!!.jsonArray[0].jsonObject["watts"])
        }
    }

    // BDD: Everything else is the unattributed remainder
    @Test
    fun `everything else is the unattributed remainder`() {
        val t = TestHeating()
        roomWith(t)
        t.meterState("Main Clamp", """{"energy":1.0,"power":2000}""")
        t.meterState("TV Plug", """{"energy":1.0,"power":700}""")
        t.meterState("Lamp Plug", """{"energy":1.0,"power":100}""")
        t.meterState("Fridge Plug", """{"energy":1.0,"power":400}""")

        live(t) { client ->
            val socket = client.connect()
            assertEquals(800.0, socket.nextMessage()["elseWatts"]!!.jsonPrimitive.double, 1e-9)
            // Plugs and clamp disagree for a moment: never below 0.
            t.meterState("Main Clamp", """{"energy":1.0,"power":1000}""")
            assertEquals(0.0, socket.nextMessage()["elseWatts"]!!.jsonPrimitive.double, 1e-9)
        }
        // Not shown while the house reading is stale.
        t.clock.advance(Duration.ofMinutes(3))
        live(t) { client -> assertEquals(JsonNull, client.connect().nextMessage()["elseWatts"]) }
    }

    // BDD: The dial colours come only from recorded power
    @Test
    fun `the dial colours come only from recorded power`() {
        val start = Instant.parse("2026-10-11T23:00:00Z")
        val t = TestHeating(clock = MutableClock(start))
        t.discover(meters = listOf("Main Clamp"))
        put(t, "/api/meters/Main Clamp/house")
        val history = t.app.energyHistory
        // Three days of samples from before average power was kept: kWh only.
        (1..3 * 288).forEach { i -> history.add(MeterSample(start.plus(Duration.ofMinutes(5L * i)), "Main Clamp", 1.0, 0.0)) }
        t.clock.advance(Duration.ofDays(3))
        assertEquals(JsonNull, power(t)["bands"])

        // Half a day with average power: still neutral.
        val from = t.clock.instant()
        (0..144).forEach { i -> history.add(MeterSample(from.plus(Duration.ofMinutes(5L * i)), "Main Clamp", 1.0, 0.05, watts = 600.0)) }
        t.clock.advance(Duration.ofHours(12))
        assertEquals(JsonNull, power(t)["bands"])

        // A full day: the bands come from the power samples only, never the old zeros.
        (145..288).forEach { i -> history.add(MeterSample(from.plus(Duration.ofMinutes(5L * i)), "Main Clamp", 1.0, 0.05, watts = 600.0)) }
        t.clock.advance(Duration.ofHours(12))
        val bands = power(t)["bands"]!!.jsonObject
        assertEquals(600.0, bands["green"]!!.jsonPrimitive.double, 1e-6)
        assertEquals(600.0, bands["red"]!!.jsonPrimitive.double, 1e-6)
    }
}
