package heating

import heating.api.ApiError
import heating.api.RoomView
import heating.rooms.Block
import heating.rooms.Control
import heating.rooms.DaySchedule
import heating.rooms.Mode
import heating.rooms.WeekSchedule
import heating.testing.TestHeating
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupedIndependentTest {

    private fun room(t: TestHeating, vararg valves: String): String {
        t.discover(*valves)
        val room = t.app.rooms.create("Lounge")
        valves.forEach { t.app.rooms.assignValve(room.id, it) }
        return room.id
    }

    private suspend fun HttpClient.setMode(zoneId: String, body: String) =
        put("/api/zones/$zoneId/mode") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.patchRoom(id: String, body: String) =
        patch("/api/rooms/$id") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun heat(target: Double) = buildJsonObject {
        put("system_mode", JsonPrimitive("heat"))
        put("occupied_heating_setpoint", JsonPrimitive(target))
    }

    // BDD: Grouped room shows the average temperature of online valves
    @Test
    fun `grouped room shows the average temperature of online valves`() {
        val t = TestHeating()
        val id = room(t, "A", "B")
        t.valveState("A", """{"local_temperature":19.5}""")
        t.valveState("B", """{"local_temperature":21.0}""")
        t.api { client ->
            assertEquals(20.3, client.get("/api/rooms/$id").body<RoomView>().current)
        }
    }

    // BDD: Grouped room ignores offline valves in its temperature
    @Test
    fun `grouped room ignores offline valves in its temperature`() {
        val t = TestHeating()
        val id = room(t, "A", "B")
        t.availability("A", true)
        t.availability("B", true)
        t.valveState("A", """{"local_temperature":19.0}""")
        t.valveState("B", """{"local_temperature":22.0}""")
        t.availability("B", false)
        t.api { client ->
            assertEquals(19.0, client.get("/api/rooms/$id").body<RoomView>().current)
        }
    }

    // BDD: Grouped room sends one target to every valve
    @Test
    fun `grouped room sends one target to every valve`() {
        val t = TestHeating()
        val id = room(t, "A", "B")
        t.bus.clearPublished()
        t.api { client ->
            assertEquals(HttpStatusCode.OK, client.setMode(id, """{"mode":"manual","target":21.0}""").status)
        }
        assertEquals(heat(21.0), t.sentTo("A").last())
        assertEquals(heat(21.0), t.sentTo("B").last())
    }

    // BDD: Switch grouped to independent copies settings to each valve
    @Test
    fun `switch grouped to independent copies settings to each valve`() {
        val t = TestHeating()
        val id = room(t, "A", "B")
        val custom = DaySchedule(listOf(Block("00:00", 17.0), Block("07:00", 19.5)))
        val week = WeekSchedule(custom, custom, custom, custom, custom, custom, custom)
        t.app.rooms.updateZone(id) { it.copy(schedule = week) }
        t.api { client ->
            client.setMode(id, """{"mode":"manual","target":21.0}""")
            t.bus.clearPublished()
            val response = client.patchRoom(id, """{"control":"independent"}""")
            assertEquals(HttpStatusCode.OK, response.status)
            val view = response.body<RoomView>()
            assertEquals(Control.INDEPENDENT, view.control)
            assertEquals(listOf("A", "B"), view.zones.map { it.valves.single() })
            view.zones.forEach {
                assertEquals(Mode.MANUAL, it.mode)
                assertEquals(21.0, it.manualTarget)
            }
        }
        val zones = t.app.rooms.get(id).zones()
        assertEquals(2, zones.size)
        zones.forEach { assertEquals(week, it.settings.schedule) }
        assertEquals(heat(21.0), t.sentTo("A").last())
        assertEquals(heat(21.0), t.sentTo("B").last())
    }

    // BDD: Switch independent to grouped requires a source valve
    @Test
    fun `switch independent to grouped requires a source valve`() {
        val t = TestHeating()
        val id = room(t, "A", "B")
        t.api { client ->
            client.patchRoom(id, """{"control":"independent"}""")
            val response = client.patchRoom(id, """{"control":"grouped"}""")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("sourceValve is required", response.body<ApiError>().error)
            val unknown = client.patchRoom(id, """{"control":"grouped","sourceValve":"Z"}""")
            assertEquals(HttpStatusCode.BadRequest, unknown.status)
        }
    }

    // BDD: Switch independent to grouped takes the source valve's settings
    @Test
    fun `switch independent to grouped takes the source valve's settings`() {
        val t = TestHeating()
        val id = room(t, "A", "B")
        t.api { client ->
            client.patchRoom(id, """{"control":"independent"}""")
            client.setMode("$id:A", """{"mode":"manual","target":21.0}""")
            client.setMode("$id:B", """{"mode":"off"}""")
            val response = client.patchRoom(id, """{"control":"grouped","sourceValve":"B"}""")
            assertEquals(HttpStatusCode.OK, response.status)
            val view = response.body<RoomView>()
            assertEquals(Control.GROUPED, view.control)
            assertEquals(Mode.OFF, view.mode)
            assertEquals(1, view.zones.size)
        }
        assertEquals(buildJsonObject { put("system_mode", JsonPrimitive("off")) }, t.sentTo("A").last())
    }

    // BDD: Independent zones are controlled separately
    @Test
    fun `independent zones are controlled separately`() {
        val t = TestHeating()
        val id = room(t, "A", "B")
        t.api { client -> client.patchRoom(id, """{"control":"independent"}""") }
        t.bus.clearPublished()
        t.api { client ->
            assertEquals(HttpStatusCode.OK, client.setMode("$id:A", """{"mode":"manual","target":22.0}""").status)
        }
        assertEquals(heat(22.0), t.sentTo("A").last())
        assertTrue(t.sentTo("B").isEmpty())
    }
}
