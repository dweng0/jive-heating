package heating.groups

import heating.api.GroupView
import heating.api.RoomView
import heating.api.heatingModule
import heating.heatingJson
import heating.rooms.Control
import heating.rooms.Mode
import heating.testing.TEST_TOKEN
import heating.testing.TestHeating
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupsTest {

    /** Rooms "Bedroom 3" and "Master Bedroom", one valve each. Returns their ids. */
    private fun upstairs(t: TestHeating): Pair<String, String> {
        t.discover("Bedroom 3 TRV", "Master Bedroom TRV", "Lounge TRV")
        val bedroom3 = t.app.rooms.create("Bedroom 3")
        t.app.rooms.assignValve(bedroom3.id, "Bedroom 3 TRV")
        val master = t.app.rooms.create("Master Bedroom")
        t.app.rooms.assignValve(master.id, "Master Bedroom TRV")
        return bedroom3.id to master.id
    }

    private suspend fun HttpClient.send(method: String, path: String, body: String? = null): HttpResponse {
        val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return when (method) {
            "POST" -> post(path, build)
            "PATCH" -> patch(path, build)
            "DELETE" -> delete(path, build)
            else -> get(path, build)
        }
    }

    private suspend fun HttpClient.createGroup(name: String, vararg rooms: String) =
        send("POST", "/api/groups", """{"name":"$name","rooms":[${rooms.joinToString(",") { "\"$it\"" }}]}""")

    private suspend fun HttpClient.groups() = get("/api/groups").body<List<GroupView>>()

    private suspend fun HttpClient.rooms() = get("/api/rooms").body<List<RoomView>>()

    // BDD: New groups hold the chosen rooms
    @Test
    fun `new groups hold the chosen rooms`() {
        val t = TestHeating()
        val (bedroom3, master) = upstairs(t)
        t.api { client ->
            val response = client.createGroup("Upstairs", bedroom3, master)
            assertEquals(HttpStatusCode.Created, response.status)
            val group = client.groups().single()
            assertEquals("Upstairs", group.name)
            assertEquals(listOf(bedroom3, master), group.rooms)
        }
    }

    // BDD: Groups refuse duplicate names and unknown rooms
    @Test
    fun `groups refuse duplicate names and unknown rooms`() {
        val t = TestHeating()
        t.api { client ->
            assertEquals(HttpStatusCode.Created, client.createGroup("Upstairs").status)
            assertEquals(HttpStatusCode.Conflict, client.createGroup("upstairs").status)
            assertEquals(HttpStatusCode.BadRequest, client.createGroup("Kids", "no-such-room").status)
            assertEquals(1, client.groups().size)
        }
    }

    // BDD: A room can be in more than one group
    @Test
    fun `a room can be in more than one group`() {
        val t = TestHeating()
        val (bedroom3, _) = upstairs(t)
        t.api { client ->
            client.createGroup("Upstairs", bedroom3)
            assertEquals(HttpStatusCode.Created, client.createGroup("Kids", bedroom3).status)
            val groups = client.groups().associateBy { it.name }
            assertEquals(listOf(bedroom3), groups.getValue("Upstairs").rooms)
            assertEquals(listOf(bedroom3), groups.getValue("Kids").rooms)
        }
    }

    // BDD: Rename a group and change its rooms
    @Test
    fun `rename a group and change its rooms`() {
        val t = TestHeating()
        val (bedroom3, master) = upstairs(t)
        t.api { client ->
            val id = client.createGroup("Upstairs", bedroom3).body<GroupView>().id
            val response = client.send("PATCH", "/api/groups/$id", """{"name":"Top floor","rooms":["$bedroom3","$master"]}""")
            assertEquals(HttpStatusCode.OK, response.status)
            val group = client.groups().single()
            assertEquals("Top floor", group.name)
            assertEquals(listOf(bedroom3, master), group.rooms)
        }
    }

    // BDD: Deleting a group leaves its rooms alone
    @Test
    fun `deleting a group leaves its rooms alone`() {
        val t = TestHeating()
        val (bedroom3, _) = upstairs(t)
        t.app.zones.setMode(bedroom3, Mode.MANUAL, 21.0)
        t.api { client ->
            val id = client.createGroup("Upstairs", bedroom3).body<GroupView>().id
            assertEquals(HttpStatusCode.NoContent, client.send("DELETE", "/api/groups/$id").status)
            assertTrue(client.groups().isEmpty())
            val room = client.rooms().single { it.id == bedroom3 }
            assertEquals(Mode.MANUAL, room.mode)
            assertEquals(21.0, room.target)
        }
    }

    // BDD: Deleting a room removes it from its groups
    @Test
    fun `deleting a room removes it from its groups`() {
        val t = TestHeating()
        val (bedroom3, master) = upstairs(t)
        t.api { client ->
            client.createGroup("Upstairs", bedroom3, master)
            client.send("DELETE", "/api/rooms/$bedroom3")
            assertEquals(listOf(master), client.groups().single().rooms)
        }
    }

    // BDD: Groups list their average temperature, heating, mode and target
    @Test
    fun `groups list their average temperature, heating, mode and target`() {
        val t = TestHeating()
        val (bedroom3, master) = upstairs(t)
        t.app.zones.setMode(bedroom3, Mode.MANUAL, 21.0)
        t.app.zones.setMode(master, Mode.MANUAL, 21.0)
        t.valveState("Bedroom 3 TRV", """{"local_temperature":19.0,"running_state":"heat"}""")
        t.valveState("Master Bedroom TRV", """{"local_temperature":20.0,"running_state":"idle"}""")
        t.api { client ->
            client.createGroup("Upstairs", bedroom3, master)
            val group = client.groups().single()
            assertEquals(19.5, group.current)
            assertEquals(21.0, group.target)
            assertEquals("manual", group.mode)
            assertEquals(false, group.heating)
            assertEquals(listOf(bedroom3), group.heatingRooms)
        }
    }

    // BDD: A group lights up only when every room in it heats
    @Test
    fun `a group lights up only when every room in it heats`() {
        val t = TestHeating()
        t.discover("Bedroom 2 TRV", "Bedroom 3 TRV", "Bedroom 1 TRV")
        val kids = listOf("Bedroom 2", "Bedroom 3", "Bedroom 1").map { name ->
            t.app.rooms.create(name).also { t.app.rooms.assignValve(it.id, "${name} TRV") }.id
        }
        val (bedroom2, bedroom3, bedroom1) = kids
        t.valveState("Bedroom 2 TRV", """{"local_temperature":19.0,"running_state":"idle"}""")
        t.valveState("Bedroom 3 TRV", """{"local_temperature":19.0,"running_state":"idle"}""")
        t.valveState("Bedroom 1 TRV", """{"local_temperature":19.0,"running_state":"heat"}""")
        t.api { client ->
            client.createGroup("Kids", bedroom2, bedroom3, bedroom1)
            val one = client.groups().single()
            assertEquals(false, one.heating)
            assertEquals(listOf(bedroom1), one.heatingRooms)

            t.valveState("Bedroom 2 TRV", """{"running_state":"heat"}""")
            t.valveState("Bedroom 3 TRV", """{"running_state":"heat"}""")
            val all = client.groups().single()
            assertEquals(true, all.heating)
            assertEquals(kids, all.heatingRooms)
        }
    }

    // BDD: Group shows mixed when its rooms disagree
    @Test
    fun `group shows mixed when its rooms disagree`() {
        val t = TestHeating()
        val (bedroom3, master) = upstairs(t)
        t.app.zones.setMode(bedroom3, Mode.MANUAL, 21.0)
        t.app.zones.setMode(master, Mode.OFF, null)
        t.api { client ->
            client.createGroup("Upstairs", bedroom3, master)
            val group = client.groups().single()
            assertEquals("mixed", group.mode)
            assertNull(group.target)
        }
    }

    // BDD: Groups set every member room to manual at one temperature
    @Test
    fun `groups set every member room to manual at one temperature`() {
        val t = TestHeating()
        val (bedroom3, master) = upstairs(t)
        t.app.zones.setMode(master, Mode.OFF, null)
        val lounge = t.app.rooms.create("Lounge")
        t.app.rooms.assignValve(lounge.id, "Lounge TRV")
        t.api { client ->
            val id = client.createGroup("Upstairs", bedroom3, master).body<GroupView>().id
            val response = client.send("POST", "/api/groups/$id/control", """{"mode":"manual","target":21.0}""")
            assertEquals(HttpStatusCode.OK, response.status)
            val rooms = client.rooms().associateBy { it.name }
            listOf("Bedroom 3", "Master Bedroom").forEach {
                assertEquals(Mode.MANUAL, rooms.getValue(it).mode)
                assertEquals(21.0, rooms.getValue(it).target)
            }
            assertEquals(Mode.SCHEDULE, rooms.getValue("Lounge").mode)
        }
        listOf("Bedroom 3 TRV", "Master Bedroom TRV").forEach { v ->
            assertTrue(t.sentTo(v).last().toString().contains("\"occupied_heating_setpoint\":21.0"), "$v: ${t.sentTo(v).last()}")
        }
        assertTrue(t.sentTo("Lounge TRV").none { it.toString().contains("\"occupied_heating_setpoint\":21.0") })
    }

    // BDD: Turn a group off and back to schedule
    @Test
    fun `turn a group off and back to schedule`() {
        val t = TestHeating()
        val (bedroom3, master) = upstairs(t)
        t.api { client ->
            val id = client.createGroup("Upstairs", bedroom3, master).body<GroupView>().id
            client.send("POST", "/api/groups/$id/control", """{"mode":"off"}""")
            assertTrue(client.rooms().all { it.mode == Mode.OFF })
            client.send("POST", "/api/groups/$id/control", """{"mode":"schedule"}""")
            assertTrue(client.rooms().all { it.mode == Mode.SCHEDULE })
        }
    }

    // BDD: Groups boost every member room and cancel together
    @Test
    fun `groups boost every member room and cancel together`() {
        val t = TestHeating()
        val (bedroom3, master) = upstairs(t)
        val lounge = t.app.rooms.create("Lounge")
        t.app.rooms.assignValve(lounge.id, "Lounge TRV")
        t.api { client ->
            val id = client.createGroup("Upstairs", bedroom3, master).body<GroupView>().id
            assertEquals(HttpStatusCode.OK, client.send("POST", "/api/groups/$id/control", """{"boost":60,"target":22.0}""").status)
            val ends = t.app.now().plusMinutes(60).toInstant()
            val rooms = client.rooms().associateBy { it.name }
            listOf("Bedroom 3", "Master Bedroom").flatMap { rooms.getValue(it).zones }.forEach { z ->
                val boost = assertNotNull(z.boost)
                assertEquals(22.0, boost.target)
                assertEquals(ends, java.time.OffsetDateTime.parse(boost.endsAt).toInstant())
            }
            assertNull(rooms.getValue("Lounge").zones.single().boost)
            assertEquals(HttpStatusCode.OK, client.send("DELETE", "/api/groups/$id/boost").status)
            assertTrue(client.rooms().flatMap { it.zones }.all { it.boost == null })
        }
    }

    // BDD: Group changes follow the same rules as whole house
    @Test
    fun `group changes follow the same rules as whole house`() {
        val t = TestHeating()
        t.discover("M1", "M2")
        val bedroom3 = t.app.rooms.create("Bedroom 3")
        t.app.rooms.assignValve(bedroom3.id, "M1")
        t.app.rooms.assignValve(bedroom3.id, "M2")
        t.app.rooms.switchControl(bedroom3.id, Control.INDEPENDENT, null)
        val loft = t.app.rooms.create("Loft")
        t.api { client ->
            val id = client.createGroup("Upstairs", bedroom3.id, loft.id).body<GroupView>().id
            client.send("POST", "/api/groups/$id/control", """{"mode":"manual","target":20.0}""")
            val rooms = client.rooms().associateBy { it.name }
            rooms.getValue("Bedroom 3").zones.forEach {
                assertEquals(Mode.MANUAL, it.mode)
                assertEquals(20.0, it.target)
            }
            assertEquals(Mode.SCHEDULE, rooms.getValue("Loft").mode)

            val before = client.rooms()
            assertEquals(HttpStatusCode.BadRequest, client.send("POST", "/api/groups/$id/control", """{"mode":"manual","target":40.0}""").status)
            assertEquals(before, client.rooms())

            t.app.holiday.start(null, t.app.now().plusDays(3).toOffsetDateTime().toString(), null)
            assertEquals(HttpStatusCode.Conflict, client.send("POST", "/api/groups/$id/control", """{"boost":60}""").status)
        }
    }

    // BDD: Groups survive a restart
    @Test
    fun `groups survive a restart`() {
        val t = TestHeating()
        val (bedroom3, master) = upstairs(t)
        t.app.groups.create("Upstairs", listOf(bedroom3, master))
        val app = t.restart()
        val group = app.groups.list().single()
        assertEquals("Upstairs", group.name)
        assertEquals(listOf(bedroom3, master), group.rooms)
    }

    // BDD: Group change is pushed to connected clients
    @Test
    fun `group change is pushed to connected clients`() {
        val t = TestHeating()
        val (bedroom3, master) = upstairs(t)
        testApplication {
            application { heatingModule(t.app) }
            val ws = createClient { install(WebSockets) }.webSocketSession("/ws") { bearerAuth(TEST_TOKEN) }
            suspend fun nextGroups() = withTimeout(5000) {
                while (true) {
                    val frame = ws.incoming.receive()
                    if (frame !is Frame.Text) continue
                    val event = heatingJson.parseToJsonElement(frame.readText()).jsonObject
                    if (event["type"]?.jsonPrimitive?.content == "groups.updated") return@withTimeout event
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
            // The server subscribes the socket just after the handshake; wait until it hears changes.
            withTimeout(5000) {
                while (true) {
                    t.app.changes.emit(heating.Change.StatusChanged)
                    val frame = kotlinx.coroutines.withTimeoutOrNull(200) { ws.incoming.receive() }
                    if (frame is Frame.Text && frame.readText().contains("status.updated")) break
                }
            }
            t.app.groups.create("Upstairs", listOf(bedroom3, master))
            val created = nextGroups()
            assertEquals("Upstairs", created["groups"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)

            t.app.zones.setMode(bedroom3, Mode.MANUAL, 22.0)
            val changed = nextGroups()
            // Master Bedroom is still on its schedule, so the group now shows mixed
            assertEquals("mixed", changed["groups"]!!.jsonArray.single().jsonObject["mode"]!!.jsonPrimitive.content)
            ws.close()
        }
    }
}
