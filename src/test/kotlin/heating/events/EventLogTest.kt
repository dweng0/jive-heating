package heating.events

import heating.Config
import heating.heatingJson
import heating.testing.TEST_TOKEN
import heating.testing.TestHeating
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventLogTest {

    /** A service whose event log is written only when asked, so tests decide when the queue is drained. */
    private fun service(maxBytes: Long = EventRecorder.MAX_BYTES) =
        TestHeating(configure = { it.copy(eventFlushMillis = 0, eventMaxBytes = maxBytes) })

    private suspend fun ApplicationTestBuilder.get(path: String, token: String = TEST_TOKEN): HttpResponse = client.get(path) { bearerAuth(token) }

    private suspend fun ApplicationTestBuilder.getJson(path: String): JsonObject {
        val response = get(path)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return heatingJson.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private suspend fun ApplicationTestBuilder.putSettings(body: String, token: String = TEST_TOKEN): HttpResponse =
        client.put("/api/events/settings") {
            contentType(ContentType.Application.Json)
            setBody(body)
            bearerAuth(token)
        }

    /** The topics of a page of events, in order. */
    private fun JsonObject.topics() = this["events"]!!.jsonArray.map { it.jsonObject["topic"]!!.jsonPrimitive.content }

    /** A signed-in member's token (jane is the admin who set the household up). */
    private suspend fun ApplicationTestBuilder.memberToken(): String {
        val jane = token(client.post("/api/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody("""{"serviceToken":"$TEST_TOKEN","username":"jane","password":"correct horse"}""")
        })
        client.post("/api/admin/users") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"sam","password":"correct horse","admin":false}""")
            bearerAuth(jane)
        }
        return token(client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"sam","password":"correct horse"}""")
        })
    }

    private suspend fun token(response: HttpResponse) = heatingJson.parseToJsonElement(response.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content

    // BDD: Every message on the broker is logged
    @Test
    fun `every message on the broker is logged`() {
        val t = service()
        t.bus.receive("z2m/front_door", """{"contact":false,"battery":91}""")
        t.bus.receive("garage/temp", "18.5")
        t.api {
            val events = getJson("/api/events")["events"]!!.jsonArray.map { it.jsonObject }.associateBy { it["topic"]!!.jsonPrimitive.content }
            val door = events.getValue("z2m/front_door")
            assertEquals("2026-10-12T09:00:00Z", door["at"]!!.jsonPrimitive.content)
            assertEquals(false, door["retained"]!!.jsonPrimitive.boolean)
            assertEquals(true, door["json"]!!.jsonPrimitive.boolean)
            assertEquals(91, door["payload"]!!.jsonObject["battery"]!!.jsonPrimitive.int)
            val temp = events.getValue("garage/temp")
            assertEquals(false, temp["json"]!!.jsonPrimitive.boolean)
            assertEquals("18.5", temp["payload"]!!.jsonPrimitive.content)
            assertTrue(temp["payload"]!!.jsonPrimitive.isString)
        }
    }

    // BDD: Every message on the broker is logged
    @Test
    fun `retained messages delivered on connect are logged once flagged retained`() {
        val t = service()
        t.bus.receive("home/mode", "away", retained = true)
        t.restart() // a new connection: the broker replays the retained message once
        t.app.events.flush()
        val found = t.app.eventLog.search(EventQuery(topic = "home/mode")).events
        assertEquals(1, found.size)
        assertTrue(found.single().retained)
        // A live message on the same topic is not retained.
        t.bus.receive("home/mode", "home")
        t.app.events.flush()
        assertEquals(listOf(false, true), t.app.eventLog.search(EventQuery(topic = "home/mode")).events.map { it.retained })
    }
}
