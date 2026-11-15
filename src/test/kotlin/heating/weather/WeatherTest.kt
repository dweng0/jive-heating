package heating.weather

import com.sun.net.httpserver.HttpServer
import heating.Config
import heating.api.heatingModule
import heating.heatingJson
import heating.testing.TEST_TOKEN
import heating.testing.TestHeating
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherTest {

    /** A weather source for tests: says [reading], or throws when [failing]; counts how often it is asked. */
    private class FakeSource(var reading: WeatherReading?) : WeatherSource {
        var asked = 0
        var failing = false
        override fun current(): WeatherReading? {
            asked++
            if (failing) throw java.io.IOException("timed out")
            return reading
        }
    }

    // BDD: The service asks the weather source every five minutes
    @Test
    fun `the service asks the weather source every five minutes`() {
        val source = FakeSource(WeatherReading(Condition.PARTLY_CLOUDY, temperatureC = 9.5))
        val t = TestHeating(weatherSource = source)
        t.app.tick()
        assertEquals(1, source.asked)
        assertEquals("cloudy", t.app.weather.now()!!.look)
        for (minute in 1..4) { t.clock.advance(Duration.ofMinutes(1)); t.app.tick() }
        assertEquals(1, source.asked, "not on every tick")
        t.clock.advance(Duration.ofMinutes(1)); t.app.tick()
        assertEquals(2, source.asked)

        // Without a source it never asks, and there is no weather.
        val none = TestHeating()
        none.app.tick()
        assertNull(none.app.weather.now())
    }

    // BDD: A reading becomes the look the Live picture draws
    @Test
    fun `a reading becomes the look the live picture draws`() {
        val cases = listOf(
            Condition.CLEAR to 8.0 to "clear", Condition.PARTLY_CLOUDY to 8.0 to "cloudy", Condition.CLOUDY to 8.0 to "overcast",
            Condition.RAIN to 8.0 to "rain", Condition.SNOW to -1.0 to "snow", Condition.CLEAR to -2.0 to "frost",
            Condition.PARTLY_CLOUDY to 0.0 to "frost", Condition.CLOUDY to -3.0 to "overcast",
        )
        for ((input, look) in cases) assertEquals(look, lookOf(WeatherReading(input.first, temperatureC = input.second)), "$input")
        assertEquals("clear", lookOf(WeatherReading(Condition.CLEAR)), "no temperature: no frost")
    }

    // BDD: Home Assistant is the first weather source
    @Test
    fun `home assistant is the first weather source`() {
        var state = "rainy"
        var auth: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/states/weather.forecast_home") { ex ->
            auth = ex.requestHeaders.getFirst("Authorization")
            val body = """{"entity_id":"weather.forecast_home","state":"$state","attributes":{"temperature":7.5,"cloud_coverage":90,"temperature_unit":"°C"},"last_updated":"2026-11-15T09:00:00+00:00"}"""
            ex.sendResponseHeaders(200, body.toByteArray().size.toLong()); ex.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val ha = HomeAssistantWeather("http://127.0.0.1:${server.address.port}", "ha-token", "weather.forecast_home")
            assertEquals(WeatherReading(Condition.RAIN, 90, 7.5, Instant.parse("2026-11-15T09:00:00Z")), ha.current())
            assertEquals("Bearer ha-token", auth)
            val mapping = mapOf(
                "sunny" to Condition.CLEAR, "clear-night" to Condition.CLEAR, "partlycloudy" to Condition.PARTLY_CLOUDY,
                "cloudy" to Condition.CLOUDY, "fog" to Condition.CLOUDY, "rainy" to Condition.RAIN, "pouring" to Condition.RAIN,
                "lightning-rainy" to Condition.RAIN, "hail" to Condition.RAIN, "snowy" to Condition.SNOW, "snowy-rainy" to Condition.SNOW,
            )
            for ((s, c) in mapping) { state = s; assertEquals(c, ha.current()!!.condition, s) }
            // Anything else keeps the last known condition.
            state = "windy"
            assertEquals(Condition.SNOW, ha.current()!!.condition)
        } finally {
            server.stop(0)
        }

        // Chosen in the environment; a missing setting stops the service starting, naming it.
        val env = mapOf("HEATING_API_TOKEN" to "t", "WEATHER_SOURCE" to "homeassistant", "HA_URL" to "http://ha:8123", "HA_TOKEN" to "x", "HA_WEATHER_ENTITY" to "weather.forecast_home")
        assertTrue(Config.fromEnv(env).weatherSource() is HomeAssistantWeather)
        val missing = assertFailsWith<IllegalStateException> { Config.fromEnv(env - "HA_TOKEN") }
        assertTrue("HA_TOKEN" in missing.message!!, missing.message)
        assertFailsWith<IllegalStateException> { Config.fromEnv(env + ("WEATHER_SOURCE" to "crystal-ball")) }
        assertNull(Config.fromEnv(mapOf("HEATING_API_TOKEN" to "t")).weatherSource())
    }

    // BDD: Live power carries the weather
    @Test
    fun `live power carries the weather`() {
        val source = FakeSource(WeatherReading(Condition.RAIN, temperatureC = 7.5))
        val t = TestHeating(weatherSource = source)
        t.discover(meters = listOf("Main Clamp"))
        t.api { client -> assertEquals(HttpStatusCode.OK, client.put("/api/meters/Main%20Clamp/house").status) }
        t.meterState("Main Clamp", """{"energy":1.0,"power":500}""")
        t.app.tick()

        testApplication {
            application { heatingModule(t.app) }
            val socket = createClient { install(WebSockets) }.webSocketSession("/ws/power") { bearerAuth(TEST_TOKEN) }
            suspend fun next(): JsonObject = withTimeout(5000) {
                var found: JsonObject? = null
                while (found == null) { val f = socket.incoming.receive(); if (f is Frame.Text) found = heatingJson.parseToJsonElement(f.readText()).jsonObject }
                found
            }
            val weather = next()["weather"]!!.jsonObject
            assertEquals("rain", weather["look"]!!.jsonPrimitive.content)
            assertEquals(7.5, weather["temperatureC"]!!.jsonPrimitive.double, 1e-9)
            assertNotNull(weather["at"])

            // A change of look is sent straight away.
            source.reading = WeatherReading(Condition.SNOW, temperatureC = -1.0)
            t.clock.advance(Duration.ofMinutes(5)); t.app.tick()
            // (The house reading going stale in the same five minutes may send its own message first.)
            val looks = List(2) { next()["weather"]!!.jsonObject["look"]!!.jsonPrimitive.content }
            assertTrue("snow" in looks, "$looks")
        }

        // No reading in the last 30 minutes: null.
        source.failing = true
        for (i in 1..7) { t.clock.advance(Duration.ofMinutes(5)); t.app.tick() }
        assertNull(t.app.weather.now())
        assertNull(t.app.livePower.now().weather)
        assertEquals(JsonNull, heatingJson.encodeToJsonElement(heating.energy.LivePowerView.serializer(), TestHeating().app.livePower.now()).jsonObject["weather"] ?: JsonNull)
    }

    // BDD: A failing weather source does not stop live power
    @Test
    fun `a failing weather source does not stop live power`() {
        val source = FakeSource(WeatherReading(Condition.CLEAR, temperatureC = 12.0))
        val t = TestHeating(weatherSource = source)
        t.app.tick()
        source.failing = true
        t.clock.advance(Duration.ofMinutes(5)); t.app.tick()
        // The last reading keeps showing, and live power still answers.
        assertEquals("clear", t.app.weather.now()!!.look)
        assertNotNull(t.app.livePower.now())
        assertTrue(t.app.weather.failing)
        t.clock.advance(Duration.ofMinutes(26)); t.app.tick()
        assertNull(t.app.weather.now(), "30 minutes old")
        // Working again: it recovers.
        source.failing = false
        t.clock.advance(Duration.ofMinutes(5)); t.app.tick()
        assertEquals("clear", t.app.weather.now()!!.look)
        assertTrue(!t.app.weather.failing)
    }
}
