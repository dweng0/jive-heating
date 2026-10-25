package heating.valves

import heating.rooms.Mode
import heating.testing.TestHeating
import java.time.Duration
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
        utility(t, 22.0)
        val first = setpoints(t, 22.0)
        assertEquals(1, first)

        t.clock.advance(Duration.ofMinutes(2))
        t.valveState("Utility TRV", stale)
        t.app.tick()
        assertEquals(first + 1, setpoints(t, 22.0))

        t.clock.advance(Duration.ofMinutes(1))
        t.app.tick()
        assertEquals(first + 1, setpoints(t, 22.0), "no more than once every 5 minutes")

        t.clock.advance(Duration.ofMinutes(5))
        t.app.tick()
        assertEquals(first + 2, setpoints(t, 22.0))

        t.valveState("Utility TRV", """{"occupied_heating_setpoint":22.0}""")
        t.clock.advance(Duration.ofMinutes(10))
        t.app.tick()
        assertEquals(first + 2, setpoints(t, 22.0), "stops once the valve reports it")
    }

    // BDD: A valve told to turn off that keeps heating is told again
    @Test
    fun `a valve told to turn off that keeps heating is told again`() {
        val t = TestHeating()
        utility(t, null)
        val first = offs(t)
        assertTrue(first >= 1)

        t.clock.advance(Duration.ofMinutes(2))
        t.valveState("Utility TRV", stale)
        t.app.tick()
        assertEquals(first + 1, offs(t))

        t.clock.advance(Duration.ofMinutes(1))
        t.app.tick()
        assertEquals(first + 1, offs(t), "at most once every 5 minutes")
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
}
