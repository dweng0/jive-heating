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

    private fun houseSamples(t: TestHeating) = t.app.energyHistory.samples(Instant.EPOCH, t.clock.instant())

    // BDD: The house meter is recorded every five minutes
    @Test
    fun `the house meter is recorded every five minutes`() {
        val t = TestHeating()
        t.discover(meters = listOf("Main Clamp"))
        houseMeter(t, "Main Clamp")

        t.meterState("Main Clamp", """{"energy":1523.4,"power":1200}""")
        minutes(t, 5)
        val first = houseSamples(t).single()
        assertEquals(0.1, first.kwh!!, 1e-9)
        assertEquals(1200.0, first.watts!!, 1e-6)

        // Its energy total is not used: a jump in it adds nothing.
        t.meterState("Main Clamp", """{"energy":1600.0,"power":1200}""")
        minutes(t, 5)
        assertEquals(0.1, lastKwh(t)!!, 1e-9)

        // Nothing more is recorded until five minutes have passed.
        minutes(t, 2)
        assertEquals(2, houseSamples(t).size)
        minutes(t, 3)
        assertEquals(3, houseSamples(t).size)
    }

    // BDD: The house meter's use is counted from its power
    @Test
    fun `the house meter's use is counted from its power`() {
        val t = TestHeating()
        t.discover(meters = listOf("Main Clamp"))
        houseMeter(t, "Main Clamp")
        minutes(t, 5)

        // Each power reading holds until the next; the total jumping up and down changes nothing.
        t.meterState("Main Clamp", """{"energy":0.56,"power":600}""")
        t.clock.advance(Duration.ofSeconds(150))
        t.meterState("Main Clamp", """{"energy":0.25,"power":1800}""")
        t.clock.advance(Duration.ofSeconds(150))
        t.meterState("Main Clamp", """{"energy":0.17,"power":1800}""")
        t.app.tick()
        assertEquals(0.1, lastKwh(t)!!, 1e-9)
        assertEquals(1200.0, houseSamples(t).last().watts!!, 1e-6)

        // A gap of 30 minutes or more is unknown, never counted at the last power.
        minutes(t, 30)
        assertEquals(null, lastKwh(t))
        t.meterState("Main Clamp", """{"energy":0.2,"power":600}""")
        minutes(t, 5)
        assertEquals(0.05, lastKwh(t)!!, 1e-9)

        // After a restart: counted again from the first reading, without a jump.
        val before = houseSamples(t).size
        t.restart()
        t.meterState("Main Clamp", """{"energy":0.1,"power":2400}""")
        minutes(t, 5)
        val after = houseSamples(t).drop(before)
        assertEquals(0.2, after.single().kwh!!, 1e-9)
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
        // The plug's samples are its own: the house meter's are unchanged (380 W for five minutes).
        assertEquals(380.0 / 12_000, lastKwh(t)!!, 1e-9)
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
