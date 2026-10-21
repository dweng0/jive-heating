package heating.boost

import heating.api.ApiError
import heating.api.RoomView
import heating.rooms.Mode
import heating.testing.TestHeating
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BoostTest {

    private fun json(text: String) = Json.parseToJsonElement(text)

    private val valve = "Lounge TRV 1"

    /** Clock is Monday 10:00 BST, so the default schedule target is 16.0. */
    private fun lounge(): Pair<TestHeating, String> {
        val t = TestHeating()
        t.discover(valve)
        val room = t.app.rooms.create("Lounge")
        t.app.rooms.assignValve(room.id, valve)
        t.app.tick()
        return t to room.id
    }

    private suspend fun HttpClient.boost(zoneId: String, body: String) =
        post("/api/zones/$zoneId/boost") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.zoneOf(roomId: String) = get("/api/rooms/$roomId").body<RoomView>().zones.single()

    // BDD: A boost without a target uses the household default
    @Test
    fun `a boost without a target uses the household default`() {
        val (t, id) = lounge()
        t.app.boostSettings.update { it.copy(defaultTarget = 20.0) }
        t.api { client ->
            assertEquals(HttpStatusCode.OK, client.boost(id, """{"minutes":60}""").status)
            assertEquals(20.0, client.zoneOf(id).target)
        }
    }

    // BDD: Boost a zone
    @Test
    fun `boost a zone`() {
        val (t, id) = lounge()
        t.api { client ->
            assertEquals(16.0, client.zoneOf(id).target)
            assertEquals(HttpStatusCode.OK, client.boost(id, """{"minutes":60,"target":22.0}""").status)
            val zone = client.zoneOf(id)
            assertEquals(22.0, zone.target)
            assertEquals(22.0, zone.boost!!.target)
            assertEquals(Instant.parse("2026-10-12T10:00:00Z"), ZonedDateTime.parse(zone.boost!!.endsAt).toInstant())
        }
        assertEquals(json("""{"system_mode":"heat","occupied_heating_setpoint":22.0}"""), t.sentTo(valve).dropLast(1).last())
    }

    // BDD: Boost is mirrored to the valve's timer mode
    @Test
    fun `boost is mirrored to the valve timer mode`() {
        val (t, id) = lounge()
        t.api { client -> client.boost(id, """{"minutes":60,"target":22.0}""") }
        assertEquals(json("""{"temporary_mode_select":"timer","timer_mode_target_temp":22.0,"temporary_mode_duration":60}"""),
            t.sentTo(valve).last(),
        )
    }

    // BDD: Boost ends and the zone reverts
    @Test
    fun `boost ends and the zone reverts`() {
        val (t, id) = lounge()
        t.api { client -> client.boost(id, """{"minutes":30,"target":22.0}""") }
        t.clock.advance(Duration.ofMinutes(30))
        t.app.tick()
        assertNull(t.app.rooms.get(id).settings.boost)
        t.api { client -> assertEquals(16.0, client.zoneOf(id).target) }
        assertEquals(json("""{"system_mode":"heat","occupied_heating_setpoint":16.0}"""), t.sentTo(valve).last())
    }

    // BDD: Cancel a boost early
    @Test
    fun `cancel a boost early`() {
        val (t, id) = lounge()
        t.api { client ->
            client.boost(id, """{"minutes":60,"target":22.0}""")
            t.clock.advance(Duration.ofMinutes(20))
            assertEquals(HttpStatusCode.OK, client.delete("/api/zones/$id/boost").status)
            val zone = client.zoneOf(id)
            assertEquals(16.0, zone.target)
            assertNull(zone.boost)
        }
        assertEquals(json("""{"system_mode":"heat","occupied_heating_setpoint":16.0}"""), t.sentTo(valve).last())
    }

    // BDD: Boost duration must be 30 to 180 minutes in 30-minute steps
    @Test
    fun `boost duration must be 30 to 180 minutes in 30-minute steps`() {
        val expected = mapOf(
            30 to HttpStatusCode.OK,
            180 to HttpStatusCode.OK,
            0 to HttpStatusCode.BadRequest,
            45 to HttpStatusCode.BadRequest,
            210 to HttpStatusCode.BadRequest,
        )
        expected.forEach { (minutes, status) ->
            val (t, id) = lounge()
            t.api { client ->
                val response = client.boost(id, """{"minutes":$minutes}""")
                assertEquals(status, response.status, "minutes=$minutes")
                if (status == HttpStatusCode.BadRequest) assertNotNull(response.body<ApiError>().error)
            }
        }
    }

    // BDD: Boost a zone that is off
    @Test
    fun `boost a zone that is off`() {
        val (t, id) = lounge()
        t.app.zones.setMode(id, Mode.OFF, null)
        t.api { client ->
            client.boost(id, """{"minutes":30,"target":21.0}""")
            assertEquals(21.0, client.zoneOf(id).target)
            t.clock.advance(Duration.ofMinutes(30))
            t.app.tick()
            val zone = client.zoneOf(id)
            assertNull(zone.target)
            assertNull(zone.boost)
        }
        assertEquals(json("""{"system_mode":"off"}"""), t.sentTo(valve).last())
    }

    // BDD: Boost survives a service restart
    @Test
    fun `boost survives a service restart`() {
        val (t, id) = lounge()
        t.api { client -> client.boost(id, """{"minutes":60,"target":22.0}""") }
        t.clock.advance(Duration.ofMinutes(20))
        t.restart()
        t.api { client ->
            val zone = client.zoneOf(id)
            assertEquals(22.0, zone.boost!!.target)
            assertEquals(22.0, zone.target)
            val left = Duration.between(t.clock.instant(), ZonedDateTime.parse(zone.boost!!.endsAt).toInstant())
            assertEquals(Duration.ofMinutes(40), left)
        }
    }
}
