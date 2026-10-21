package heating.auth

import heating.Change
import heating.testing.TEST_TOKEN
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import heating.testing.TestHeating
import heating.testing.anonymous
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoomAccessTest {

    private suspend fun ApplicationTestBuilder.post(path: String, body: String, token: String? = null): HttpResponse =
        anonymous.post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
            token?.let { bearerAuth(it) }
        }

    private fun tokenOf(body: String): String = Json.parseToJsonElement(body).jsonObject["token"]!!.jsonPrimitive.content

    /** Admin "jane" via setup; returns jane's session token. */
    private suspend fun ApplicationTestBuilder.admin(): String =
        tokenOf(post("/api/auth/setup", """{"serviceToken":"$TEST_TOKEN","username":"jane","password":"correct horse"}""").bodyAsText())

    /** Creates member [username] as the admin and signs them in; returns their session token. */
    private suspend fun ApplicationTestBuilder.member(adminToken: String, username: String): String {
        post("/api/admin/users", """{"username":"$username","password":"correct horse","admin":false}""", adminToken)
        return tokenOf(post("/api/auth/login", """{"username":"$username","password":"correct horse"}""").bodyAsText())
    }

    private suspend fun HttpClient.createRoom(name: String) = post("/api/rooms") {
        contentType(ContentType.Application.Json)
        setBody("""{"name":"$name"}""")
    }

    private suspend fun ApplicationTestBuilder.roomNames(token: String): Pair<HttpStatusCode, List<String>> {
        val response = anonymous.get("/api/rooms") { bearerAuth(token) }
        val names = Json.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        return response.status to names
    }

    private fun idOf(body: String): String = Json.parseToJsonElement(body).jsonObject["id"]!!.jsonPrimitive.content

    /** Users as the admin list shows them: username to the room ids they can see. */
    private suspend fun ApplicationTestBuilder.usersRooms(adminToken: String): Map<String, List<String>> =
        Json.parseToJsonElement(anonymous.get("/api/admin/users") { bearerAuth(adminToken) }.bodyAsText()).jsonArray
            .associate { u ->
                u.jsonObject["username"]!!.jsonPrimitive.content to
                    u.jsonObject["rooms"]!!.jsonArray.map { it.jsonPrimitive.content }
            }

    private suspend fun ApplicationTestBuilder.userId(adminToken: String, username: String): String =
        Json.parseToJsonElement(anonymous.get("/api/admin/users") { bearerAuth(adminToken) }.bodyAsText()).jsonArray
            .single { it.jsonObject["username"]!!.jsonPrimitive.content == username }
            .jsonObject["id"]!!.jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.setRooms(token: String, userId: String, vararg roomIds: String): HttpResponse =
        anonymous.put("/api/admin/users/$userId/rooms") {
            contentType(ContentType.Application.Json)
            setBody("""{"rooms":[${roomIds.joinToString(",") { "\"$it\"" }}]}""")
            bearerAuth(token)
        }

    // BDD: An admin chooses the rooms a member can see
    @Test
    fun `an admin chooses the rooms a member can see`() = TestHeating().api { client ->
        val lounge = idOf(client.createRoom("Lounge").bodyAsText())
        val bedroom = idOf(client.createRoom("Bedroom").bodyAsText())
        val jane = admin()
        val sam = member(jane, "sam")
        val samId = userId(jane, "sam")

        val response = setRooms(jane, samId, bedroom)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf(bedroom), Json.parseToJsonElement(response.bodyAsText()).jsonObject["rooms"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("Bedroom"), roomNames(sam).second)
        val shown = usersRooms(jane)
        assertEquals(listOf(bedroom), shown["sam"])
        assertEquals(setOf(lounge, bedroom), shown["jane"]!!.toSet())

        assertEquals(HttpStatusCode.OK, setRooms(jane, samId).status)
        assertEquals(emptyList(), roomNames(sam).second)
    }

    // BDD: Room access refuses unknown rooms and admin users
    @Test
    fun `room access refuses unknown rooms and admin users`() = TestHeating().api { client ->
        val bedroom = idOf(client.createRoom("Bedroom").bodyAsText())
        val jane = admin()
        val sam = member(jane, "sam")
        val samId = userId(jane, "sam")

        assertEquals(HttpStatusCode.BadRequest, setRooms(jane, samId, bedroom, "no-such-room").status)
        assertEquals(emptyList(), usersRooms(jane)["sam"])
        assertEquals(HttpStatusCode.Conflict, setRooms(jane, userId(jane, "jane"), bedroom).status)
        assertEquals(HttpStatusCode.NotFound, setRooms(jane, "no-such-user", bedroom).status)
        assertEquals(HttpStatusCode.Forbidden, setRooms(sam, samId, bedroom).status)
        assertEquals(emptyList(), usersRooms(jane)["sam"])
    }

    /** Sends [method] [path] as [token], with a JSON [body] when given. */
    private suspend fun ApplicationTestBuilder.send(token: String, method: HttpMethod, path: String, body: String? = null): HttpResponse =
        anonymous.request(path) {
            this.method = method
            bearerAuth(token)
            body?.let {
                contentType(ContentType.Application.Json)
                setBody(it)
            }
        }

    /** Rooms "Lounge" and "Bedroom", each with a valve, and member "sam" who can see only "Bedroom". */
    private class House(val t: TestHeating, val lounge: String, val bedroom: String)

    private fun house(): House {
        val t = TestHeating()
        t.discover("Lounge TRV", "Bedroom TRV")
        val lounge = t.app.rooms.create("Lounge").id
        val bedroom = t.app.rooms.create("Bedroom").id
        t.app.rooms.assignValve(lounge, "Lounge TRV")
        t.app.rooms.assignValve(bedroom, "Bedroom TRV")
        t.app.tick()
        return House(t, lounge, bedroom)
    }

    /** Admin "jane" and member "sam", who can see [rooms]; returns their tokens. */
    private suspend fun ApplicationTestBuilder.janeAndSam(vararg rooms: String): Pair<String, String> {
        val jane = admin()
        val sam = member(jane, "sam")
        assertEquals(HttpStatusCode.OK, setRooms(jane, userId(jane, "sam"), *rooms).status)
        return jane to sam
    }

    private suspend fun ApplicationTestBuilder.roomJson(token: String, id: String) =
        Json.parseToJsonElement(send(token, HttpMethod.Get, "/api/rooms/$id").bodyAsText()).jsonObject

    // BDD: A member controls the rooms they can see
    @Test
    fun `a member controls the rooms they can see`() {
        val h = house()
        h.t.api {
            val (_, sam) = janeAndSam(h.bedroom)

            val manual = send(sam, HttpMethod.Put, "/api/zones/${h.bedroom}/mode", """{"mode":"manual","target":21.0}""")
            assertEquals(HttpStatusCode.OK, manual.status)
            val room = roomJson(sam, h.bedroom)
            assertEquals("manual", room["mode"]!!.jsonPrimitive.content)
            assertEquals(21.0, room["target"]!!.jsonPrimitive.content.toDouble())

            assertEquals(HttpStatusCode.OK, send(sam, HttpMethod.Post, "/api/zones/${h.bedroom}/boost", """{"minutes":30}""").status)
            assertNotNull(roomJson(sam, h.bedroom)["zones"]!!.jsonArray.single().jsonObject["boost"]?.takeIf { it !is JsonNull })

            assertEquals(HttpStatusCode.OK, send(sam, HttpMethod.Delete, "/api/zones/${h.bedroom}/boost").status)
            assertTrue(roomJson(sam, h.bedroom)["zones"]!!.jsonArray.single().jsonObject["boost"].let { it == null || it is JsonNull })

            assertEquals(HttpStatusCode.OK, send(sam, HttpMethod.Get, "/api/zones/${h.bedroom}/schedule").status)
            assertEquals(HttpStatusCode.OK, send(sam, HttpMethod.Patch, "/api/rooms/${h.bedroom}", """{"smartTemperatureControl":false}""").status)
            assertEquals("false", roomJson(sam, h.bedroom)["smartTemperatureControl"]!!.jsonPrimitive.content)
        }
    }

    // BDD: A room a member cannot see answers as if it does not exist
    @Test
    fun `a room a member cannot see answers as if it does not exist`() {
        val h = house()
        h.t.discover("Lounge TRV", "Bedroom TRV", "Spare TRV", sensors = listOf("Lounge Smoke"))
        h.t.api {
            val (jane, sam) = janeAndSam(h.bedroom)
            val before = roomJson(jane, h.lounge)
            val lounge = h.lounge
            val requests = listOf(
                Triple(HttpMethod.Get, "/api/rooms/$lounge", null),
                Triple(HttpMethod.Patch, "/api/rooms/$lounge", """{"control":"independent"}"""),
                Triple(HttpMethod.Patch, "/api/rooms/$lounge", """{"smartTemperatureControl":false}"""),
                Triple(HttpMethod.Patch, "/api/rooms/$lounge", """{"name":"Den"}"""),
                Triple(HttpMethod.Delete, "/api/rooms/$lounge", null),
                Triple(HttpMethod.Put, "/api/rooms/$lounge/valves/Spare TRV", null),
                Triple(HttpMethod.Delete, "/api/rooms/$lounge/valves/Lounge TRV", null),
                Triple(HttpMethod.Put, "/api/rooms/$lounge/sensor/Lounge Smoke", null),
                Triple(HttpMethod.Delete, "/api/rooms/$lounge/sensor/Lounge Smoke", null),
                Triple(HttpMethod.Post, "/api/zones/$lounge/boost", """{"minutes":30}"""),
                Triple(HttpMethod.Delete, "/api/zones/$lounge/boost", null),
                Triple(HttpMethod.Put, "/api/zones/$lounge/mode", """{"mode":"manual","target":25.0}"""),
                Triple(HttpMethod.Put, "/api/zones/$lounge:Lounge TRV/mode", """{"mode":"manual","target":25.0}"""),
                Triple(HttpMethod.Get, "/api/zones/$lounge/schedule", null),
                Triple(HttpMethod.Put, "/api/zones/$lounge/schedule/monday", """{"blocks":[]}"""),
                Triple(HttpMethod.Post, "/api/zones/$lounge/schedule/copy", """{"from":"monday","to":["tuesday"]}"""),
            )
            for ((method, path, body) in requests) {
                assertEquals(HttpStatusCode.NotFound, send(sam, method, path, body).status, "${method.value} $path")
            }
            assertEquals(before, roomJson(jane, h.lounge))
            assertEquals(listOf("Lounge"), roomNames(jane).second.filter { it == "Lounge" })
        }
    }

    // BDD: Only admins see the whole house
    @Test
    fun `only admins see the whole house`() {
        val h = house()
        val group = h.t.app.groups.create("Upstairs", listOf(h.bedroom)).id
        h.t.api {
            val (jane, sam) = janeAndSam(h.bedroom)
            val requests = listOf(
                Triple(HttpMethod.Post, "/api/house", """{"mode":"off"}"""),
                Triple(HttpMethod.Delete, "/api/house/boost", null),
                Triple(HttpMethod.Get, "/api/groups", null),
                Triple(HttpMethod.Post, "/api/groups", """{"name":"Mine","rooms":["${h.bedroom}"]}"""),
                Triple(HttpMethod.Patch, "/api/groups/$group", """{"name":"Up"}"""),
                Triple(HttpMethod.Delete, "/api/groups/$group", null),
                Triple(HttpMethod.Post, "/api/groups/$group/control", """{"mode":"off"}"""),
                Triple(HttpMethod.Delete, "/api/groups/$group/boost", null),
                Triple(HttpMethod.Get, "/api/dashboard", null),
                Triple(HttpMethod.Get, "/api/dashboard?period=today", null),
                Triple(HttpMethod.Get, "/api/status", null),
                Triple(HttpMethod.Put, "/api/holiday", """{"until":"2026-10-20"}"""),
                Triple(HttpMethod.Delete, "/api/holiday", null),
                Triple(HttpMethod.Get, "/api/valves", null),
                Triple(HttpMethod.Get, "/api/valves?unassigned=true", null),
                Triple(HttpMethod.Patch, "/api/valves/Bedroom TRV", """{"childLock":true}"""),
                Triple(HttpMethod.Get, "/api/sensors", null),
            )
            for ((method, path, body) in requests) {
                assertEquals(HttpStatusCode.Forbidden, send(sam, method, path, body).status, "${method.value} $path")
            }
            assertEquals(HttpStatusCode.OK, send(jane, HttpMethod.Get, "/api/status").status)
            assertEquals(HttpStatusCode.OK, send(jane, HttpMethod.Get, "/api/groups").status)
            assertEquals(HttpStatusCode.OK, send(jane, HttpMethod.Get, "/api/valves").status)
            assertEquals(HttpStatusCode.OK, send(jane, HttpMethod.Get, "/api/sensors").status)
            assertEquals(listOf("Upstairs"), h.t.app.groups.list().map { it.name })
        }
    }

    // BDD: Only admins create, rename, delete or fit out rooms
    @Test
    fun `only admins create, rename, delete or fit out rooms`() {
        val h = house()
        h.t.discover("Lounge TRV", "Bedroom TRV", "Spare TRV", sensors = listOf("Bedroom Smoke"))
        h.t.app.roomSensors.assign(h.bedroom, "Bedroom Smoke")
        h.t.api {
            val (jane, sam) = janeAndSam(h.bedroom)
            val before = roomJson(jane, h.bedroom)
            val bedroom = h.bedroom
            val requests = listOf(
                Triple(HttpMethod.Post, "/api/rooms", """{"name":"Study"}"""),
                Triple(HttpMethod.Patch, "/api/rooms/$bedroom", """{"name":"Den"}"""),
                Triple(HttpMethod.Patch, "/api/rooms/$bedroom", """{"name":"Den","smartTemperatureControl":false}"""),
                Triple(HttpMethod.Delete, "/api/rooms/$bedroom", null),
                Triple(HttpMethod.Put, "/api/rooms/$bedroom/valves/Spare TRV", null),
                Triple(HttpMethod.Delete, "/api/rooms/$bedroom/valves/Bedroom TRV", null),
                Triple(HttpMethod.Put, "/api/rooms/$bedroom/sensor/Bedroom Smoke", null),
                Triple(HttpMethod.Delete, "/api/rooms/$bedroom/sensor/Bedroom Smoke", null),
            )
            for ((method, path, body) in requests) {
                assertEquals(HttpStatusCode.Forbidden, send(sam, method, path, body).status, "${method.value} $path")
            }
            assertEquals(before, roomJson(jane, h.bedroom))
            assertEquals(setOf("Lounge", "Bedroom"), roomNames(jane).second.toSet())

            assertEquals(HttpStatusCode.OK, send(jane, HttpMethod.Patch, "/api/rooms/$bedroom", """{"name":"Den"}""").status)
            assertEquals(HttpStatusCode.Created, send(jane, HttpMethod.Post, "/api/rooms", """{"name":"Study"}""").status)
        }
    }

    // BDD: Making a member an admin gives them the whole house
    @Test
    fun `making a member an admin gives them the whole house`() {
        val h = house()
        h.t.api {
            val (jane, sam) = janeAndSam(h.bedroom)
            val samId = userId(jane, "sam")

            assertEquals(HttpStatusCode.OK, send(jane, HttpMethod.Patch, "/api/admin/users/$samId", """{"admin":true}""").status)
            assertEquals(setOf("Lounge", "Bedroom"), roomNames(sam).second.toSet())
            assertEquals(HttpStatusCode.OK, send(sam, HttpMethod.Get, "/api/status").status)
            assertEquals(setOf(h.lounge, h.bedroom), usersRooms(jane)["sam"]!!.toSet())

            assertEquals(HttpStatusCode.OK, send(jane, HttpMethod.Patch, "/api/admin/users/$samId", """{"admin":false}""").status)
            assertEquals(listOf("Bedroom"), roomNames(sam).second)
            assertEquals(HttpStatusCode.Forbidden, send(sam, HttpMethod.Get, "/api/status").status)

            // An admin who never had a list of rooms starts with none when made a member.
            post("/api/admin/users", """{"username":"kim","password":"correct horse","admin":true}""", jane)
            val kim = tokenOf(post("/api/auth/login", """{"username":"kim","password":"correct horse"}""").bodyAsText())
            assertEquals(HttpStatusCode.OK, send(jane, HttpMethod.Patch, "/api/admin/users/${userId(jane, "kim")}", """{"admin":false}""").status)
            assertEquals(emptyList(), roomNames(kim).second)
        }
    }

    // BDD: New rooms are not given to members
    @Test
    fun `new rooms are not given to members`() {
        val h = house()
        h.t.api {
            val (jane, sam) = janeAndSam(h.bedroom)
            val study = idOf(send(jane, HttpMethod.Post, "/api/rooms", """{"name":"Study"}""").bodyAsText())

            assertEquals(listOf("Bedroom"), roomNames(sam).second)
            assertEquals(HttpStatusCode.NotFound, send(sam, HttpMethod.Get, "/api/rooms/$study").status)

            setRooms(jane, userId(jane, "sam"), h.bedroom, study)
            assertEquals(setOf("Bedroom", "Study"), roomNames(sam).second.toSet())
        }
    }

    // BDD: Deleting a room takes it off every member's list
    @Test
    fun `deleting a room takes it off every member's list`() {
        val h = house()
        val study = h.t.app.rooms.create("Study").id
        h.t.api {
            val (jane, sam) = janeAndSam(h.bedroom, study)
            assertEquals(HttpStatusCode.NoContent, send(jane, HttpMethod.Delete, "/api/rooms/$study").status)

            assertEquals(listOf("Bedroom"), roomNames(sam).second)
            assertEquals(listOf(h.bedroom), h.t.app.users.list().single { it.username == "sam" }.rooms)

            assertEquals(HttpStatusCode.Created, send(jane, HttpMethod.Post, "/api/rooms", """{"name":"Study"}""").status)
            assertEquals(listOf("Bedroom"), roomNames(sam).second)
            assertEquals(listOf(h.bedroom), usersRooms(jane)["sam"])
        }
    }

    // BDD: Members already signed up keep every room when room access arrives
    @Test
    fun `members already signed up keep every room when room access arrives`() {
        val h = house()
        var jane = ""
        var john = ""
        h.t.api {
            jane = admin()
            john = member(jane, "john")
        }
        // Rewrite the store as it was before room access: no "rooms" on any user.
        val users = Json.parseToJsonElement(h.t.app.store.get("users")!!).jsonArray
        val legacy = JsonArray(users.map { JsonObject(it.jsonObject - "rooms") })
        h.t.app.store.put("users", legacy.toString())

        h.t.restart()
        h.t.app.rooms.create("Study")
        h.t.api {
            assertEquals(setOf("Lounge", "Bedroom"), roomNames(john).second.toSet())
            assertEquals(setOf(h.lounge, h.bedroom), h.t.app.users.list().single { it.username == "john" }.rooms!!.toSet())
            assertEquals(null, h.t.app.users.list().single { it.username == "jane" }.rooms)

            val sam = member(jane, "sam")
            assertEquals(emptyList(), roomNames(sam).second)
        }
    }

    // BDD: Room access survives a restart
    @Test
    fun `room access survives a restart`() {
        val h = house()
        var sam = ""
        h.t.api { sam = janeAndSam(h.bedroom).second }

        h.t.restart()
        h.t.api {
            assertEquals(listOf("Bedroom"), roomNames(sam).second)
            assertEquals(HttpStatusCode.NotFound, send(sam, HttpMethod.Get, "/api/rooms/${h.lounge}").status)
        }
    }

    // BDD: A new member sees no rooms
    @Test
    fun `a new member sees no rooms`() = TestHeating().api { client ->
        client.createRoom("Lounge")
        client.createRoom("Bedroom")
        val jane = admin()
        val sam = member(jane, "sam")

        assertEquals(HttpStatusCode.OK to emptyList(), roomNames(sam))
        assertEquals(setOf("Lounge", "Bedroom"), roomNames(jane).second.toSet())
    }

    // BDD: Members get history only for rooms they can see
    @Test
    fun `members get history only for rooms they can see`() {
        val h = house()
        val now = h.t.clock.instant()
        h.t.app.history.addPoints(
            listOf(
                heating.history.RoomPoint(now.minusSeconds(600), h.lounge, 19.0, 21.0),
                heating.history.RoomPoint(now.minusSeconds(600), h.bedroom, 17.0, 18.0),
            ),
        )
        h.t.app.history.boilerChanged("heating", now.minusSeconds(900))
        h.t.api {
            val (jane, sam) = janeAndSam(h.bedroom)
            fun names(o: JsonObject) = o["rooms"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

            val forSam = send(sam, HttpMethod.Get, "/api/history")
            assertEquals(HttpStatusCode.OK, forSam.status)
            val samHistory = Json.parseToJsonElement(forSam.bodyAsText()).jsonObject
            assertEquals(listOf("Bedroom"), names(samHistory))
            assertEquals(JsonArray(emptyList()), samHistory["house"])
            assertEquals(JsonArray(emptyList()), samHistory["boiler"])

            val oneRoom = send(sam, HttpMethod.Get, "/api/history?room=${h.bedroom}")
            assertEquals(HttpStatusCode.OK, oneRoom.status)
            assertEquals(listOf("Bedroom"), names(Json.parseToJsonElement(oneRoom.bodyAsText()).jsonObject))
            assertEquals(HttpStatusCode.NotFound, send(sam, HttpMethod.Get, "/api/history?room=${h.lounge}").status)
            assertEquals(HttpStatusCode.NotFound, send(sam, HttpMethod.Get, "/api/history?room=no-such-room").status)

            val janeHistory = Json.parseToJsonElement(send(jane, HttpMethod.Get, "/api/history").bodyAsText()).jsonObject
            assertEquals(setOf("Lounge", "Bedroom"), names(janeHistory).toSet())
            assertTrue(janeHistory["house"]!!.jsonArray.isNotEmpty())
            assertTrue(janeHistory["boiler"]!!.jsonArray.isNotEmpty())
            val janeLounge = Json.parseToJsonElement(send(jane, HttpMethod.Get, "/api/history?room=${h.lounge}").bodyAsText()).jsonObject
            assertEquals(listOf("Lounge"), names(janeLounge))
        }
    }

    /** Opens /ws as [token]. */
    private suspend fun ApplicationTestBuilder.socket(token: String): DefaultClientWebSocketSession =
        createClient { install(WebSockets) }.webSocketSession("/ws") { bearerAuth(token) }

    /** The next live event's type and room id (from "room" or "roomId"). */
    private suspend fun DefaultClientWebSocketSession.nextEvent(): Pair<String, String?> = withTimeout(5000) {
        var event: JsonObject? = null
        while (event == null) {
            val frame = incoming.receive()
            if (frame is Frame.Text) event = Json.parseToJsonElement(frame.readText()).jsonObject
        }
        val roomId = event["room"]?.takeIf { it !is JsonNull }?.jsonObject?.get("id")?.jsonPrimitive?.content
            ?: event["roomId"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        event["type"]!!.jsonPrimitive.content to roomId
    }

    // BDD: Live updates only carry rooms a member can see
    @Test
    fun `live updates only carry rooms a member can see`() {
        val h = house()
        h.t.app.groups.create("Everywhere", listOf(h.lounge, h.bedroom))
        h.t.api {
            val (jane, sam) = janeAndSam(h.bedroom)
            val samSocket = socket(sam)
            val janeSocket = socket(jane)
            // Both sockets are listening once each has had this.
            h.t.app.changes.emit(Change.RoomChanged(h.bedroom))
            assertEquals("room.updated" to h.bedroom, samSocket.nextEvent())
            assertEquals("room.updated" to h.bedroom, janeSocket.nextEvent())
            assertEquals("groups.updated" to null, janeSocket.nextEvent())

            h.t.valveState("Lounge TRV", """{"local_temperature":20.0}""")
            h.t.app.changes.emit(Change.StatusChanged)
            h.t.app.changes.emit(Change.GroupsChanged)
            h.t.app.changes.emit(Change.RoomDeleted("no-such-room"))
            h.t.valveState("Bedroom TRV", """{"local_temperature":17.0}""")
            // Lounge, status, groups and the unseen deletion never reach sam: the next event is Bedroom's.
            assertEquals("room.updated" to h.bedroom, samSocket.nextEvent())
            val janeSaw = mutableListOf<Pair<String, String?>>()
            while (janeSaw.lastOrNull() != ("room.updated" to h.bedroom)) janeSaw += janeSocket.nextEvent()
            assertTrue(("room.updated" to h.lounge) in janeSaw, "$janeSaw")
            assertTrue(("status.updated" to null) in janeSaw, "$janeSaw")
            assertTrue(("valve.updated" to null) in janeSaw, "$janeSaw")

            assertEquals(HttpStatusCode.OK, setRooms(jane, userId(jane, "sam"), h.bedroom, h.lounge).status)
            assertEquals("access.updated" to null, samSocket.nextEvent())
            h.t.valveState("Lounge TRV", """{"local_temperature":20.5}""")
            assertEquals("room.updated" to h.lounge, samSocket.nextEvent())

            // Taken away again: Lounge stops at once.
            assertEquals(HttpStatusCode.OK, setRooms(jane, userId(jane, "sam")).status)
            assertEquals("access.updated" to null, samSocket.nextEvent())
            h.t.valveState("Lounge TRV", """{"local_temperature":21.0}""")
            assertEquals(HttpStatusCode.OK, setRooms(jane, userId(jane, "sam"), h.bedroom).status)
            assertEquals("access.updated" to null, samSocket.nextEvent())
        }
    }
}
