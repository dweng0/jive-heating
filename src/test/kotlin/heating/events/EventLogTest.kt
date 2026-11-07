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

    // BDD: Event logging keeps up with a busy broker
    @Test
    fun `messages are queued off the mqtt thread and written in batches`() {
        val t = service()
        t.app.events.flush() // what the service itself published while starting
        val before = t.app.eventLog.count()
        repeat(200) { t.bus.receive("busy/$it", """{"n":$it}""") }
        // Nothing has been written by the MQTT handler itself: it only queued the events.
        assertEquals(200, t.app.events.status().queued)
        assertEquals(before, t.app.eventLog.count())
        t.app.events.flush()
        assertEquals(0, t.app.events.status().queued)
        assertEquals(before + 200, t.app.eventLog.count())
    }

    // BDD: Event logging keeps up with a busy broker
    @Test
    fun `over 10000 waiting events the oldest are dropped and counted in the status`() {
        val t = service()
        t.app.events.flush()
        repeat(12_000) { t.bus.receive("busy/$it", "x") }
        t.api {
            val settings = getJson("/api/events/settings")["status"]!!.jsonObject
            assertEquals(2_000, settings["dropped"]!!.jsonPrimitive.long)
            assertEquals(10_000, settings["queued"]!!.jsonPrimitive.int)
            val oldest = getJson("/api/events?topic=busy/%23&sort=at%20asc&limit=1")
            assertEquals(listOf("busy/2000"), oldest.topics())
            assertEquals(10_000, oldest["total"]!!.jsonPrimitive.long)
        }
    }

    // BDD: Event logging keeps up with a busy broker
    @Test
    fun `payloads over 64 KB are cut to 64 KB and flagged truncated`() {
        val t = service()
        t.bus.receive("big/json", """{"data":"${"a".repeat(100_000)}"}""")
        t.bus.receive("big/text", "é".repeat(50_000))
        t.bus.receive("small/text", "fits")
        t.api {
            val events = getJson("/api/events")["events"]!!.jsonArray.map { it.jsonObject }.associateBy { it["topic"]!!.jsonPrimitive.content }
            val json = events.getValue("big/json")
            assertEquals(true, json["truncated"]!!.jsonPrimitive.boolean)
            assertEquals(false, json["json"]!!.jsonPrimitive.boolean)
            assertEquals(65_536, json["payload"]!!.jsonPrimitive.content.toByteArray().size)
            val text = events.getValue("big/text")
            assertEquals(true, text["truncated"]!!.jsonPrimitive.boolean)
            assertTrue(text["payload"]!!.jsonPrimitive.content.toByteArray().size <= 65_536)
            assertEquals(false, events.getValue("small/text")["truncated"]!!.jsonPrimitive.boolean)
        }
    }
}
