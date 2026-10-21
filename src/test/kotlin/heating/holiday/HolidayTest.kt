package heating.holiday

import heating.api.ApiError
import heating.api.RoomView
import heating.api.StatusView
import heating.rooms.Mode
import heating.testing.TestHeating
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.time.Instant
import java.time.ZonedDateTime
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HolidayTest {

    private fun json(text: String) = Json.parseToJsonElement(text)

    /** One room per name with valve "<name> TRV 1". The clock starts Monday 10:00 BST: schedule target 16.0. */
    private fun house(vararg names: String): Pair<TestHeating, Map<String, String>> {
        val t = TestHeating()
        t.discover(*names.map { "$it TRV 1" }.toTypedArray())
        val ids = names.associateWith { name ->
            val room = t.app.rooms.create(name)
            t.app.rooms.assignValve(room.id, "$name TRV 1")
            room.id
        }
        t.app.tick()
        return t to ids
    }

    private suspend fun HttpClient.holiday(body: String) = put("/api/holiday") {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpClient.target(roomId: String) =
        get("/api/rooms/$roomId").body<RoomView>().zones.single().target

    private suspend fun HttpClient.status() = get("/api/status").body<StatusView>()

    private fun heat(target: Double) = json("""{"system_mode":"heat","occupied_heating_setpoint":$target}""")

    // BDD: Holiday sets every heating zone to the holiday temperature
    @Test
    fun `holiday sets every heating zone to the holiday temperature`() {
        val (t, ids) = house("Lounge", "Kitchen")
        t.app.zones.setMode(ids.getValue("Kitchen"), Mode.MANUAL, 21.0)
        t.api { client ->
            assertEquals(HttpStatusCode.OK, client.holiday("""{"until":"2026-12-28T15:00","target":12.0}""").status)
            assertEquals(12.0, client.target(ids.getValue("Lounge")))
            assertEquals(12.0, client.target(ids.getValue("Kitchen")))
        }
        assertEquals(heat(12.0), t.sentTo("Lounge TRV 1").last())
        assertEquals(heat(12.0), t.sentTo("Kitchen TRV 1").last())
    }

    // BDD: Holiday leaves zones that are off alone
    @Test
    fun `holiday leaves zones that are off alone`() {
        val (t, ids) = house("Spare")
        t.app.zones.setMode(ids.getValue("Spare"), Mode.OFF, null)
        t.api { client ->
            client.holiday("""{"until":"2026-12-28T15:00"}""")
            assertNull(client.target(ids.getValue("Spare")))
        }
        assertEquals(json("""{"system_mode":"off"}"""), t.sentTo("Spare TRV 1").last())
    }

    // BDD: Holiday ends at the return time
    @Test
    fun `holiday ends at the return time`() {
        val (t, ids) = house("Lounge", "Kitchen")
        t.clock.set(Instant.parse("2026-12-28T10:00:00Z"))
        t.app.tick()
        t.app.zones.setMode(ids.getValue("Kitchen"), Mode.MANUAL, 21.0)
        t.api { client ->
            client.holiday("""{"until":"2026-12-28T15:00"}""")
            assertEquals(12.0, client.target(ids.getValue("Lounge")))
            t.clock.set(Instant.parse("2026-12-28T15:00:00Z"))
            t.app.tick()
            assertNull(client.status().holiday)
            assertEquals(16.0, client.target(ids.getValue("Lounge")))
            assertEquals(21.0, client.target(ids.getValue("Kitchen")))
        }
        assertEquals(heat(16.0), t.sentTo("Lounge TRV 1").last())
        assertEquals(heat(21.0), t.sentTo("Kitchen TRV 1").last())
    }

    // BDD: Starting a holiday cancels active boosts
    @Test
    fun `starting a holiday cancels active boosts`() {
        val (t, ids) = house("Lounge")
        val id = ids.getValue("Lounge")
        t.api { client ->
            client.post("/api/zones/$id/boost") {
                contentType(ContentType.Application.Json)
                setBody("""{"minutes":60,"target":22.0}""")
            }
            assertNotNull(t.app.rooms.get(id).settings.boost)
            client.holiday("""{"until":"2026-12-28T15:00"}""")
            assertNull(t.app.rooms.get(id).settings.boost)
        }
    }

    // BDD: Boost is refused during a holiday
    @Test
    fun `boost is refused during a holiday`() {
        val (t, ids) = house("Lounge")
        t.api { client ->
            client.holiday("""{"until":"2026-12-28T15:00"}""")
            val response = client.post("/api/zones/${ids.getValue("Lounge")}/boost") {
                contentType(ContentType.Application.Json)
                setBody("""{"minutes":60}""")
            }
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("cannot boost during a holiday", response.body<ApiError>().error)
        }
    }

    // BDD: Holiday temperature defaults to 12
    @Test
    fun `holiday temperature defaults to 12`() {
        val (t, _) = house("Lounge")
        t.api { client ->
            client.holiday("""{"until":"2026-12-28T15:00"}""")
            assertEquals(12.0, client.status().holiday!!.target)
        }
    }

    // BDD: Holiday can start in the future
    @Test
    fun `holiday can start in the future`() {
        val (t, ids) = house("Lounge", "Kitchen")
        t.clock.set(Instant.parse("2026-12-20T09:00:00Z"))
        t.app.tick()
        t.app.zones.setMode(ids.getValue("Kitchen"), Mode.MANUAL, 21.0)
        t.api { client ->
            client.holiday("""{"from":"2026-12-21T06:00","until":"2026-12-28T15:00"}""")
            assertFalse(client.status().holiday!!.active)
            assertEquals(16.0, client.target(ids.getValue("Lounge")))
            val sentBefore = t.sentTo("Lounge TRV 1").size
            t.clock.set(Instant.parse("2026-12-21T05:59:00Z"))
            t.app.tick()
            assertEquals(sentBefore, t.sentTo("Lounge TRV 1").size)
            assertEquals(21.0, client.target(ids.getValue("Kitchen")))
            t.clock.set(Instant.parse("2026-12-21T06:00:00Z"))
            t.app.tick()
            assertEquals(12.0, client.target(ids.getValue("Lounge")))
            assertEquals(12.0, client.target(ids.getValue("Kitchen")))
            assertTrue(client.status().holiday!!.active)
            assertEquals(heat(12.0), t.sentTo("Lounge TRV 1").last())
        }
    }

    // BDD: Holiday survives a restart
    @Test
    fun `holiday survives a restart`() {
        val (t, _) = house("Lounge")
        t.api { client -> client.holiday("""{"until":"2026-12-28T15:00"}""") }
        t.restart()
        t.api { client ->
            val holiday = client.status().holiday!!
            assertTrue(holiday.active)
            assertEquals(Instant.parse("2026-12-28T15:00:00Z"), ZonedDateTime.parse(holiday.until).toInstant())
        }
    }
}
