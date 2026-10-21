package heating.energy

import heating.testing.TestHeating
import io.ktor.client.request.put
import io.ktor.http.HttpStatusCode
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MeterRecordingTest {

    private fun houseMeter(t: TestHeating, meter: String) = t.api { client ->
        assertEquals(HttpStatusCode.OK, client.put("/api/meters/${meter.replace(" ", "%20")}/house").status)
    }

    private fun minutes(t: TestHeating, n: Long) {
        t.clock.advance(Duration.ofMinutes(n))
        t.app.tick()
    }

    private fun lastKwh(t: TestHeating): Double? =
        t.app.energyHistory.samples(Instant.EPOCH, t.clock.instant()).last().kwh

    // BDD: The house meter is recorded every five minutes
    @Test
    fun `the house meter is recorded every five minutes`() {
        val t = TestHeating()
        t.discover(meters = listOf("Main Clamp"))
        houseMeter(t, "Main Clamp")

        t.meterState("Main Clamp", """{"energy":1523.4,"power":412}""")
        minutes(t, 5)
        t.meterState("Main Clamp", """{"energy":1523.9,"power":380}""")
        minutes(t, 5)
        assertEquals(0.5, lastKwh(t)!!, 1e-9)
        assertEquals(2, t.app.energyHistory.samples(Instant.EPOCH, t.clock.instant()).size)

        // Nothing more is recorded until five minutes have passed.
        minutes(t, 2)
        assertEquals(2, t.app.energyHistory.samples(Instant.EPOCH, t.clock.instant()).size)
        minutes(t, 3)

        // The counter resets: the reading counts on from 0.2, never negative.
        t.meterState("Main Clamp", """{"energy":0.2,"power":380}""")
        minutes(t, 5)
        val reset = assertNotNull(lastKwh(t))
        assertTrue(reset >= 0.0, "recorded $reset kWh at the reset")
        t.meterState("Main Clamp", """{"energy":0.5,"power":380}""")
        minutes(t, 5)
        assertEquals(0.3, lastKwh(t)!!, 1e-9)
    }
    private fun fridgeKwh(t: TestHeating): Double? =
        t.app.energyHistory.deviceSamples("Fridge Plug", Instant.EPOCH, t.clock.instant()).last().kwh

    // BDD: Monitored devices are recorded every five minutes
    @Test
    fun `monitored devices are recorded every five minutes`() {
        val t = TestHeating()
        t.discover(meters = listOf("Main Clamp", "Fridge Plug"))
        houseMeter(t, "Main Clamp")
        t.api { client -> assertEquals(HttpStatusCode.OK, client.put("/api/meters/Fridge%20Plug/monitored").status) }

        t.meterState("Main Clamp", """{"energy":1523.4,"power":412}""")
        t.meterState("Fridge Plug", """{"energy":1027.88,"power":60}""")
        minutes(t, 5)
        t.meterState("Main Clamp", """{"energy":1523.9,"power":380}""")
        t.meterState("Fridge Plug", """{"energy":1027.98,"power":60}""")
        minutes(t, 5)
        assertEquals(0.1, fridgeKwh(t)!!, 1e-9)
        // The plug's samples are its own: the house meter's are unchanged.
        assertEquals(0.5, lastKwh(t)!!, 1e-9)
        assertTrue(t.app.energyHistory.samples(Instant.EPOCH, t.clock.instant()).all { it.meter == "Main Clamp" })

        // The counter resets: the reading counts on from 0.2, never negative.
        t.meterState("Fridge Plug", """{"energy":0.2,"power":60}""")
        minutes(t, 5)
        assertEquals(0.2, fridgeKwh(t)!!, 1e-9)
        t.meterState("Fridge Plug", """{"energy":0.5,"power":60}""")
        minutes(t, 5)
        assertEquals(0.3, fridgeKwh(t)!!, 1e-9)

        // Stale: its use is unknown, not 0 kWh, and once it reports again the use since its last reading counts.
        repeat(7) {
            t.meterState("Main Clamp", """{"energy":1524.0,"power":380}""")
            minutes(t, 5)
        }
        assertEquals(null, fridgeKwh(t))
        t.meterState("Fridge Plug", """{"energy":0.9,"power":60}""")
        minutes(t, 5)
        assertEquals(0.4, fridgeKwh(t)!!, 1e-9)
    }
}
