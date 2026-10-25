package heating.valves

import heating.rooms.Mode
import heating.testing.TestHeating
import java.time.Duration
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LostCommandsTest {

    private val stale = """{"local_temperature":19.0,"occupied_heating_setpoint":21.0,"system_mode":"heat"}"""

    /** Room "Utility" with valve "Utility TRV" that has reported [report]; the zone is on manual [target], or off when null. */
    private fun utility(t: TestHeating, target: Double?, report: String = stale): String {
        t.discover("Utility TRV")
        val room = t.app.rooms.create("Utility")
        t.app.rooms.assignValve(room.id, "Utility TRV")
        t.valveState("Utility TRV", report)
        if (target == null) t.app.zones.setMode(room.id, Mode.OFF, null)
        else t.app.zones.setMode(room.id, Mode.MANUAL, target)
        return room.id
    }

    private fun sent(t: TestHeating, key: String, value: String) =
        t.sentTo("Utility TRV").count { it[key]?.jsonPrimitive?.content == value }

    private fun setpoints(t: TestHeating, value: Double) = sent(t, "occupied_heating_setpoint", value.toString())

    private fun offs(t: TestHeating) = sent(t, "system_mode", "off")

    // BDD: A setpoint the valve did not take is sent again
    @Test
    fun `a setpoint the valve did not take is sent again`() {
        val t = TestHeating()
        val room = utility(t, 22.0)
        var count = setpoints(t, 22.0)
        assertEquals(1, count)

        val waits = listOf(10L, 20, 60, 180, 300, 300)
        waits.forEach { seconds ->
            t.clock.advance(Duration.ofSeconds(seconds - 1))
            t.valveState("Utility TRV", stale)
            t.app.tick()
            assertEquals(count, setpoints(t, 22.0), "not before ${seconds}s")
            t.clock.advance(Duration.ofSeconds(1))
            t.app.tick()
            count += 1
            assertEquals(count, setpoints(t, 22.0), "again after ${seconds}s")
        }

        t.valveState("Utility TRV", """{"occupied_heating_setpoint":22.0}""")
        t.clock.advance(Duration.ofMinutes(10))
        t.app.tick()
        assertEquals(count, setpoints(t, 22.0), "stops once the valve reports it")

        // a person's new change starts the waits again
        t.valveState("Utility TRV", stale)
        t.app.zones.setMode(room, Mode.MANUAL, 23.0)
        t.clock.advance(Duration.ofSeconds(10))
        t.app.tick()
        assertEquals(2, setpoints(t, 23.0))
    }

    // BDD: A valve told to turn off that keeps heating is told again
    @Test
    fun `a valve told to turn off that keeps heating is told again`() {
        val t = TestHeating()
        utility(t, null)
        val first = offs(t)
        assertTrue(first >= 1)

        t.clock.advance(Duration.ofSeconds(9))
        t.valveState("Utility TRV", stale)
        t.app.tick()
        assertEquals(first, offs(t))
        t.clock.advance(Duration.ofSeconds(1))
        t.app.tick()
        assertEquals(first + 1, offs(t))

        t.clock.advance(Duration.ofSeconds(19))
        t.app.tick()
        assertEquals(first + 1, offs(t), "the second wait is 20 seconds")
        t.clock.advance(Duration.ofSeconds(1))
        t.app.tick()
        assertEquals(first + 2, offs(t))
    }

    // BDD: A knob turn is not mistaken for a lost command
    @Test
    fun `a knob turn is not mistaken for a lost command`() {
        val t = TestHeating()
        utility(t, 22.0)
        val first = setpoints(t, 22.0)

        t.clock.advance(Duration.ofMinutes(2))
        t.valveState("Utility TRV", """{"occupied_heating_setpoint":19.0}""")
        t.app.tick()
        t.clock.advance(Duration.ofMinutes(10))
        t.app.tick()

        assertEquals(first, setpoints(t, 22.0))
        assertEquals(19.0, t.app.rooms.list().single().zones().single().settings.manualTarget)
    }

    // BDD: A valve that is offline is not sent commands again
    @Test
    fun `a valve that is offline is not sent commands again`() {
        val t = TestHeating()
        utility(t, 22.0)
        t.availability("Utility TRV", false)
        val before = t.sentTo("Utility TRV").size

        t.clock.advance(Duration.ofMinutes(10))
        t.app.tick()
        assertEquals(before, t.sentTo("Utility TRV").size)

        t.availability("Utility TRV", true)
        assertTrue(setpoints(t, 22.0) >= 2)
        assertTrue(t.sentTo("Utility TRV").size > before)
    }

    // BDD: A room sensor reading is passed to the valves only when it matters
    @Test
    fun `a room sensor reading is passed to the valves only when it matters`() {
        val t = TestHeating()
        t.discover("Landing TRV", sensors = listOf("Landing Smoke"))
        val room = t.app.rooms.create("Landing")
        t.app.rooms.assignValve(room.id, "Landing TRV")
        t.sensorState("Landing Smoke", """{"temperature":20.8,"battery":90}""")
        t.app.roomSensors.assign(room.id, "Landing Smoke")
        fun pushes() = t.sentTo("Landing TRV").count { "external_temperature_input" in it }
        val first = pushes()
        assertEquals(1, first)

        t.sensorState("Landing Smoke", """{"temperature":20.9,"battery":90}""")
        t.sensorState("Landing Smoke", """{"temperature":21.0,"battery":90}""")
        assertEquals(first, pushes())

        t.sensorState("Landing Smoke", """{"temperature":21.3,"battery":90}""")
        assertEquals(first + 1, pushes())

        t.clock.advance(Duration.ofMinutes(11))
        t.app.tick()
        assertEquals(first + 2, pushes(), "the last reading is still sent again after 10 minutes")
    }

    private fun unresponsive(t: TestHeating) =
        t.app.activity.recent(100).filter { it.kind == "unresponsive" }.map { it.value }

    // BDD: A valve that keeps missing commands is flagged in the feed
    @Test
    fun `a valve that keeps missing commands is flagged in the feed`() {
        val t = TestHeating()
        utility(t, 22.0)
        listOf(10L, 20, 60).forEachIndexed { i, seconds ->
            assertEquals(emptyList(), unresponsive(t), "not yet after ${i} re-sends")
            t.clock.advance(Duration.ofSeconds(seconds))
            t.valveState("Utility TRV", stale)
            t.app.tick()
        }
        assertEquals(listOf(JsonPrimitive("unresponsive")), unresponsive(t))
        assertEquals("Utility", t.app.activity.recent(100).first { it.kind == "unresponsive" }.room)

        t.clock.advance(Duration.ofSeconds(180))
        t.app.tick()
        assertEquals(1, unresponsive(t).size, "once per episode")
    }

    // BDD: A valve that takes the command again is marked responding
    @Test
    fun `a valve that takes the command again is marked responding`() {
        val t = TestHeating()
        utility(t, 22.0)
        listOf(10L, 20, 60).forEach { seconds ->
            t.clock.advance(Duration.ofSeconds(seconds))
            t.valveState("Utility TRV", stale)
            t.app.tick()
        }
        t.valveState("Utility TRV", """{"occupied_heating_setpoint":22.0}""")
        assertEquals(listOf(JsonPrimitive("unresponsive"), JsonPrimitive("responding")), unresponsive(t).reversed())
    }
}
