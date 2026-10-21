package heating.rooms

import heating.api.ApiError
import heating.api.RoomView
import heating.testing.MutableClock
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ScheduleBlocksTest {

    private val valve = "Lounge TRV 1"

    private fun lounge(at: String): Pair<TestHeating, String> {
        val t = TestHeating(clock = MutableClock(Instant.parse(at)))
        t.discover(valve)
        val room = t.app.rooms.create("Lounge")
        t.app.rooms.assignValve(room.id, valve)
        return t to room.id
    }

    private suspend fun HttpClient.saveDay(zoneId: String, day: String, body: String) =
        put("/api/zones/$zoneId/schedule/$day") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun heat(target: Double) = buildJsonObject {
        put("system_mode", JsonPrimitive("heat"))
        put("occupied_heating_setpoint", JsonPrimitive(target))
    }

    private fun TestHeating.setDay(zoneId: String, day: DayOfWeek, vararg blocks: Block) {
        app.rooms.updateZone(zoneId) { it.copy(schedule = it.schedule.with(day, DaySchedule(blocks.toList()))) }
    }

    // BDD: Block change sends the new target to valves
    @Test
    fun `block change sends the new target to valves`() {
        // Monday 06:29 BST is 05:29 UTC
        val (t, zoneId) = lounge("2026-10-12T05:29:00Z")
        t.api { client ->
            val body = """{"blocks":[{"start":"00:00","target":16.0},{"start":"06:30","target":20.0}]}"""
            assertEquals(HttpStatusCode.OK, client.saveDay(zoneId, "monday", body).status)
        }
        t.app.tick()
        t.bus.clearPublished()

        t.clock.advance(Duration.ofMinutes(1))
        t.app.tick()

        assertEquals(listOf(heat(20.0)), t.sentTo(valve))
        t.app.tick()
        assertEquals(1, t.sentTo(valve).size)
    }

    // BDD: A day may have at most six blocks
    @Test
    fun `a day may have at most six blocks`() {
        val (t, zoneId) = lounge("2026-10-12T09:00:00Z")
        val blocks = listOf("00:00", "01:00", "02:00", "03:00", "04:00", "05:00", "06:00")
            .joinToString(",") { """{"start":"$it","target":18.0}""" }
        t.api { client ->
            val response = client.saveDay(zoneId, "monday", """{"blocks":[$blocks]}""")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("a day may have at most 6 blocks", response.body<ApiError>().error)
        }
    }

    // BDD: A day must start at midnight
    @Test
    fun `a day must start at midnight`() {
        val (t, zoneId) = lounge("2026-10-12T09:00:00Z")
        t.api { client ->
            val response = client.saveDay(zoneId, "monday", """{"blocks":[{"start":"06:00","target":18.0}]}""")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("first block must start at 00:00", response.body<ApiError>().error)
        }
    }

    // BDD: Blocks must be in time order
    @Test
    fun `blocks must be in time order`() {
        val (t, zoneId) = lounge("2026-10-12T09:00:00Z")
        t.api { client ->
            val body = """{"blocks":[{"start":"00:00","target":16.0},{"start":"09:00","target":20.0},{"start":"08:00","target":18.0}]}"""
            val response = client.saveDay(zoneId, "monday", body)
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("blocks must be in increasing time order", response.body<ApiError>().error)
        }
    }

    // BDD: Copy one day to other days
    @Test
    fun `copy one day to other days`() {
        val (t, zoneId) = lounge("2026-10-12T09:00:00Z")
        t.api { client ->
            client.saveDay(zoneId, "monday", """{"blocks":[{"start":"00:00","target":16.0},{"start":"07:00","target":21.0}]}""")
            val response = client.post("/api/zones/$zoneId/schedule/copy") {
                contentType(ContentType.Application.Json)
                setBody("""{"from":"monday","to":["tuesday","wednesday","thursday"]}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val schedule = client.get("/api/zones/$zoneId/schedule").body<WeekSchedule>()
            val copied = listOf(Block("00:00", 16.0), Block("07:00", 21.0))
            assertEquals(copied, schedule.tuesday.blocks)
            assertEquals(copied, schedule.wednesday.blocks)
            assertEquals(copied, schedule.thursday.blocks)
            assertEquals(
                listOf(Block("00:00", 16.0), Block("06:30", 20.0), Block("08:30", 16.0), Block("16:30", 20.0), Block("22:30", 16.0)),
                schedule.friday.blocks,
            )
        }
    }

    // BDD: Schedule uses the configured time zone
    @Test
    fun `schedule uses the configured time zone`() {
        // Monday 06:30 UTC is 07:30 BST
        val (t, zoneId) = lounge("2026-10-12T06:30:00Z")
        t.setDay(zoneId, DayOfWeek.MONDAY, Block("00:00", 16.0), Block("07:00", 20.0))
        t.api { client ->
            val room = client.get("/api/rooms").body<List<RoomView>>().single()
            assertEquals(20.0, room.zones.single().target)
        }
    }

    // BDD: Block inside the spring-forward gap starts when the clocks jump
    @Test
    fun `block inside the spring-forward gap starts when the clocks jump`() {
        // Sunday 2026-03-29: 00:59 GMT, one minute later it is 02:00 BST
        val (t, zoneId) = lounge("2026-03-29T00:59:00Z")
        t.setDay(zoneId, DayOfWeek.SUNDAY, Block("00:00", 16.0), Block("01:30", 20.0))
        t.app.tick()
        t.bus.clearPublished()

        t.clock.advance(Duration.ofMinutes(1))
        t.app.tick()

        assertEquals(20.0, EffectiveTarget.of(t.app.rooms.get(zoneId).settings, t.app.now()))
        assertEquals(listOf(heat(20.0)), t.sentTo(valve))
    }

    // BDD: Block in the repeated autumn hour applies once
    @Test
    fun `block in the repeated autumn hour applies once`() {
        // Sunday 2026-10-25: clocks go back at 02:00 BST (01:00 UTC); 00:30 UTC is the first 01:30.
        val (t, zoneId) = lounge("2026-10-25T00:00:00Z")
        t.setDay(zoneId, DayOfWeek.SUNDAY, Block("00:00", 16.0), Block("01:30", 20.0))
        t.app.tick()
        t.bus.clearPublished()

        // 00:00 UTC to 02:30 UTC in 10 minute steps: the local clock passes 01:30 twice.
        repeat(15) {
            t.clock.advance(Duration.ofMinutes(10))
            t.app.tick()
        }

        assertEquals(listOf(heat(20.0)), t.sentTo(valve))
    }

    // BDD: Turning the valve knob overrides until the next schedule block
    @Test
    fun `turning the valve knob overrides until the next schedule block`() {
        // Monday 18:00 BST is 17:00 UTC
        val (t, zoneId) = lounge("2026-10-12T17:00:00Z")
        t.api { client ->
            val body = """{"blocks":[{"start":"00:00","target":16.0},{"start":"16:30","target":20.0},{"start":"22:30","target":16.0}]}"""
            client.saveDay(zoneId, "monday", body)
        }
        t.app.tick()
        t.valveState(valve, """{"occupied_heating_setpoint":20.0}""") // valve in sync with the 16:30 block

        t.clock.advance(Duration.ofMinutes(2))
        t.valveState(valve, """{"occupied_heating_setpoint":23.0}""")

        t.api { client ->
            val zone = client.get("/api/rooms").body<List<RoomView>>().single().zones.single()
            assertEquals(23.0, zone.target)
            assertEquals(23.0, zone.override?.target)
            assertEquals("2026-10-12T22:30:00+01:00", zone.override?.until)
        }

        // 22:30 BST is 21:30 UTC
        t.clock.advance(Duration.ofHours(4) + Duration.ofMinutes(30))
        t.app.tick()
        assertEquals(heat(16.0), t.sentTo(valve).last())
        t.api { client ->
            val zone = client.get("/api/rooms").body<List<RoomView>>().single().zones.single()
            assertEquals(16.0, zone.target)
            assertEquals(null, zone.override)
        }
    }

    // BDD: Knob turn in manual mode becomes the new manual target
    @Test
    fun `knob turn in manual mode becomes the new manual target`() {
        val (t, zoneId) = lounge("2026-10-12T09:00:00Z")
        t.app.zones.setMode(zoneId, Mode.MANUAL, 20.0)
        t.valveState(valve, """{"occupied_heating_setpoint":20.0}""") // valve in sync

        t.clock.advance(Duration.ofMinutes(2)) // someone walks up to the radiator later
        t.valveState(valve, """{"occupied_heating_setpoint":22.0}""")

        val settings = t.app.rooms.get(zoneId).settings
        assertEquals(Mode.MANUAL, settings.mode)
        assertEquals(22.0, settings.manualTarget)
    }

    // BDD: Late reports of earlier commands are not knob turns
    @Test
    fun `late reports of earlier commands are not knob turns`() {
        val (t, zoneId) = lounge("2026-10-12T09:00:00Z")
        t.app.zones.setMode(zoneId, Mode.MANUAL, 21.0)
        t.valveState(valve, """{"occupied_heating_setpoint":21.0}""")
        t.clock.advance(Duration.ofMinutes(10))

        t.app.zones.setMode(zoneId, Mode.OFF, null)
        t.clock.advance(Duration.ofSeconds(5))
        t.app.zones.setMode(zoneId, Mode.MANUAL, 20.0)
        val sent = t.sentTo(valve).size
        t.clock.advance(Duration.ofSeconds(3))

        // The valve catches up out of order: its frost setpoint from being off, then 20, then 7 again.
        t.valveState(valve, """{"occupied_heating_setpoint":7.0}""")
        t.valveState(valve, """{"occupied_heating_setpoint":20.0}""")
        t.valveState(valve, """{"occupied_heating_setpoint":7.0}""")

        val settings = t.app.rooms.get(zoneId).settings
        assertEquals(Mode.MANUAL, settings.mode)
        assertEquals(20.0, settings.manualTarget)
        assertEquals(sent, t.sentTo(valve).size)
    }

    // BDD: A report soon after a command is not a knob turn
    @Test
    fun `a report soon after a command is not a knob turn`() {
        val (t, zoneId) = lounge("2026-10-12T09:00:00Z")
        t.app.zones.setMode(zoneId, Mode.MANUAL, 19.0)
        t.valveState(valve, """{"occupied_heating_setpoint":19.0}""") // in sync
        t.clock.advance(Duration.ofMinutes(10))

        t.app.zones.setMode(zoneId, Mode.MANUAL, 20.0)
        t.clock.advance(Duration.ofSeconds(30))
        t.valveState(valve, """{"occupied_heating_setpoint":18.0}""")
        assertEquals(20.0, t.app.rooms.get(zoneId).settings.manualTarget)

        t.clock.advance(Duration.ofMinutes(2))
        t.valveState(valve, """{"occupied_heating_setpoint":20.0}""")
        t.valveState(valve, """{"occupied_heating_setpoint":18.5}""")
        assertEquals(18.5, t.app.rooms.get(zoneId).settings.manualTarget)
    }

    // BDD: A knob still turning when the service answers ends at its last position
    @Test
    fun `a knob still turning when the service answers ends at its last position`() {
        val (t, zoneId) = lounge("2026-10-12T09:00:00Z")
        t.app.zones.setMode(zoneId, Mode.MANUAL, 9.5)
        t.valveState(valve, """{"occupied_heating_setpoint":9.5}""")
        t.clock.advance(Duration.ofMinutes(10))

        t.valveState(valve, """{"occupied_heating_setpoint":26.5}""") // knob turn; the service answers 26.5
        assertEquals(26.5, t.app.rooms.get(zoneId).settings.manualTarget)
        t.clock.advance(Duration.ofSeconds(7))
        t.valveState(valve, """{"occupied_heating_setpoint":28.0}""") // still turning
        t.app.tick()
        assertEquals(26.5, t.app.rooms.get(zoneId).settings.manualTarget)

        t.clock.advance(Duration.ofSeconds(60))
        t.app.tick()
        val settings = t.app.rooms.get(zoneId).settings
        assertEquals(Mode.MANUAL, settings.mode)
        assertEquals(28.0, settings.manualTarget)
    }

    // BDD: A knob turned past the maximum sets the maximum
    @Test
    fun `a knob turned past the maximum sets the maximum`() {
        val (t, zoneId) = lounge("2026-10-12T09:00:00Z")
        t.app.zones.setMode(zoneId, Mode.MANUAL, 20.0)
        t.valveState(valve, """{"occupied_heating_setpoint":20.0}""")
        t.clock.advance(Duration.ofMinutes(2))

        t.valveState(valve, """{"occupied_heating_setpoint":35.0}""")
        val settings = t.app.rooms.get(zoneId).settings
        assertEquals(Mode.MANUAL, settings.mode)
        assertEquals(30.0, settings.manualTarget)
    }

    // BDD: A valve switched off at its knob turns its zone off
    @Test
    fun `a valve switched off at its knob turns its zone off`() {
        val (t, zoneId) = lounge("2026-10-12T09:00:00Z")
        t.app.zones.setMode(zoneId, Mode.MANUAL, 20.0)
        t.valveState(valve, """{"system_mode":"heat","occupied_heating_setpoint":20.0,"local_temperature":18.0}""")
        t.clock.advance(Duration.ofMinutes(2))

        t.valveState(valve, """{"system_mode":"off","occupied_heating_setpoint":20.0}""")
        assertEquals(Mode.OFF, t.app.rooms.get(zoneId).settings.mode)
        t.api { client ->
            assertEquals(false, client.get("/api/rooms").body<List<RoomView>>().single().zones.single().callingForHeat)
        }
    }

    // BDD: A knob turned up on a valve whose zone is off turns it on in manual
    @Test
    fun `a knob turned up on a valve whose zone is off turns it on in manual`() {
        val (t, zoneId) = lounge("2026-10-12T09:00:00Z")
        t.app.zones.setMode(zoneId, Mode.OFF, null)
        t.valveState(valve, """{"system_mode":"off","occupied_heating_setpoint":20.0}""")
        t.clock.advance(Duration.ofMinutes(2))

        t.valveState(valve, """{"system_mode":"heat","occupied_heating_setpoint":7.0}""")
        assertEquals(Mode.OFF, t.app.rooms.get(zoneId).settings.mode)

        t.valveState(valve, """{"system_mode":"heat","occupied_heating_setpoint":22.0}""")
        val settings = t.app.rooms.get(zoneId).settings
        assertEquals(Mode.MANUAL, settings.mode)
        assertEquals(22.0, settings.manualTarget)
    }

    @Test
    fun `stale setpoint reported right after startup is not a knob turn`() {
        val (t, zoneId) = lounge("2026-10-12T17:00:00Z") // Monday 18:00 BST, default schedule 20.0
        t.app.zones.apply(t.app.rooms.get(zoneId).zones().single())
        assertEquals(heat(20.0), t.sentTo(valve).last())

        // The valve's first report still carries its old setpoint.
        t.valveState(valve, """{"occupied_heating_setpoint":16.0}""")

        val zone = t.app.rooms.get(zoneId).zones().single()
        assertEquals(null, zone.settings.override)
        assertEquals(Mode.SCHEDULE, zone.settings.mode)
    }
}
