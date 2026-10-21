package heating.persistence

import heating.rooms.Block
import heating.rooms.DaySchedule
import heating.rooms.Mode
import heating.testing.MutableClock
import heating.testing.TestHeating
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.DayOfWeek
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PersistenceTest {

    private fun lounge(at: String): Pair<TestHeating, String> {
        val t = TestHeating(clock = MutableClock(Instant.parse(at)))
        t.discover("Lounge TRV 1")
        val room = t.app.rooms.create("Lounge")
        t.app.rooms.assignValve(room.id, "Lounge TRV 1")
        return t to room.id
    }

    // BDD: Rooms, zones and schedules survive a restart
    @Test
    fun `rooms zones and schedules survive a restart`() {
        val (t, id) = lounge("2026-10-12T09:00:00Z")
        val monday = DaySchedule(listOf(Block("00:00", 15.0), Block("07:15", 21.5)))
        t.app.rooms.updateZone(id) { it.copy(schedule = it.schedule.with(DayOfWeek.MONDAY, monday)) }

        val app = t.restart()

        val room = app.rooms.get(id)
        assertEquals("Lounge", room.name)
        assertEquals(listOf("Lounge TRV 1"), room.valves)
        assertEquals(listOf(Block("00:00", 15.0), Block("07:15", 21.5)), room.settings.schedule[DayOfWeek.MONDAY].blocks)
    }

    // BDD: Valves are resynced on startup
    @Test
    fun `valves are resynced on startup`() {
        val (t, id) = lounge("2026-10-12T09:00:00Z")
        t.app.zones.setMode(id, Mode.MANUAL, 21.0)
        t.bus.clearPublished()

        t.restart()

        val expected = buildJsonObject {
            put("system_mode", JsonPrimitive("heat"))
            put("occupied_heating_setpoint", JsonPrimitive(21.0))
        }
        assertEquals(expected, t.sentTo("Lounge TRV 1").last())
    }

    // BDD: Boost that expired while down is cleared on startup
    @Test
    fun `boost that expired while down is cleared on startup`() {
        // Boost at 09:30 BST for 30 minutes: ends 10:00 BST (09:00 UTC).
        val (t, id) = lounge("2026-10-12T08:30:00Z")
        t.app.boosts.boost(id, 30, 22.0)

        t.clock.set(Instant.parse("2026-10-12T09:30:00Z")) // service starts at 10:30 BST
        val app = t.restart()

        assertNull(app.rooms.get(id).settings.boost)
        // Monday 10:30 BST in the default schedule (08:30 → 16.0).
        assertEquals(JsonPrimitive(16.0), t.sentTo("Lounge TRV 1").last()["occupied_heating_setpoint"])
    }
}
