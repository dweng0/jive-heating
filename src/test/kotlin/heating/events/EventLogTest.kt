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

    private fun TestHeating.addEvent(ageDays: Long, topic: String, payload: String = "x") =
        app.eventLog.addAll(listOf(Event(0, clock.instant().minus(Duration.ofDays(ageDays)), topic, payload, false, false, false)))

    private fun TestHeating.topicsLogged() = app.eventLog.search(EventQuery(sortBy = "at", descending = false)).events.map { it.topic }.filter { it.startsWith("age/") }

    // BDD: Old events are cleared out
    @Test
    fun `events older than the kept days are deleted once an hour`() {
        val t = service()
        t.app.tick() // the first clean-up
        t.addEvent(8, "age/8d")
        t.addEvent(6, "age/6d")
        t.clock.advance(Duration.ofMinutes(30))
        t.app.tick()
        assertEquals(listOf("age/8d", "age/6d"), t.topicsLogged(), "not yet an hour since the last clean-up")
        t.clock.advance(Duration.ofMinutes(31))
        t.app.tick()
        assertEquals(listOf("age/6d"), t.topicsLogged())
        // After a day the 6-day-old event is 7 days and an hour old: gone too.
        t.clock.advance(Duration.ofDays(1))
        t.app.tick()
        assertEquals(emptyList(), t.topicsLogged())
    }

    // BDD: Old events are cleared out
    @Test
    fun `the oldest events go when the log is over its size limit`() {
        val t = service(maxBytes = 4_000)
        t.app.tick()
        repeat(10) { t.addEvent(0, "age/$it", "p".repeat(1_000)) }
        t.clock.advance(Duration.ofHours(1))
        t.app.tick()
        val left = t.topicsLogged()
        assertTrue(left.isNotEmpty() && left.size < 10, left.toString())
        assertEquals("age/9", left.last())
        assertTrue(t.app.eventLog.bytes() <= 4_000)
        assertEquals((10 - left.size until 10).map { "age/$it" }, left)
    }

    // BDD: Old events are cleared out
    @Test
    fun `an admin sets the days from 1 to 90 and members get 403`() {
        val t = service()
        t.api {
            assertEquals(7, getJson("/api/events/settings")["days"]!!.jsonPrimitive.int)
            assertEquals(HttpStatusCode.OK, putSettings("""{"days":30}""").status)
            assertEquals(30, getJson("/api/events/settings")["days"]!!.jsonPrimitive.int)
            assertEquals(HttpStatusCode.OK, putSettings("""{"days":1}""").status)
            assertEquals(HttpStatusCode.OK, putSettings("""{"days":90}""").status)
            listOf("0", "91", "-3", "7.5", "\"week\"").forEach {
                assertEquals(HttpStatusCode.BadRequest, putSettings("""{"days":$it}""").status, it)
            }
            assertEquals(90, getJson("/api/events/settings")["days"]!!.jsonPrimitive.int)
            val member = memberToken()
            assertEquals(HttpStatusCode.Forbidden, putSettings("""{"days":5}""", member).status)
            assertEquals(HttpStatusCode.Forbidden, get("/api/events/settings", member).status)
            assertEquals(90, getJson("/api/events/settings")["days"]!!.jsonPrimitive.int)
        }
        // The setting is kept across a restart and decides what is cleared.
        t.restart()
        t.addEvent(40, "age/40d")
        t.clock.advance(Duration.ofHours(2))
        t.app.tick()
        assertEquals(listOf("age/40d"), t.topicsLogged())
        t.api {
            putSettings("""{"days":30}""")
            t.clock.advance(Duration.ofHours(2))
            t.app.tick()
            assertEquals(emptyList(), t.topicsLogged())
        }
    }

    // BDD: Topics can be left out of the event log
    @Test
    fun `excluded topics are not logged but still reach other subscribers`() {
        val t = service()
        val heard = mutableListOf<String>()
        t.bus.subscribe("#") { topic, _ -> heard += topic } // stands in for a notification rule
        t.api {
            val response = putSettings("""{"excluded":["z2m/bridge/logging","homeassistant/#"]}""")
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            repeat(3) { t.bus.receive("z2m/bridge/logging", """{"level":"info"}""") }
            t.bus.receive("homeassistant/sensor/a/config", "{}")
            t.bus.receive("homeassistant/sensor/b/config", "{}")
            t.bus.receive("z2m/bridge/state", "online")
            val logged = getJson("/api/events")["events"]!!.jsonArray.map { it.jsonObject["topic"]!!.jsonPrimitive.content }
            assertTrue("z2m/bridge/state" in logged)
            assertTrue(logged.none { it == "z2m/bridge/logging" || it.startsWith("homeassistant/") }, logged.toString())
            assertEquals(3, heard.count { it == "z2m/bridge/logging" })
            assertEquals(2, heard.count { it.startsWith("homeassistant/") })

            suspend fun rates() = getJson("/api/events/settings")["excluded"]!!.jsonArray.associate {
                it.jsonObject["topic"]!!.jsonPrimitive.content to it.jsonObject["perMinute"]!!.jsonPrimitive.int
            }
            assertEquals(mapOf("z2m/bridge/logging" to 3, "homeassistant/#" to 2), rates())
            t.clock.advance(Duration.ofSeconds(61))
            assertEquals(mapOf("z2m/bridge/logging" to 0, "homeassistant/#" to 0), rates())

            // Taking a topic off the list logs it again; bad filters and members are refused.
            putSettings("""{"excluded":["homeassistant/#"]}""")
            t.bus.receive("z2m/bridge/logging", "{}")
            assertTrue("z2m/bridge/logging" in getJson("/api/events")["events"]!!.jsonArray.map { it.jsonObject["topic"]!!.jsonPrimitive.content })
            listOf("\"a/#/b\"", "\"\"", "\"a/b+\"").forEach {
                assertEquals(HttpStatusCode.BadRequest, putSettings("""{"excluded":[$it]}""").status, it)
            }
            assertEquals(HttpStatusCode.Forbidden, putSettings("""{"excluded":[]}""", memberToken()).status)
        }
        // Kept across a restart.
        t.restart()
        t.api { assertEquals(listOf("homeassistant/#"), getJson("/api/events/settings")["excluded"]!!.jsonArray.map { it.jsonObject["topic"]!!.jsonPrimitive.content }) }
    }

    /** Events an hour apart from 10:00Z: door contact, kitchen temperature, a deeper z2m topic and a text one. */
    private fun TestHeating.seed() {
        val messages = listOf(
            "z2m/front_door" to """{"contact":false,"battery":91}""",
            "z2m/kitchen" to """{"temperature":19.5}""",
            "z2m/front_door" to """{"contact":true,"battery":90}""",
            "z2m/bridge/logging" to """{"message":"contact sensor paired"}""",
            "garage/temp" to "18.5",
            "z2m/back_door" to """{"contact":true,"battery":80,"nested":{"deep":1}}""",
        )
        messages.forEach { (topic, payload) ->
            clock.advance(Duration.ofHours(1))
            bus.receive(topic, payload)
        }
        app.events.flush()
    }

    // BDD: Events are searched through the API
    @Test
    fun `events are searched by topic wildcard time text and field newest first`() {
        val t = service()
        t.seed()
        t.api {
            // The wildcard matches one level; newest first by default.
            val z2m = getJson("/api/events?topic=z2m/%2B")
            assertEquals(listOf("z2m/back_door", "z2m/front_door", "z2m/kitchen", "z2m/front_door"), z2m.topics())
            assertEquals(4, z2m["total"]!!.jsonPrimitive.long)
            assertEquals(5, getJson("/api/events?topic=z2m/%23")["total"]!!.jsonPrimitive.long)
            assertEquals(listOf("garage/temp"), getJson("/api/events?topic=garage/temp").topics())
            // Times: the seeded events are at 10:00Z to 15:00Z.
            val window = getJson("/api/events?topic=z2m/%23&from=2026-10-12T11:00:00Z&to=2026-10-12T12:00:00Z&sort=at%20asc")
            assertEquals(listOf("z2m/kitchen", "z2m/front_door"), window.topics())
            assertEquals("2026-10-12T11:00:00Z", window["events"]!!.jsonArray.first().jsonObject["at"]!!.jsonPrimitive.content)
            // Text matches topic or payload, ignoring case.
            assertEquals(listOf("z2m/back_door", "z2m/bridge/logging", "z2m/front_door", "z2m/front_door"), getJson("/api/events?text=CONTACT").topics().sorted())
            assertEquals(listOf("garage/temp"), getJson("/api/events?text=garage").topics())
            // Field: only JSON events that have it.
            val contact = getJson("/api/events?field=contact")
            assertEquals(listOf("z2m/back_door", "z2m/front_door", "z2m/front_door"), contact.topics().sorted())
            assertEquals(3, contact["total"]!!.jsonPrimitive.long)
            assertEquals(listOf("z2m/back_door"), getJson("/api/events?field=nested.deep").topics())
            // The scenario's own combination.
            val combined = getJson("/api/events?topic=z2m/%2B&from=2026-10-12T09:00:00Z&to=2026-10-12T20:00:00Z&text=contact&field=contact&sort=at%20desc&limit=100")
            assertEquals(listOf("z2m/back_door", "z2m/front_door", "z2m/front_door"), combined.topics())
            assertEquals(3, combined["total"]!!.jsonPrimitive.long)
            assertEquals("null", combined["next"]!!.toString())
        }
    }

    // BDD: Events are searched through the API
    @Test
    fun `events are paged with a cursor and sorted by topic`() {
        val t = service()
        t.seed()
        t.api {
            val first = getJson("/api/events?topic=z2m/%23&limit=2")
            assertEquals(listOf("z2m/back_door", "z2m/bridge/logging"), first.topics())
            assertEquals(5, first["total"]!!.jsonPrimitive.long)
            val second = getJson("/api/events?topic=z2m/%23&limit=2&cursor=${first["next"]!!.jsonPrimitive.content}")
            assertEquals(listOf("z2m/front_door", "z2m/kitchen"), second.topics())
            val third = getJson("/api/events?topic=z2m/%23&limit=2&cursor=${second["next"]!!.jsonPrimitive.content}")
            assertEquals(listOf("z2m/front_door"), third.topics())
            assertEquals("null", third["next"]!!.toString())
            assertEquals(5, third["total"]!!.jsonPrimitive.long)

            val byTopic = getJson("/api/events?topic=z2m/%23&sort=topic%20asc&limit=2")
            assertEquals(listOf("z2m/back_door", "z2m/bridge/logging"), byTopic.topics())
            val more = getJson("/api/events?topic=z2m/%23&sort=topic%20asc&limit=2&cursor=${byTopic["next"]!!.jsonPrimitive.content}")
            assertEquals(listOf("z2m/front_door", "z2m/front_door"), more.topics())
            assertEquals(listOf("z2m/kitchen"), getJson("/api/events?topic=z2m/%23&sort=topic%20desc&limit=1").topics())

            listOf("limit=0", "limit=1001", "limit=x", "sort=colour", "sort=at%20sideways", "from=yesterday", "to=1", "field=a%27b", "cursor=%24%24").forEach {
                assertEquals(HttpStatusCode.BadRequest, get("/api/events?$it").status, it)
            }
        }
    }

    // BDD: Events are searched through the API
    @Test
    fun `topics seen are listed with count last seen and last payload and members get 403`() {
        val t = service()
        t.seed()
        t.api {
            val response = get("/api/events/topics")
            assertEquals(HttpStatusCode.OK, response.status)
            val topics = heatingJson.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonObject }.associateBy { it["topic"]!!.jsonPrimitive.content }
            val door = topics.getValue("z2m/front_door")
            assertEquals(2, door["count"]!!.jsonPrimitive.long)
            assertEquals("2026-10-12T12:00:00Z", door["lastSeen"]!!.jsonPrimitive.content)
            assertEquals(90, door["lastPayload"]!!.jsonObject["battery"]!!.jsonPrimitive.int)
            assertEquals(true, door["json"]!!.jsonPrimitive.boolean)
            val garage = topics.getValue("garage/temp")
            assertEquals(1, garage["count"]!!.jsonPrimitive.long)
            assertEquals("18.5", garage["lastPayload"]!!.jsonPrimitive.content)

            val member = memberToken()
            assertEquals(HttpStatusCode.Forbidden, get("/api/events", member).status)
            assertEquals(HttpStatusCode.Forbidden, get("/api/events/topics", member).status)
        }
    }
}
