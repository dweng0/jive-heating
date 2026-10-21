package heating.demand

import heating.Change
import heating.Config
import heating.api.heatingModule
import heating.heatingJson
import heating.rooms.Mode
import heating.testing.TEST_TOKEN
import heating.testing.TestHeating
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class BoilerStateTest {

    private fun lounge(t: TestHeating) {
        t.discover("Lounge TRV 1")
        val room = t.app.rooms.create("Lounge")
        t.app.rooms.assignValve(room.id, "Lounge TRV 1")
        t.app.zones.setMode(room.id, Mode.MANUAL, 21.0)
    }

    private fun temp(t: TestHeating, degrees: Double) =
        t.valveState("Lounge TRV 1", """{"local_temperature":$degrees}""")

    private fun minutes(t: TestHeating, n: Long) {
        t.clock.advance(Duration.ofMinutes(n))
        t.app.tick()
    }

    private fun boiler(t: TestHeating): JsonObject {
        var result: JsonObject? = null
        t.api { client ->
            result = heatingJson.parseToJsonElement(client.get("/api/status").bodyAsText()).jsonObject["boiler"]!!.jsonObject
        }
        return result!!
    }

    private fun JsonObject.state() = this["state"]!!.jsonPrimitive.content
    private fun JsonObject.until(): Instant? =
        this["until"].takeUnless { it == null || it is JsonNull }?.jsonPrimitive?.content?.let { OffsetDateTime.parse(it).toInstant() }

    // BDD: Boiler state reads heating while a zone calls for heat
    @Test
    fun `boiler state reads heating while a zone calls for heat`() {
        val t = TestHeating()
        lounge(t)
        temp(t, 20.0)
        val boiler = boiler(t)
        assertEquals("heating", boiler.state())
        assertEquals(JsonNull, boiler["until"])
    }

    // BDD: Boiler is cycling while it finishes its minimum run
    @Test
    fun `boiler is cycling while it finishes its minimum run`() {
        val t = TestHeating()
        lounge(t)
        temp(t, 20.0)
        val turnedOn = t.clock.instant()
        minutes(t, 1)
        temp(t, 21.0)
        val boiler = boiler(t)
        assertEquals("cycling", boiler.state())
        assertEquals(turnedOn.plus(Duration.ofMinutes(4)), boiler.until())
        minutes(t, 3)
        assertEquals("idle", boiler(t).state())
    }

    // BDD: Boiler is resting while it waits out its minimum rest
    @Test
    fun `boiler is resting while it waits out its minimum rest`() {
        val t = TestHeating()
        lounge(t)
        temp(t, 20.0)
        minutes(t, 4)
        temp(t, 21.0)
        val turnedOff = t.clock.instant()
        assertEquals(false, t.app.demand.demand)
        minutes(t, 2)
        temp(t, 20.0)
        val boiler = boiler(t)
        assertEquals("resting", boiler.state())
        assertEquals(turnedOff.plus(Duration.ofMinutes(5)), boiler.until())
        minutes(t, 3)
        assertEquals("heating", boiler(t).state())
    }

    // BDD: Boiler is idle when nothing calls for heat
    @Test
    fun `boiler is idle when nothing calls for heat`() {
        val t = TestHeating()
        lounge(t)
        temp(t, 22.0)
        minutes(t, 60)
        val boiler = boiler(t)
        assertEquals("idle", boiler.state())
        assertEquals(JsonNull, boiler["until"])
    }

    // BDD: Boiler state changes are pushed to connected clients
    @Test
    fun `boiler state changes are pushed to connected clients`() {
        val t = TestHeating()
        lounge(t)
        testApplication {
            application { heatingModule(t.app) }
            val ws = createClient { install(WebSockets) }.webSocketSession("/ws") { bearerAuth(TEST_TOKEN) }
            // The server subscribes the socket just after the handshake; wait until it hears changes.
            withTimeout(5000) {
                while (true) {
                    t.app.changes.emit(Change.StatusChanged)
                    val frame = withTimeoutOrNull(200) { ws.incoming.receive() }
                    if (frame is Frame.Text && frame.readText().contains("status.updated")) break
                }
            }
            suspend fun nextBoilerState(): String = withTimeout(5000) {
                var state: String? = null
                while (state == null) {
                    val frame = ws.incoming.receive()
                    if (frame !is Frame.Text) continue
                    val event = heatingJson.parseToJsonElement(frame.readText()).jsonObject
                    if (event["type"]?.jsonPrimitive?.content == "status.updated") {
                        state = event["status"]!!.jsonObject["boiler"]!!.jsonObject["state"]!!.jsonPrimitive.content
                    }
                }
                state!!
            }
            temp(t, 20.0)
            assertEquals("heating", nextBoilerState())
            temp(t, 21.0)
            assertEquals("cycling", nextBoilerState())
            minutes(t, 4)
            assertEquals("idle", nextBoilerState())
            ws.close()
        }
    }

    // BDD: Minimum run and rest times come from configuration
    @Test
    fun `minimum run and rest times come from configuration`() {
        val env = mapOf("HEATING_API_TOKEN" to "x", "BOILER_MIN_RUN_MINUTES" to "6", "BOILER_MIN_REST_MINUTES" to "3")
        val configured = Config.fromEnv(env)
        assertEquals(Duration.ofMinutes(6), configured.boilerMinRun)
        assertEquals(Duration.ofMinutes(3), configured.boilerMinRest)
        val defaults = Config.fromEnv(mapOf("HEATING_API_TOKEN" to "x"))
        assertEquals(Duration.ofMinutes(4), defaults.boilerMinRun)
        assertEquals(Duration.ofMinutes(5), defaults.boilerMinRest)

        val t = TestHeating(configure = { it.copy(boilerMinRun = configured.boilerMinRun, boilerMinRest = configured.boilerMinRest) })
        lounge(t)
        temp(t, 20.0)
        minutes(t, 1)
        temp(t, 21.0)
        minutes(t, 4)
        assertEquals(true, t.app.demand.demand)
        minutes(t, 1)
        assertEquals(false, t.app.demand.demand)
    }
}
