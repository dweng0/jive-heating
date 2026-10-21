package heating

import heating.api.CreateRoom
import heating.api.PatchRoom
import heating.api.RoomView
import heating.rooms.Control
import heating.rooms.Mode
import heating.testing.TestHeating
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomsTest {

    private fun valvePath(roomId: String, valve: String) = "/api/rooms/$roomId/valves/${valve.encodeURLPathPart()}"

    // BDD: Create a room
    @Test
    fun `create a room`() = TestHeating().api { client ->
        val response = client.post("/api/rooms") {
            contentType(ContentType.Application.Json)
            setBody(CreateRoom("Lounge"))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val room = response.body<RoomView>()
        assertEquals("Lounge", room.name)
        assertEquals(Control.GROUPED, room.control)
        assertEquals(Mode.SCHEDULE, room.mode)
        assertEquals(emptyList(), room.valves)
    }

    // BDD: Room names must be unique
    @Test
    fun `room names must be unique`() {
        val t = TestHeating()
        t.app.rooms.create("Lounge")
        t.api { client ->
            val response = client.post("/api/rooms") {
                contentType(ContentType.Application.Json)
                setBody(CreateRoom("lounge"))
            }
            assertEquals(HttpStatusCode.Conflict, response.status)
        }
    }

    // BDD: Rename a room
    @Test
    fun `rename a room`() {
        val t = TestHeating()
        val room = t.app.rooms.create("Lounge")
        t.api { client ->
            val response = client.patch("/api/rooms/${room.id}") {
                contentType(ContentType.Application.Json)
                setBody(PatchRoom(name = "Living Room"))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("Living Room", t.app.rooms.get(room.id).name)
        }
    }

    // BDD: Assign a valve to a room
    @Test
    fun `assign a valve to a room`() {
        val t = TestHeating()
        t.discover("Lounge TRV 1")
        val room = t.app.rooms.create("Lounge")
        t.api { client ->
            val response = client.put(valvePath(room.id, "Lounge TRV 1"))
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(listOf("Lounge TRV 1"), t.app.rooms.get(room.id).valves)
            assertEquals("Lounge", t.app.rooms.roomOf("Lounge TRV 1")?.name)
        }
    }

    // BDD: A valve can only be in one room
    @Test
    fun `a valve can only be in one room`() {
        val t = TestHeating()
        t.discover("Lounge TRV 1")
        val lounge = t.app.rooms.create("Lounge")
        val kitchen = t.app.rooms.create("Kitchen")
        t.app.rooms.assignValve(lounge.id, "Lounge TRV 1")
        t.api { client ->
            assertEquals(HttpStatusCode.Conflict, client.put(valvePath(kitchen.id, "Lounge TRV 1")).status)
        }
    }

    // BDD: Remove a valve from a room
    @Test
    fun `remove a valve from a room`() {
        val t = TestHeating()
        t.discover("Lounge TRV 1")
        val room = t.app.rooms.create("Lounge")
        t.app.rooms.assignValve(room.id, "Lounge TRV 1")
        t.api { client ->
            assertEquals(HttpStatusCode.OK, client.delete(valvePath(room.id, "Lounge TRV 1")).status)
            assertNull(t.app.rooms.roomOf("Lounge TRV 1"))
            assertEquals(
                buildJsonObject { put("system_mode", JsonPrimitive("off")) },
                t.sentTo("Lounge TRV 1").last(),
            )
        }
    }

    // BDD: Delete a room with valves
    @Test
    fun `delete a room with valves`() {
        val t = TestHeating()
        t.discover("Lounge TRV 1", "Lounge TRV 2")
        val room = t.app.rooms.create("Lounge")
        t.app.rooms.assignValve(room.id, "Lounge TRV 1")
        t.app.rooms.assignValve(room.id, "Lounge TRV 2")
        t.api { client ->
            assertEquals(HttpStatusCode.NoContent, client.delete("/api/rooms/${room.id}").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/rooms/${room.id}").status)
            val off = buildJsonObject { put("system_mode", JsonPrimitive("off")) }
            listOf("Lounge TRV 1", "Lounge TRV 2").forEach {
                assertNull(t.app.rooms.roomOf(it))
                assertEquals(off, t.sentTo(it).last())
            }
        }
    }

    // BDD: Newly assigned valve gets default settings
    @Test
    fun `newly assigned valve gets default settings`() {
        val t = TestHeating()
        t.discover("Lounge TRV 1")
        val room = t.app.rooms.create("Lounge")
        t.app.rooms.assignValve(room.id, "Lounge TRV 1")
        val expected = buildJsonObject {
            put("open_window", JsonPrimitive("ON"))
            put("frost_protection_temperature", JsonPrimitive(7))
            put("smart_temperature_control", JsonPrimitive(true))
            put("child_lock", JsonPrimitive("UNLOCK"))
        }
        assertTrue(expected in t.sentTo("Lounge TRV 1"))
    }

    // BDD: A valve reporting the wrong frost protection is put back to 7
    @Test
    fun `a valve reporting the wrong frost protection is put back to 7`() {
        val t = TestHeating()
        t.discover("Lounge TRV 1", "Spare TRV")
        val room = t.app.rooms.create("Lounge")
        t.app.rooms.assignValve(room.id, "Lounge TRV 1")
        val fix = buildJsonObject { put("frost_protection_temperature", JsonPrimitive(7)) }

        t.valveState("Lounge TRV 1", """{"frost_protection_temperature":29.5}""")
        t.valveState("Lounge TRV 1", """{"frost_protection_temperature":29.5,"local_temperature":19.0}""")
        assertEquals(1, t.sentTo("Lounge TRV 1").count { it == fix })

        t.valveState("Spare TRV", """{"frost_protection_temperature":29.5}""")
        assertTrue(t.sentTo("Spare TRV").none { it == fix })
    }

    // BDD: Room list shows temperature, target, mode and heating
    @Test
    fun `room list shows temperature target mode and heating`() {
        val t = TestHeating()
        t.discover("Lounge TRV 1")
        val room = t.app.rooms.create("Lounge")
        t.app.rooms.assignValve(room.id, "Lounge TRV 1")
        t.app.rooms.update(room.id) { it.copy(settings = it.settings.copy(mode = Mode.MANUAL, manualTarget = 21.0)) }
        t.valveState("Lounge TRV 1", """{"local_temperature":19.5,"running_state":"heat"}""")
        t.api { client ->
            val lounge = client.get("/api/rooms").body<List<RoomView>>().single()
            assertEquals(19.5, lounge.current)
            assertEquals(21.0, lounge.target)
            assertEquals(Mode.MANUAL, lounge.mode)
            assertTrue(lounge.heating)
            assertFalse(lounge.notices.isNotEmpty())
        }
    }
}
