package heating.activity

import heating.Actor
import heating.api.heatingModule
import heating.heatingJson
import heating.rooms.Block
import heating.rooms.DaySchedule
import heating.rooms.Mode
import heating.testing.MutableClock
import heating.testing.TEST_TOKEN
import heating.testing.TestHeating
import heating.testing.anonymous
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityFeedTest {

    /** Signs in "john" as the first user and returns her token. */
    private suspend fun ApplicationTestBuilder.signInJohn(): String {
        val body = anonymous.post("/api/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody("""{"serviceToken":"$TEST_TOKEN","username":"john","password":"correct horse"}""")
        }.bodyAsText()
        return Json.parseToJsonElement(body).jsonObject["token"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.events(token: String, query: String = ""): List<JsonObject> =
        Json.parseToJsonElement(anonymous.get("/api/activity$query") { bearerAuth(token) }.bodyAsText())
            .jsonObject["events"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.text(key: String) = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun JsonObject.names(key: String) = this[key]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun TestHeating.room(name: String, valve: String = "$name TRV"): String {
        discover(valve)
        val room = app.rooms.create(name)
        app.rooms.assignValve(room.id, valve)
        return room.id
    }

    /** Rooms named [names], each with its own "<name> TRV", discovered together (a second discover replaces the first). */
    private fun TestHeating.rooms(vararg names: String): List<String> {
        discover(*names.map { "$it TRV" }.toTypedArray())
        return names.map { name ->
            app.rooms.create(name).also { app.rooms.assignValve(it.id, "$name TRV") }.id
        }
    }

    private fun TestHeating.feed(limit: Int = 500) = app.activity.recent(limit)

    private fun TestHeating.minutes(n: Long) {
        clock.advance(Duration.ofMinutes(n))
        app.tick()
    }

    // BDD: A person changing a room is recorded with their name
    @Test
    fun `a person changing a room is recorded with their name`() {
        val t = TestHeating()
        val room = t.room("Utility Room")

        t.api {
            val john = signInJohn()
            anonymous.put("/api/zones/$room/mode") {
                contentType(ContentType.Application.Json)
                setBody("""{"mode":"manual","target":21.0}""")
                bearerAuth(john)
            }

            val newest = events(john).first()
            assertEquals("setpoint", newest.text("kind"))
            assertEquals("John", newest.text("actor"))
            assertEquals("Utility Room", newest.text("room"))
            assertEquals(21.0, newest["value"]!!.jsonPrimitive.double)
        }
    }

    // BDD: Changing Off, Auto, boost or holiday is recorded
    @Test
    fun `changing off auto boost or holiday is recorded`() {
        val t = TestHeating()
        val (lounge, bedroom) = t.rooms("Lounge", "Bedroom")

        Actor.runAs("jane") {
            t.app.zones.setMode(lounge, Mode.OFF, null)
            t.app.boosts.boost(bedroom, 60, 23.0)
            t.app.holiday.start(null, "2026-10-20T12:00:00", null)
        }

        val (holiday, boost, mode) = t.feed()
        assertEquals("holiday", holiday.kind)
        assertEquals("boost", boost.kind)
        assertEquals(JsonPrimitive(23.0), boost.value)
        assertEquals("mode", mode.kind)
        assertEquals(JsonPrimitive("off"), mode.value)
        assertEquals(listOf("Jane", "Jane", "Jane"), listOf(holiday, boost, mode).map { it.actor })
    }

    // BDD: A change to a group or the whole house is one event
    @Test
    fun `a change to a group or the whole house is one event`() {
        val t = TestHeating()
        val ids = t.rooms("Lounge", "Kitchen", "Hall")

        Actor.runAs("jane") { t.app.house.setMode(Mode.MANUAL, 20.0, ids, "group Downstairs") }

        val events = t.feed()
        assertEquals(1, events.size)
        assertEquals("setpoint", events.single().kind)
        assertEquals("Downstairs", events.single().room)
        assertEquals(listOf("Lounge", "Kitchen", "Hall"), events.single().rooms)
    }

    // BDD: A knob turn is recorded with no actor
    @Test
    fun `a knob turn is recorded with no actor`() {
        val t = TestHeating()
        val room = t.room("Bedroom 2", "Bedroom 2 TRV")
        t.app.zones.setMode(room, Mode.MANUAL, 20.0)
        t.valveState("Bedroom 2 TRV", """{"occupied_heating_setpoint":20.0}""")
        t.clock.advance(Duration.ofMinutes(2))

        t.valveState("Bedroom 2 TRV", """{"occupied_heating_setpoint":22.0}""")

        val knob = t.feed().first()
        assertEquals("knob", knob.kind)
        assertNull(knob.actor)
        assertEquals("Bedroom 2", knob.room)
        assertEquals(JsonPrimitive(22.0), knob.value)

        // A late report of an earlier command is not a knob turn.
        val before = t.feed().size
        t.app.zones.setMode(room, Mode.OFF, null)
        t.valveState("Bedroom 2 TRV", """{"occupied_heating_setpoint":22.0}""")
        assertEquals(before, t.feed().count { it.kind == "knob" } + t.feed().count { it.kind != "knob" })
        assertEquals(1, t.feed().count { it.kind == "knob" })
    }

    // BDD: Schedule blocks starting and ending are recorded
    @Test
    fun `schedule blocks starting and ending are recorded`() {
        // Monday 06:29 BST
        val t = TestHeating(clock = MutableClock(Instant.parse("2026-10-12T05:29:00Z")))
        val room = t.room("Lounge")
        t.app.rooms.updateZone(room) {
            it.copy(
                mode = Mode.SCHEDULE,
                schedule = it.schedule.with(DayOfWeek.MONDAY, DaySchedule(listOf(Block("00:00", 16.0), Block("06:30", 21.0), Block("08:00", 16.0)))),
            )
        }
        t.app.tick()

        t.minutes(1)
        t.minutes(90)

        val schedule = t.feed().filter { it.kind == "schedule" }.reversed()
        assertEquals(listOf(JsonPrimitive(21.0), JsonPrimitive(16.0)), schedule.map { it.value })
        assertEquals(listOf(null, null), schedule.map { it.actor })
        assertEquals("Lounge", schedule.first().room)
    }

    /** Lounge and Utility Room, both 18.0 and scheduled to 21.0 from 07:00 BST on Monday; the clock is at 06:59. */
    private fun twoRoomsAboutToCall(): TestHeating {
        val t = TestHeating(clock = MutableClock(Instant.parse("2026-10-12T05:59:00Z")))
        t.rooms("Lounge", "Utility Room").zip(listOf("Lounge", "Utility Room")).forEach { (id, name) ->
            t.app.rooms.updateZone(id) {
                it.copy(schedule = it.schedule.with(DayOfWeek.MONDAY, DaySchedule(listOf(Block("00:00", 16.0), Block("07:00", 21.0)))))
            }
            t.valveState("$name TRV", """{"local_temperature":18.0}""")
        }
        t.app.tick()
        return t
    }

    private fun boilerEvents(t: TestHeating) = t.feed().filter { it.kind == "boiler" }.reversed()

    // BDD: The boiler going to heating says which rooms called for it
    @Test
    fun `the boiler going to heating says which rooms called for it`() {
        val t = twoRoomsAboutToCall()

        t.minutes(2)

        val heating = boilerEvents(t).single()
        assertEquals(JsonPrimitive("heating"), heating.value)
        assertEquals("calling", heating.reason)
        assertEquals(setOf("Lounge", "Utility Room"), heating.rooms.toSet())
    }

    // BDD: The boiler cycling down says which room reached its temperature
    @Test
    fun `the boiler cycling down says which room reached its temperature`() {
        val t = TestHeating()
        val room = t.room("Utility Room")
        t.valveState("Utility Room TRV", """{"local_temperature":18.0}""")
        t.app.zones.setMode(room, Mode.MANUAL, 22.0)
        assertEquals(JsonPrimitive("heating"), boilerEvents(t).single().value)

        t.valveState("Utility Room TRV", """{"local_temperature":22.0}""")

        val cycling = boilerEvents(t).last()
        assertEquals(JsonPrimitive("cycling"), cycling.value)
        assertEquals("reached-temp", cycling.reason)
        assertEquals(listOf("Utility Room"), cycling.rooms)

        t.minutes(4)
        val idle = boilerEvents(t).last()
        assertEquals(JsonPrimitive("idle"), idle.value)
        assertEquals("no-demand", idle.reason)
    }

    /** Heating, cycling, idle, resting and heating again for "Lounge" within 9 minutes. */
    private fun loungeCycledTwice(t: TestHeating) {
        val room = t.room("Lounge")
        t.valveState("Lounge TRV", """{"local_temperature":18.0}""")
        t.app.zones.setMode(room, Mode.MANUAL, 22.0)
        t.valveState("Lounge TRV", """{"local_temperature":22.0}""")
        t.minutes(4)
        t.minutes(2)
        t.app.zones.setMode(room, Mode.MANUAL, 24.0)
        t.minutes(3)
    }

    // BDD: The boiler resting and restarting are explained
    @Test
    fun `the boiler resting and restarting are explained`() {
        val t = TestHeating()
        loungeCycledTwice(t)

        val states = boilerEvents(t).map { it.value!!.jsonPrimitive.content to it.reason }
        assertEquals(
            listOf("heating" to "calling", "cycling" to "reached-temp", "idle" to "no-demand", "resting" to "min-rest", "heating" to "calling"),
            states,
        )
        assertEquals(listOf("Lounge"), boilerEvents(t)[3].rooms)
    }

    // BDD: A valve or sensor losing contact is recorded once
    @Test
    fun `a valve or sensor losing contact is recorded once`() {
        val t = TestHeating()
        t.room("Lounge")

        t.availability("Lounge TRV", false)
        t.availability("Lounge TRV", false)
        t.availability("Lounge TRV", true)

        val offline = t.feed().filter { it.kind == "offline" }.reversed()
        assertEquals(listOf(JsonPrimitive("offline"), JsonPrimitive("online")), offline.map { it.value })
        assertEquals("Lounge", offline.first().room)
        assertNull(offline.first().device)
    }

    // BDD: A plug losing contact is in the activity feed
    @Test
    fun `a plug losing contact is in the activity feed`() {
        val t = TestHeating()
        t.discover("Bedroom 1 TRV", meters = listOf("TV Plug", "Kettle Plug"))
        val bedroom1 = t.app.rooms.create("Bedroom 1").also { t.app.rooms.assignValve(it.id, "Bedroom 1 TRV") }.id
        t.app.monitoredMeters.monitor("TV Plug")
        t.app.monitoredMeters.monitor("Kettle Plug")
        t.app.monitoredMeters.putInRoom("TV Plug", bedroom1)

        t.availability("TV Plug", false)
        t.availability("TV Plug", false)
        t.availability("TV Plug", true)
        t.availability("Kettle Plug", false)

        live(t) { client ->
            val plug = events(TEST_TOKEN).filter { it.text("kind") == "offline" }.reversed()
            assertEquals(
                listOf(Triple("Bedroom 1", "TV Plug", "offline"), Triple("Bedroom 1", "TV Plug", "online"), Triple(null, "Kettle Plug", "offline")),
                plug.map { Triple(it.text("room"), it.text("device"), it.text("value")) },
            )
            assertTrue(t.pushed.isEmpty())
            assertTrue(t.webPushed.isEmpty())

            val sam = memberWith(bedroom1)
            assertTrue(events(sam).none { it.text("kind") == "offline" })
            val socket = client.webSocketSession("/ws") { bearerAuth(sam) }
            t.availability("TV Plug", false)
            Actor.runAs("jane") { t.app.zones.setMode(bedroom1, Mode.MANUAL, 21.0) }
            assertEquals("setpoint", socket.nextActivity()["activity"]!!.jsonObject.text("kind"))
        }
    }

    // BDD: Events with nothing new are not recorded
    @Test
    fun `events with nothing new are not recorded`() {
        val t = TestHeating()
        val room = t.room("Lounge")
        Actor.runAs("jane") { t.app.zones.setMode(room, Mode.MANUAL, 21.0) }
        val before = t.feed().size

        Actor.runAs("jane") { t.app.zones.setMode(room, Mode.MANUAL, 21.0) }

        assertEquals(before, t.feed().size)
    }

    // BDD: Rapid boiler changes collapse into one line
    @Test
    fun `rapid boiler changes collapse into one line`() {
        val t = TestHeating()
        loungeCycledTwice(t)

        t.api {
            val boiler = events(TEST_TOKEN).filter { it.text("kind") == "boiler" }
            val line = boiler.single()
            assertEquals("cycled", line.text("value"))
            assertEquals(2, line["count"]!!.jsonPrimitive.int)
        }
    }

    // BDD: The feed is newest first and limited
    @Test
    fun `the feed is newest first and limited`() {
        val t = TestHeating()
        (1..80).forEach { t.app.activity.record("knob", null, "Lounge", JsonPrimitive(it.toDouble()), roomIds = emptyList()) }

        t.api {
            val first = events(TEST_TOKEN, "?limit=50")
            assertEquals(50, first.size)
            assertEquals(80.0, first.first()["value"]!!.jsonPrimitive.double)
            val older = events(TEST_TOKEN, "?limit=50&before=${first.last()["id"]!!.jsonPrimitive.content}")
            assertEquals(30, older.size)
            assertEquals(30.0, older.first()["value"]!!.jsonPrimitive.double)
            listOf("0", "201", "-1", "many").forEach { limit ->
                assertEquals(HttpStatusCode.BadRequest, anonymous.get("/api/activity?limit=$limit") { bearerAuth(TEST_TOKEN) }.status)
            }
        }
    }

    // BDD: Only the newest 500 events are kept
    @Test
    fun `only the newest 500 events are kept`() {
        val t = TestHeating()
        repeat(500) { t.app.activity.record("knob", null, "Lounge", JsonPrimitive(it.toDouble())) }

        t.app.activity.record("knob", null, "Lounge", JsonPrimitive(500.0))

        val all = t.app.activity.recent(1000)
        assertEquals(500, all.size)
        assertEquals(JsonPrimitive(500.0), all.first().value)
        assertEquals(JsonPrimitive(1.0), all.last().value)
    }

    // BDD: The feed survives a restart
    @Test
    fun `the feed survives a restart`() {
        val t = TestHeating()
        val room = t.room("Lounge")
        Actor.runAs("jane") { t.app.zones.setMode(room, Mode.MANUAL, 21.0) }
        val before = t.feed().map { it.id to it.value }

        t.restart()

        assertEquals(before, t.feed().map { it.id to it.value })
    }

    private fun live(t: TestHeating, block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application { heatingModule(t.app) }
        block(createClient { install(WebSockets) })
    }

    private suspend fun DefaultClientWebSocketSession.nextActivity(): JsonObject = withTimeout(5000) {
        var found: JsonObject? = null
        while (found == null) {
            val frame = incoming.receive()
            if (frame is Frame.Text) {
                val event = heatingJson.parseToJsonElement(frame.readText()).jsonObject
                if (event["type"]!!.jsonPrimitive.content == "activity.added") found = event
            }
        }
        found
    }

    // BDD: New events are pushed to connected clients
    @Test
    fun `new events are pushed to connected clients`() {
        val t = TestHeating()
        val room = t.room("Utility Room")
        live(t) { client ->
            val socket = client.webSocketSession("/ws") { bearerAuth(TEST_TOKEN) }
            Actor.runAs("john") { t.app.zones.setMode(room, Mode.MANUAL, 21.0) }

            val event = socket.nextActivity()["activity"]!!.jsonObject
            assertEquals("setpoint", event.text("kind"))
            assertEquals("John", event.text("actor"))
            assertEquals("Utility Room", event.text("room"))
        }
    }

    // BDD: Signed-out clients cannot read the feed
    @Test
    fun `signed-out clients cannot read the feed`() = TestHeating().api {
        assertEquals(HttpStatusCode.Unauthorized, anonymous.get("/api/activity").status)
    }

    // BDD: An empty feed says so
    @Test
    fun `an empty feed answers with no events`() = TestHeating().api {
        val response = anonymous.get("/api/activity") { bearerAuth(TEST_TOKEN) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(JsonObject(mapOf("events" to kotlinx.serialization.json.JsonArray(emptyList()))), Json.parseToJsonElement(response.bodyAsText()))
    }

    /** Admin "jane" and member "sam", who may use only [rooms]; returns sam's token. */
    private suspend fun ApplicationTestBuilder.memberWith(vararg rooms: String): String {
        suspend fun post(path: String, body: String, token: String? = null) = anonymous.post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
            token?.let { bearerAuth(it) }
        }.bodyAsText()
        fun field(json: String, key: String) = Json.parseToJsonElement(json).jsonObject[key]!!.jsonPrimitive.content
        val jane = field(post("/api/auth/setup", """{"serviceToken":"$TEST_TOKEN","username":"jane","password":"correct horse"}"""), "token")
        val sam = field(post("/api/admin/users", """{"username":"sam","password":"correct horse","admin":false}""", jane), "id")
        anonymous.put("/api/admin/users/$sam/rooms") {
            contentType(ContentType.Application.Json)
            setBody("""{"rooms":[${rooms.joinToString(",") { "\"$it\"" }}]}""")
            bearerAuth(jane)
        }
        return field(post("/api/auth/login", """{"username":"sam","password":"correct horse"}"""), "token")
    }

    // BDD: Members see only the rooms they can access
    @Test
    fun `members see only the rooms they can access`() {
        val t = TestHeating()
        val (lounge, bedroom) = t.rooms("Lounge", "Bedroom")
        t.app.activity.record("boiler", null, null, JsonPrimitive("heating"), "calling", listOf("Lounge", "Bedroom"), listOf(lounge, bedroom))
        t.app.activity.record("setpoint", "Jane", "Bedroom", JsonPrimitive(20.0), roomIds = listOf(bedroom))
        t.app.activity.record("setpoint", "Jane", "Lounge", JsonPrimitive(21.0), roomIds = listOf(lounge))

        live(t) { client ->
            val sam = memberWith(lounge)
            val seen = events(sam)
            assertEquals(listOf("setpoint", "boiler"), seen.map { it.text("kind") })
            assertEquals("Lounge", seen.first().text("room"))
            assertEquals(listOf("Lounge"), seen.last().names("rooms"))

            val socket = client.webSocketSession("/ws") { bearerAuth(sam) }
            t.app.activity.record("setpoint", "Jane", "Bedroom", JsonPrimitive(19.0), roomIds = listOf(bedroom))
            t.app.activity.record("setpoint", "Jane", "Lounge", JsonPrimitive(22.0), roomIds = listOf(lounge))

            assertEquals("Lounge", socket.nextActivity()["activity"]!!.jsonObject.text("room"))
        }
    }

    // BDD: A failed activity write does not stop the heating
    @Test
    fun `a failed activity write does not stop the heating`() {
        val t = TestHeating()
        val room = t.room("Utility Room")
        t.valveState("Utility Room TRV", """{"local_temperature":18.0}""")
        t.app.activity.close()

        Actor.runAs("john") { t.app.zones.setMode(room, Mode.MANUAL, 22.0) }

        assertTrue(t.app.demand.demand)
    }

    // BDD: Schedules and automatic changes are never attributed to a person
    @Test
    fun `schedules and automatic changes are never attributed to a person`() {
        val t = TestHeating()
        val room = t.room("Lounge")
        Actor.runAs("jane") { t.app.boosts.boost(room, 30, 22.0) }

        t.minutes(31)

        val ended = t.feed().first { it.kind == "boost" }
        assertNull(ended.actor)
        assertNull(ended.value)

        Actor.runAs("jane") { t.app.holiday.start(null, "2026-10-12T11:00:00", null) }
        t.minutes(180)
        val holidayOver = t.feed().first { it.kind == "holiday" }
        assertNull(holidayOver.actor)
        assertEquals(JsonPrimitive("end"), holidayOver.value)
    }
}
