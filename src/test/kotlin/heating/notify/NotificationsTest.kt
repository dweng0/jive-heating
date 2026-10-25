package heating.notify

import heating.rooms.Mode
import heating.testing.Pushed
import heating.testing.TEST_TOKEN
import heating.testing.TestHeating
import heating.testing.anonymous
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationsTest {

    private suspend fun ApplicationTestBuilder.post(path: String, body: String, token: String? = null): HttpResponse =
        anonymous.post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
            token?.let { bearerAuth(it) }
        }

    private suspend fun ApplicationTestBuilder.tokenOf(response: HttpResponse): String =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content

    /**
     * Admin "jane" and user "john", who can see every room of [t] so far, each signed in with a
     * phone registered as "<name>-phone".
     */
    private suspend fun ApplicationTestBuilder.household(t: TestHeating): Map<String, String> {
        val jane = tokenOf(post("/api/auth/setup", """{"serviceToken":"$TEST_TOKEN","username":"jane","password":"correct horse"}"""))
        val johnId = Json.parseToJsonElement(
            post("/api/admin/users", """{"username":"john","password":"correct horse","admin":false}""", jane).bodyAsText(),
        ).jsonObject["id"]!!.jsonPrimitive.content
        // John may use every room that exists so far.
        val roomIds = t.app.rooms.list().joinToString(",") { "\"${it.id}\"" }
        assertEquals(HttpStatusCode.OK, anonymous.put("/api/admin/users/$johnId/rooms") {
            contentType(ContentType.Application.Json)
            setBody("""{"rooms":[$roomIds]}""")
            bearerAuth(jane)
        }.status)
        val john = tokenOf(post("/api/auth/login", """{"username":"john","password":"correct horse"}"""))
        val tokens = mapOf("jane" to jane, "john" to john)
        tokens.forEach { (name, token) ->
            assertEquals(HttpStatusCode.NoContent, anonymous.put("/api/me/devices/$name-phone") { bearerAuth(token) }.status)
        }
        return tokens
    }

    /** "Utility Room" with "Utility TRV" at 18.0, off, so nothing calls for heat yet. */
    private fun utility(t: TestHeating): String {
        t.discover("Utility TRV")
        val room = t.app.rooms.create("Utility Room")
        t.app.rooms.assignValve(room.id, "Utility TRV")
        t.app.zones.setMode(room.id, Mode.OFF, null)
        t.valveState("Utility TRV", """{"local_temperature":18.0}""")
        return room.id
    }

    /** "Turned the heating on" waits a few seconds to gather every room one action started. */
    private fun gathered(t: TestHeating) {
        t.clock.advance(Duration.ofSeconds(10))
        t.app.tick()
    }

    private suspend fun ApplicationTestBuilder.setMode(token: String, zoneId: String, json: String) =
        anonymous.put("/api/zones/$zoneId/mode") {
            contentType(ContentType.Application.Json)
            setBody(json)
            bearerAuth(token)
        }.also { assertEquals(HttpStatusCode.OK, it.status) }

    // BDD: A phone registers for notifications
    @Test
    fun `a phone registers for notifications`() {
        val t = TestHeating()
        val zoneId = utility(t)
        t.api {
            val users = household(t)
            assertEquals(HttpStatusCode.NoContent, anonymous.delete("/api/me/devices/jane-phone") { bearerAuth(users.getValue("jane")) }.status)
            setMode(users.getValue("john"), zoneId, """{"mode":"manual","target":22.0}""")
        }
        gathered(t)
        assertEquals(emptyList(), t.pushed.filter { it.token == "jane-phone" })
    }

    // BDD: Someone turning the heating on notifies everyone else
    @Test
    fun `someone turning the heating on notifies everyone else`() {
        val t = TestHeating()
        val zoneId = utility(t)
        t.api {
            val users = household(t)
            setMode(users.getValue("john"), zoneId, """{"mode":"manual","target":22.0}""")
        }
        gathered(t)
        assertEquals(listOf(Pushed("jane-phone", "John turned the heating on", "Utility Room, heating to 22°")), t.pushed)
    }

    // BDD: A knob turn that turns the heating on notifies everyone
    @Test
    fun `a knob turn that turns the heating on notifies everyone`() {
        val t = TestHeating()
        t.discover("Bedroom 2 TRV")
        val room = t.app.rooms.create("Bedroom 2")
        t.app.rooms.assignValve(room.id, "Bedroom 2 TRV")
        t.app.zones.setMode(room.id, Mode.MANUAL, 9.5)
        t.valveState("Bedroom 2 TRV", """{"local_temperature":21.8,"occupied_heating_setpoint":9.5}""")
        t.api { household(t) }
        t.clock.advance(Duration.ofMinutes(10))

        t.valveState("Bedroom 2 TRV", """{"occupied_heating_setpoint":26.5}""")
        gathered(t)
        val expected = Pushed("", "Thermostat turned up", "Bedroom 2 thermostat was turned up to 26.5°")
        assertEquals(setOf(expected.copy(token = "jane-phone"), expected.copy(token = "john-phone")), t.pushed.toSet())
    }

    @Test
    fun `a knob turned up in a room already warm enough sends nothing`() {
        val t = TestHeating()
        t.discover("Bedroom 2 TRV")
        val room = t.app.rooms.create("Bedroom 2")
        t.app.rooms.assignValve(room.id, "Bedroom 2 TRV")
        t.app.zones.setMode(room.id, Mode.MANUAL, 9.5)
        t.valveState("Bedroom 2 TRV", """{"local_temperature":21.8,"occupied_heating_setpoint":9.5}""")
        t.api { household(t) }
        t.clock.advance(Duration.ofMinutes(10))

        t.valveState("Bedroom 2 TRV", """{"occupied_heating_setpoint":19.0}""")
        gathered(t)
        assertEquals(19.0, t.app.rooms.get(room.id).settings.manualTarget)
        assertEquals(emptyList(), t.pushed)
    }

    // BDD: One action that starts several rooms sends one notification
    @Test
    fun `one action that starts several rooms sends one notification`() {
        val t = TestHeating()
        val names = listOf("A", "B", "C", "D")
        t.discover(*names.map { "$it TRV" }.toTypedArray())
        names.forEach { n ->
            val room = t.app.rooms.create("$n room")
            t.app.rooms.assignValve(room.id, "$n TRV")
            t.app.zones.setMode(room.id, Mode.OFF, null)
            t.valveState("$n TRV", """{"local_temperature":18.0}""")
        }
        t.api {
            val users = household(t)
            val response = post("/api/house", """{"boost":60,"target":22.0}""", users.getValue("jane"))
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        }
        gathered(t)
        assertEquals(listOf(Pushed("john-phone", "Jane turned the heating on", "4 rooms, heating to 22°")), t.pushed)
    }

    // BDD: Heating finished after a user action notifies everyone
    @Test
    fun `heating finished after a user action notifies everyone`() {
        val t = TestHeating()
        val zoneId = utility(t)
        t.api {
            val users = household(t)
            setMode(users.getValue("john"), zoneId, """{"mode":"manual","target":22.0}""")
        }
        gathered(t)
        t.pushed.clear()
        t.valveState("Utility TRV", """{"local_temperature":22.0}""")
        val expected = Pushed("", "Heating finished", "Utility Room is at 22°")
        assertEquals(setOf(expected.copy(token = "jane-phone"), expected.copy(token = "john-phone")), t.pushed.toSet())
    }

    @Test
    fun `a room turned off before it got there sends no heating finished`() {
        val t = TestHeating()
        val zoneId = utility(t)
        t.api {
            val users = household(t)
            setMode(users.getValue("john"), zoneId, """{"mode":"manual","target":22.0}""")
            gathered(t)
            t.pushed.clear()
            setMode(users.getValue("john"), zoneId, """{"mode":"off"}""")
        }
        gathered(t)
        assertEquals(emptyList(), t.pushed)
    }

    // BDD: Schedule heating sends no notifications
    @Test
    fun `schedule heating sends no notifications`() {
        val t = TestHeating()
        val zoneId = utility(t)
        t.api { household(t) }
        t.app.zones.setMode(zoneId, Mode.MANUAL, 22.0) // no one asked: like a schedule block
        gathered(t)
        t.valveState("Utility TRV", """{"local_temperature":22.0}""")
        assertEquals(emptyList(), t.pushed)
    }

    // BDD: Each person can turn each notification off
    @Test
    fun `each person can turn each notification off`() {
        val t = TestHeating()
        val zoneId = utility(t)
        t.api {
            val users = household(t)
            val jane = users.getValue("jane")
            assertEquals("""{"heatingOn":true,"heatingFinished":true,"boilerOverride":true}""", anonymous.get("/api/me/notifications") { bearerAuth(jane) }.bodyAsText())
            val saved = anonymous.put("/api/me/notifications") {
                contentType(ContentType.Application.Json)
                setBody("""{"heatingOn":false,"heatingFinished":true}""")
                bearerAuth(jane)
            }
            assertEquals(HttpStatusCode.OK, saved.status)
            assertEquals("""{"heatingOn":false,"heatingFinished":true,"boilerOverride":true}""", anonymous.get("/api/me/notifications") { bearerAuth(jane) }.bodyAsText())
            setMode(users.getValue("john"), zoneId, """{"mode":"manual","target":22.0}""")
        }
        gathered(t)
        assertEquals(emptyList(), t.pushed)
        t.valveState("Utility TRV", """{"local_temperature":22.0}""")
        assertTrue(t.pushed.any { it.token == "jane-phone" && it.title == "Heating finished" })
    }

    // BDD: Phones the push service no longer knows are forgotten
    @Test
    fun `phones the push service no longer knows are forgotten`() {
        val t = TestHeating()
        val zoneId = utility(t)
        t.unregistered += "jane-phone"
        t.api {
            val users = household(t)
            setMode(users.getValue("john"), zoneId, """{"mode":"manual","target":22.0}""")
        }
        gathered(t)
        assertEquals(1, t.pushed.count { it.token == "jane-phone" })
        t.valveState("Utility TRV", """{"local_temperature":22.0}""")
        assertEquals(1, t.pushed.count { it.token == "jane-phone" })
        assertEquals(1, t.pushed.count { it.token == "john-phone" })
    }

    // BDD: A failed notification does not stop the heating
    @Test
    fun `a failed notification does not stop the heating`() {
        val t = TestHeating()
        val zoneId = utility(t)
        t.pushFails = true
        t.api {
            val users = household(t)
            setMode(users.getValue("john"), zoneId, """{"mode":"manual","target":22.0}""")
        }
        gathered(t)
        assertTrue(t.app.demand.demand)
    }

    // BDD: Notifications only tell members about rooms they can see
    @Test
    fun `notifications only tell members about rooms they can see`() {
        val t = TestHeating()
        t.discover("Lounge TRV", "Bedroom TRV")
        val (lounge, bedroom) = listOf("Lounge", "Bedroom").map { name ->
            val room = t.app.rooms.create(name)
            t.app.rooms.assignValve(room.id, "$name TRV")
            t.app.zones.setMode(room.id, Mode.OFF, null)
            t.valveState("$name TRV", """{"local_temperature":18.0}""")
            room.id
        }
        t.api {
            val users = household(t)
            val samId = Json.parseToJsonElement(
                post("/api/admin/users", """{"username":"sam","password":"correct horse","admin":false}""", users.getValue("jane")).bodyAsText(),
            ).jsonObject["id"]!!.jsonPrimitive.content
            assertEquals(HttpStatusCode.OK, anonymous.put("/api/admin/users/$samId/rooms") {
                contentType(ContentType.Application.Json)
                setBody("""{"rooms":["$bedroom"]}""")
                bearerAuth(users.getValue("jane"))
            }.status)
            val sam = tokenOf(post("/api/auth/login", """{"username":"sam","password":"correct horse"}"""))
            assertEquals(HttpStatusCode.NoContent, anonymous.put("/api/me/devices/sam-phone") { bearerAuth(sam) }.status)
            val john = users.getValue("john")

            setMode(john, lounge, """{"mode":"manual","target":22.0}""")
            gathered(t)
            assertEquals(listOf(Pushed("jane-phone", "John turned the heating on", "Lounge, heating to 22°")), t.pushed)
            t.pushed.clear()
            t.valveState("Lounge TRV", """{"local_temperature":22.0}""")
            val finished = Pushed("", "Heating finished", "Lounge is at 22°")
            assertEquals(setOf(finished.copy(token = "jane-phone"), finished.copy(token = "john-phone")), t.pushed.toSet())
            t.pushed.clear()

            setMode(john, lounge, """{"mode":"manual","target":23.0}""")
            setMode(john, bedroom, """{"mode":"manual","target":21.0}""")
            gathered(t)
            assertEquals(
                setOf(
                    Pushed("jane-phone", "John turned the heating on", "2 rooms"),
                    Pushed("sam-phone", "John turned the heating on", "Bedroom, heating to 21°"),
                ),
                t.pushed.toSet(),
            )
            t.pushed.clear()
            t.valveState("Bedroom TRV", """{"local_temperature":21.0}""")
            t.valveState("Lounge TRV", """{"local_temperature":23.0}""")
            val both = Pushed("", "Heating finished", "Bedroom is at 21°, Lounge is at 23°")
            assertEquals(
                setOf(both.copy(token = "jane-phone"), both.copy(token = "john-phone"), Pushed("sam-phone", "Heating finished", "Bedroom is at 21°")),
                t.pushed.toSet(),
            )
        }
    }
}
