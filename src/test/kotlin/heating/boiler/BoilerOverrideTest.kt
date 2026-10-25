package heating.boiler

import heating.Actor
import heating.rooms.Mode
import heating.testing.TEST_TOKEN
import heating.testing.TestHeating
import heating.testing.anonymous
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoilerOverrideTest {

    /** Room "Lounge" on manual 21.0 with a boost, its valve at 19.0 so the boiler is heating. */
    private fun heatingLounge(t: TestHeating): String {
        t.discover("Lounge TRV")
        val room = t.app.rooms.create("Lounge")
        t.app.rooms.assignValve(room.id, "Lounge TRV")
        t.app.zones.setMode(room.id, Mode.MANUAL, 21.0)
        t.app.boosts.boost(room.id, 60, 22.0)
        t.valveState("Lounge TRV", """{"local_temperature":19.0,"occupied_heating_setpoint":22.0,"system_mode":"heat"}""")
        assertTrue(t.app.demand.demand, "boiler is heating")
        return room.id
    }

    private fun published(t: TestHeating) = t.bus.sentTo("heating/demand").last()["demand"]!!.jsonPrimitive.boolean

    private fun zone(t: TestHeating) = t.app.rooms.list().single().zones().single()

    private fun lastValveCommand(t: TestHeating, key: String) = t.sentTo("Lounge TRV").lastOrNull { key in it }

    private fun turnOff(t: TestHeating, by: String = "jane") = Actor.runAs(by) { t.app.boiler.turnOff() }

    // BDD: An admin turns the boiler off
    @Test
    fun `an admin turns the boiler off`() {
        val t = TestHeating()
        heatingLounge(t)
        turnOff(t)

        assertEquals(false, published(t), "demand is published false at once, inside the minimum run time")
        assertEquals(JsonPrimitive("off"), t.sentTo("Lounge TRV").last()["system_mode"])
        assertNull(zone(t).settings.boost)
        assertEquals(Mode.MANUAL, zone(t).settings.mode)
        assertEquals(21.0, zone(t).settings.manualTarget)
        assertEquals("jane", t.app.boilerOverride.current()?.by)
    }

    // BDD: Nothing heats while the boiler is off
    @Test
    fun `nothing heats while the boiler is off`() {
        val t = TestHeating()
        val room = heatingLounge(t)
        turnOff(t)

        t.app.zones.setMode(room, Mode.MANUAL, 25.0)
        t.app.boosts.boost(room, 30, 25.0)
        t.valveState("Lounge TRV", """{"local_temperature":18.0,"occupied_heating_setpoint":25.0,"system_mode":"heat","running_state":"heat"}""")
        t.clock.advance(Duration.ofMinutes(10))
        t.app.tick()

        assertEquals(25.0, zone(t).settings.manualTarget, "the change is saved")
        assertEquals(false, t.app.demand.demand)
        assertEquals(false, published(t))
        assertEquals(JsonPrimitive("off"), t.sentTo("Lounge TRV").last()["system_mode"], "valves are told off again")
    }

    // BDD: The override survives a restart
    @Test
    fun `the override survives a restart`() {
        val t = TestHeating()
        heatingLounge(t)
        turnOff(t)
        t.restart()
        assertEquals("jane", t.app.boilerOverride.current()?.by)
        assertEquals(null, t.app.zones.effectiveTarget(zone(t).settings))
    }

    // BDD: An admin turns the boiler back on
    @Test
    fun `an admin turns the boiler back on`() {
        val t = TestHeating()
        heatingLounge(t)
        turnOff(t)
        Actor.runAs("jane") { t.app.boiler.turnOn() }

        assertNull(t.app.boilerOverride.current())
        assertEquals(JsonPrimitive(21.0), t.sentTo("Lounge TRV").last()["occupied_heating_setpoint"], "the zone's own target again")
        assertNull(zone(t).settings.boost, "the boost does not come back")
        t.valveState("Lounge TRV", """{"local_temperature":19.0,"occupied_heating_setpoint":21.0,"system_mode":"heat"}""")
        t.clock.advance(Duration.ofMinutes(6))
        t.app.tick()
        assertEquals(true, published(t))
    }

    private suspend fun ApplicationTestBuilder.post(path: String, body: String, token: String? = null): HttpResponse =
        anonymous.post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
            token?.let { bearerAuth(it) }
        }

    private fun tokenOf(body: String) = Json.parseToJsonElement(body).jsonObject["token"]!!.jsonPrimitive.content

    /** Admin "jane" and member "john" who can see every room so far; both have a phone "<name>-phone". */
    private suspend fun ApplicationTestBuilder.household(t: TestHeating): Map<String, String> {
        val jane = tokenOf(post("/api/auth/setup", """{"serviceToken":"$TEST_TOKEN","username":"jane","password":"correct horse"}""").bodyAsText())
        val johnId = Json.parseToJsonElement(
            post("/api/admin/users", """{"username":"john","password":"correct horse","admin":false}""", jane).bodyAsText(),
        ).jsonObject["id"]!!.jsonPrimitive.content
        val roomIds = t.app.rooms.list().joinToString(",") { "\"${it.id}\"" }
        assertEquals(HttpStatusCode.OK, anonymous.put("/api/admin/users/$johnId/rooms") {
            contentType(ContentType.Application.Json)
            setBody("""{"rooms":[$roomIds]}""")
            bearerAuth(jane)
        }.status)
        val john = tokenOf(post("/api/auth/login", """{"username":"john","password":"correct horse"}""").bodyAsText())
        val tokens = mapOf("jane" to jane, "john" to john)
        tokens.forEach { (name, token) ->
            assertEquals(HttpStatusCode.NoContent, anonymous.put("/api/me/devices/$name-phone") { bearerAuth(token) }.status)
        }
        return tokens
    }

    // BDD: Only admins can turn the boiler off or on
    @Test
    fun `only admins can turn the boiler off or on`() {
        val t = TestHeating()
        heatingLounge(t)
        t.api {
            val users = household(t)
            val john = users.getValue("john")
            assertEquals(HttpStatusCode.Forbidden, post("/api/boiler/off", "", john).status)
            assertEquals(HttpStatusCode.Forbidden, post("/api/boiler/on", "", john).status)
            assertEquals(HttpStatusCode.Unauthorized, post("/api/boiler/off", "").status)
            assertNull(t.app.boilerOverride.current())

            assertEquals(HttpStatusCode.OK, post("/api/boiler/off", "", users.getValue("jane")).status)
            val read = anonymous.get("/api/boiler") { bearerAuth(john) }
            assertEquals(HttpStatusCode.OK, read.status)
            assertEquals("jane", Json.parseToJsonElement(read.bodyAsText()).jsonObject["override"]!!.jsonObject["by"]!!.jsonPrimitive.content)
        }
    }

    // BDD: The apps show that the boiler is forced off
    @Test
    fun `every signed-in app is told when the boiler is forced off or back on`() {
        val t = TestHeating()
        heatingLounge(t)
        val seen = mutableListOf<heating.Change>()
        t.app.changes.subscribe { seen += it }
        turnOff(t)
        Actor.runAs("jane") { t.app.boiler.turnOn() }
        assertEquals(2, seen.count { it == heating.Change.BoilerOverrideChanged })
    }

    // BDD: The feed and the notifications tell everyone
    @Test
    fun `the feed and the notifications tell everyone`() {
        val t = TestHeating()
        heatingLounge(t)
        t.api {
            val users = household(t)
            assertEquals(HttpStatusCode.OK, post("/api/boiler/off", "", users.getValue("jane")).status)
            assertEquals(listOf("john-phone" to "Boiler turned off by admin"), t.pushed.map { it.token to it.title })
            val off = t.app.activity.recent(20).first { it.kind == "override" }
            assertEquals("Jane", off.actor)
            assertEquals(JsonPrimitive("off"), off.value)

            assertEquals(HttpStatusCode.OK, post("/api/boiler/on", "", users.getValue("jane")).status)
            assertEquals(listOf("Boiler turned off by admin", "Boiler back on"), t.pushed.map { it.title })
            assertEquals(JsonPrimitive("on"), t.app.activity.recent(20).first { it.kind == "override" }.value)

            // A member sees the event in the feed too.
            val feed = anonymous.get("/api/activity") { bearerAuth(users.getValue("john")) }.bodyAsText()
            assertTrue("\"override\"" in feed)
        }
    }

    // BDD: Each person can switch the boiler notification off
    @Test
    fun `each person can switch the boiler notification off`() {
        val t = TestHeating()
        heatingLounge(t)
        t.api {
            val users = household(t)
            val johnId = t.app.users.list().first { it.username == "john" }.id
            t.app.notificationSettings.setPrefs(johnId, t.app.notificationSettings.prefs(johnId).copy(boilerOverride = false))
            assertEquals(HttpStatusCode.OK, post("/api/boiler/off", "", users.getValue("jane")).status)
            assertEquals(emptyList(), t.pushed)
            assertNotNull(t.app.activity.recent(20).firstOrNull { it.kind == "override" }, "the feed still has it")
        }
    }
}
