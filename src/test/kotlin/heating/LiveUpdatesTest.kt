package heating

import heating.api.heatingModule
import heating.testing.TEST_TOKEN
import heating.testing.TestHeating
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LiveUpdatesTest {

    private fun live(t: TestHeating, block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { heatingModule(t.app) }
        block(createClient { install(WebSockets) })
    }

    private suspend fun HttpClient.connect(): DefaultClientWebSocketSession =
        webSocketSession("/ws") { bearerAuth(TEST_TOKEN) }

    /** Next event of [type] for which [match] holds; skips others. */
    private suspend fun DefaultClientWebSocketSession.next(
        type: String,
        match: (JsonObject) -> Boolean = { true },
    ): JsonObject = withTimeout(5000) {
        var found: JsonObject? = null
        while (found == null) {
            val frame = incoming.receive()
            if (frame is Frame.Text) {
                val event = heatingJson.parseToJsonElement(frame.readText()).jsonObject
                if (event["type"]!!.jsonPrimitive.content == type && match(event)) found = event
            }
        }
        found
    }

    private fun JsonObject.room() = this["room"]!!.jsonObject

    // BDD: WebSocket requires the bearer token
    @Test
    fun `websocket requires the bearer token`() {
        val t = TestHeating()
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { heatingModule(t.app) }.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            val e = assertFailsWith<ExecutionException> {
                java.net.http.HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI("ws://127.0.0.1:$port/ws"), object : WebSocket.Listener {})
                    .get(5, TimeUnit.SECONDS)
            }
            val handshake = e.cause as WebSocketHandshakeException
            assertEquals(401, handshake.response.statusCode())
        } finally {
            server.stop(0, 0)
        }
    }

    // BDD: Room change is pushed to connected clients
    @Test
    fun `room change is pushed to both connected clients`() {
        val t = TestHeating()
        t.discover("Lounge TRV 1")
        val room = t.app.rooms.create("Lounge")
        t.app.rooms.assignValve(room.id, "Lounge TRV 1")
        live(t) { client ->
            val one = client.connect()
            val two = client.connect()
            val response = client.put("/api/zones/${room.id}/mode") {
                bearerAuth(TEST_TOKEN)
                contentType(ContentType.Application.Json)
                setBody("""{"mode":"manual","target":21.0}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            for (ws in listOf(one, two)) {
                val event = ws.next("room.updated") {
                    it.room()["name"]!!.jsonPrimitive.content == "Lounge" &&
                        it.room()["target"]!!.jsonPrimitive.double == 21.0
                }
                assertEquals(21.0, event.room()["target"]!!.jsonPrimitive.double)
            }
        }
    }

    // BDD: Valve temperature change is pushed
    @Test
    fun `valve temperature change is pushed as a room update`() {
        val t = TestHeating()
        t.discover("Lounge TRV 1")
        val room = t.app.rooms.create("Lounge")
        t.app.rooms.assignValve(room.id, "Lounge TRV 1")
        live(t) { client ->
            val ws = client.connect()
            t.valveState("Lounge TRV 1", """{"local_temperature":20.0}""")
            val event = ws.next("room.updated") {
                it.room()["name"]!!.jsonPrimitive.content == "Lounge" &&
                    it.room()["current"]?.jsonPrimitive?.content == "20.0"
            }
            assertEquals(20.0, event.room()["current"]!!.jsonPrimitive.double)
        }
    }

    // Supporting behaviour (not a BDD scenario): status contract.
    @Test
    fun `status endpoint and status events carry the contract fields`() {
        val t = TestHeating()
        live(t) { client ->
            val status = heatingJson.parseToJsonElement(
                client.get("/api/status") { bearerAuth(TEST_TOKEN) }.bodyAsText(),
            ).jsonObject
            assertEquals("false", status["heatDemand"]!!.jsonPrimitive.content)
            assertEquals("Europe/London", status["timezone"]!!.jsonPrimitive.content)
            assertEquals("2026-10-12T10:00:00+01:00", status["serverTime"]!!.jsonPrimitive.content)
            assertTrue(status["holiday"] is JsonNull)

            val ws = client.connect()
            t.app.changes.emit(Change.StatusChanged)
            val event = ws.next("status.updated")
            assertEquals("Europe/London", event["status"]!!.jsonObject["timezone"]!!.jsonPrimitive.content)
        }
    }
}
