package heating.demand

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
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class BoilerDemandTest {

    private fun lounge(t: TestHeating): String {
        t.discover("Lounge TRV 1")
        val room = t.app.rooms.create("Lounge")
        t.app.rooms.assignValve(room.id, "Lounge TRV 1")
        t.app.zones.setMode(room.id, Mode.MANUAL, 21.0)
        return room.id
    }

    private fun temp(t: TestHeating, degrees: Double) =
        t.valveState("Lounge TRV 1", """{"local_temperature":$degrees}""")

    private fun demandMessages(t: TestHeating) =
        t.bus.published.filter { it.topic == "heating/demand" }

    private fun minutes(t: TestHeating, n: Long) {
        t.clock.advance(Duration.ofMinutes(n))
        t.app.tick()
    }

    // BDD: Any calling zone turns heat demand on
    @Test
    fun `any calling zone turns heat demand on`() {
        val t = TestHeating()
        lounge(t)
        temp(t, 21.0)
        minutes(t, 10)
        t.bus.clearPublished()
        temp(t, 20.0)
        val sent = demandMessages(t).single()
        assertEquals("""{"demand":true}""", sent.payload)
        assertEquals(true, sent.retained)
    }

    // BDD: Demand stays on for the minimum run time
    @Test
    fun `demand stays on for the minimum run time`() {
        val t = TestHeating()
        lounge(t)
        temp(t, 20.0)
        minutes(t, 2)
        temp(t, 21.0)
        minutes(t, 0)
        assertEquals("""{"demand":true}""", demandMessages(t).last().payload)
        minutes(t, 1)
        assertEquals("""{"demand":true}""", demandMessages(t).last().payload)
        minutes(t, 1)
        assertEquals("""{"demand":false}""", demandMessages(t).last().payload)
    }

    // BDD: Demand stays off for the minimum rest time
    @Test
    fun `demand stays off for the minimum rest time`() {
        val t = TestHeating()
        lounge(t)
        temp(t, 20.0)
        minutes(t, 10)
        temp(t, 21.0)
        minutes(t, 0)
        assertEquals("""{"demand":false}""", demandMessages(t).last().payload)
        minutes(t, 2)
        temp(t, 20.0)
        assertEquals("""{"demand":false}""", demandMessages(t).last().payload)
        minutes(t, 2)
        assertEquals("""{"demand":false}""", demandMessages(t).last().payload)
        minutes(t, 1)
        assertEquals("""{"demand":true}""", demandMessages(t).last().payload)
    }

    // BDD: Demand is republished on startup
    @Test
    fun `demand is republished on startup`() {
        val t = TestHeating()
        t.bus.clearPublished()
        t.restart()
        val sent = demandMessages(t).single()
        assertEquals("""{"demand":false}""", sent.payload)
        assertEquals(true, sent.retained)
    }

    // BDD: Heat demand is visible in the API
    @Test
    fun `heat demand is visible in the API`() {
        val t = TestHeating()
        lounge(t)
        temp(t, 20.0)
        t.api { client ->
            val status = heatingJson.parseToJsonElement(client.get("/api/status").bodyAsText()).jsonObject
            assertEquals(true, status["heatDemand"]!!.jsonPrimitive.boolean)
        }
    }

    // BDD: Heat demand change is pushed
    @Test
    fun `heat demand change is pushed`() {
        val t = TestHeating()
        lounge(t)
        temp(t, 21.0)
        minutes(t, 10)
        testApplication {
            application { heatingModule(t.app) }
            val client = createClient { install(WebSockets) }
            val ws = client.webSocketSession("/ws") { bearerAuth(TEST_TOKEN) }
            client.get("/api/status") { bearerAuth(TEST_TOKEN) } // round trip: lets the server register the socket
            temp(t, 20.0)
            val event = withTimeout(5000) {
                var found: JsonObject? = null
                while (found == null) {
                    val frame = ws.incoming.receive()
                    if (frame is Frame.Text) {
                        val e = heatingJson.parseToJsonElement(frame.readText()).jsonObject
                        if (e["type"]!!.jsonPrimitive.content == "status.updated") found = e
                    }
                }
                found
            }
            assertEquals(true, event["status"]!!.jsonObject["heatDemand"]!!.jsonPrimitive.boolean)
        }
    }
}
