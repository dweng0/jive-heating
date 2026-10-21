package heating

import heating.api.ApiError
import heating.api.RoomView
import heating.rooms.Mode
import heating.testing.TestHeating
import io.ktor.client.call.body
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

class ZoneModeApiTest {

    private fun loungeWithValve(): Pair<TestHeating, String> {
        val t = TestHeating()
        t.discover("Lounge TRV 1")
        val room = t.app.rooms.create("Lounge")
        t.app.rooms.assignValve(room.id, "Lounge TRV 1")
        t.bus.clearPublished()
        return t to room.id
    }

    private suspend fun io.ktor.client.HttpClient.setMode(zoneId: String, body: String) =
        put("/api/zones/$zoneId/mode") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    // BDD: Off mode uses frost protection
    @Test
    fun `off mode uses frost protection`() {
        val (t, zoneId) = loungeWithValve()
        t.valveState("Lounge TRV 1", """{"local_temperature":15.0}""")
        t.api { client ->
            val response = client.setMode(zoneId, """{"mode":"off"}""")
            assertEquals(HttpStatusCode.OK, response.status)
            val zone = response.body<RoomView>().zones.single()
            assertEquals(Mode.OFF, zone.mode)
            assertNull(zone.target)
            assertFalse(zone.callingForHeat)
        }
        assertEquals(buildJsonObject { put("system_mode", JsonPrimitive("off")) }, t.sentTo("Lounge TRV 1").last())
    }

    // BDD: Target outside the allowed range is rejected
    @Test
    fun `target outside the allowed range is rejected`() {
        val (t, zoneId) = loungeWithValve()
        t.api { client ->
            val response = client.setMode(zoneId, """{"mode":"manual","target":35.0}""")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("target must be between 5.0 and 30.0", response.body<ApiError>().error)
        }
        assertEquals(emptyList(), t.sentTo("Lounge TRV 1"))
    }

    // BDD: Target is rounded to the nearest half degree
    @Test
    fun `target is rounded to the nearest half degree`() {
        val (t, zoneId) = loungeWithValve()
        t.api { client ->
            val zone = client.setMode(zoneId, """{"mode":"manual","target":20.3}""").body<RoomView>().zones.single()
            assertEquals(20.5, zone.target)
        }
        val expected = buildJsonObject {
            put("system_mode", JsonPrimitive("heat"))
            put("occupied_heating_setpoint", JsonPrimitive(20.5))
        }
        assertEquals(expected, t.sentTo("Lounge TRV 1").last())
    }

    @Test
    fun `unknown zone is not found`() {
        val (t, _) = loungeWithValve()
        t.api { client ->
            assertEquals(HttpStatusCode.NotFound, client.setMode("nope", """{"mode":"off"}""").status)
        }
    }

    @Test
    fun `independent zone id addresses one valve`() {
        val (t, roomId) = loungeWithValve()
        t.discover("Lounge TRV 1", "Lounge TRV 2")
        t.app.rooms.assignValve(roomId, "Lounge TRV 2")
        t.app.rooms.update(roomId) { it.copy(control = heating.rooms.Control.INDEPENDENT) }
        t.bus.clearPublished()
        t.api { client ->
            val zoneId = "$roomId:Lounge TRV 2".encodeURLPathPart()
            assertEquals(HttpStatusCode.OK, client.setMode(zoneId, """{"mode":"manual","target":22.0}""").status)
        }
        assertEquals(emptyList(), t.sentTo("Lounge TRV 1"))
        assertEquals(JsonPrimitive(22.0), t.sentTo("Lounge TRV 2").last()["occupied_heating_setpoint"])
    }
}
