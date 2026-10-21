package heating.integration

import heating.testing.MutableClock
import heating.testing.TestHeating
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Features built separately must agree on the effective target. */
class HolidayDemandTest {

    @Test
    fun `a zone on holiday does not call for heat at its schedule target`() {
        // Monday 18:00 BST: default schedule target is 20.0.
        val t = TestHeating(clock = MutableClock(Instant.parse("2026-10-12T17:00:00Z")))
        t.discover("Lounge TRV 1")
        val room = t.app.rooms.create("Lounge")
        t.app.rooms.assignValve(room.id, "Lounge TRV 1")
        t.valveState("Lounge TRV 1", """{"local_temperature":15.0,"running_state":"idle"}""")

        t.api { client ->
            val response = client.put("/api/holiday") {
                contentType(ContentType.Application.Json)
                setBody("""{"until":"2026-12-28T15:00:00Z","target":12.0}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
        t.app.tick()

        val zone = t.app.rooms.get(room.id).zones().single()
        assertEquals(12.0, t.app.zones.effectiveTarget(zone.settings))
        assertFalse(t.app.demand.zoneCalling(zone), "15.0 is above the 12.0 holiday target")
        // The boiler fired before the holiday started; it stops once the 10-minute minimum run is over.
        t.clock.advance(java.time.Duration.ofMinutes(10))
        t.app.tick()
        assertFalse(t.app.demand.demand)
    }
}
