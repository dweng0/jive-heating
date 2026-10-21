package heating.house

import heating.api.RoomView
import heating.rooms.Control
import heating.rooms.Mode
import heating.testing.TestHeating
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HouseTest {

    /** "Lounge" (schedule) and "Bedroom" (off), one valve each. Returns their room ids. */
    private fun house(t: TestHeating): Pair<String, String> {
        t.discover("Lounge TRV", "Bedroom TRV")
        val lounge = t.app.rooms.create("Lounge")
        t.app.rooms.assignValve(lounge.id, "Lounge TRV")
        val bedroom = t.app.rooms.create("Bedroom")
        t.app.rooms.assignValve(bedroom.id, "Bedroom TRV")
        t.app.zones.setMode(bedroom.id, Mode.OFF, null)
        return lounge.id to bedroom.id
    }

    private suspend fun HttpClient.house(body: String) = post("/api/house") {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpClient.zones() = get("/api/rooms").body<List<RoomView>>().flatMap { it.zones }

    // BDD: Set every room to manual at one temperature
    @Test
    fun `set every room to manual at one temperature`() {
        val t = TestHeating()
        house(t)
        t.api { client ->
            assertEquals(HttpStatusCode.OK, client.house("""{"mode":"manual","target":21.0}""").status)
            client.zones().forEach { z ->
                assertEquals("manual", z.mode.name.lowercase())
                assertEquals(21.0, z.target)
            }
        }
        listOf("Lounge TRV", "Bedroom TRV").forEach { v ->
            assertTrue(t.sentTo(v).last().toString().contains("\"occupied_heating_setpoint\":21.0"), "$v: ${t.sentTo(v).last()}")
        }
    }

    // BDD: Put every room back on its schedule
    @Test
    fun `put every room back on its schedule`() {
        val t = TestHeating()
        val (lounge, bedroom) = house(t)
        t.app.zones.setMode(lounge, Mode.MANUAL, 23.0)
        t.app.boosts.boost(bedroom, 60, 22.0)
        t.api { client ->
            client.house("""{"mode":"schedule"}""")
            client.zones().forEach { z ->
                assertEquals("schedule", z.mode.name.lowercase())
                assertNull(z.boost)
            }
        }
    }

    // BDD: Turn every room off
    @Test
    fun `turn every room off`() {
        val t = TestHeating()
        house(t)
        t.valveState("Lounge TRV", """{"local_temperature":12.0}""")
        t.api { client ->
            client.house("""{"mode":"off"}""")
            client.zones().forEach { z ->
                assertEquals("off", z.mode.name.lowercase())
                assertEquals(false, z.callingForHeat)
            }
        }
    }

    // BDD: Boost every room
    @Test
    fun `boost every room`() {
        val t = TestHeating()
        house(t)
        t.api { client ->
            assertEquals(HttpStatusCode.OK, client.house("""{"boost":60,"target":22.0}""").status)
            client.zones().forEach { z ->
                assertNotNull(z.boost)
                assertEquals(22.0, z.boost!!.target)
                assertEquals(22.0, z.target)
            }
        }
        listOf("Lounge TRV", "Bedroom TRV").forEach { v ->
            assertTrue(t.sentTo(v).any { it.toString().contains("\"temporary_mode_duration\":60") }, v)
        }
    }

    // BDD: Cancel every boost
    @Test
    fun `cancel every boost`() {
        val t = TestHeating()
        val (lounge, bedroom) = house(t)
        t.app.boosts.boost(lounge, 30, 22.0)
        t.app.boosts.boost(bedroom, 30, 22.0)
        t.api { client ->
            assertEquals(HttpStatusCode.OK, client.delete("/api/house/boost").status)
            client.zones().forEach { assertNull(it.boost) }
        }
    }

    // BDD: Independent rooms change every zone, and rooms without valves are left alone
    @Test
    fun `independent rooms change every zone, and rooms without valves are left alone`() {
        val t = TestHeating()
        t.discover("K1", "K2")
        val kitchen = t.app.rooms.create("Kitchen")
        t.app.rooms.assignValve(kitchen.id, "K1")
        t.app.rooms.assignValve(kitchen.id, "K2")
        t.app.rooms.switchControl(kitchen.id, Control.INDEPENDENT, null)
        val loft = t.app.rooms.create("Loft")
        val loftBefore = t.app.rooms.get(loft.id)
        t.api { client ->
            client.house("""{"mode":"manual","target":20.0}""")
            val rooms = client.get("/api/rooms").body<List<RoomView>>()
            val kitchenZones = rooms.single { it.name == "Kitchen" }.zones
            assertEquals(2, kitchenZones.size)
            kitchenZones.forEach { assertEquals(20.0, it.target) }
        }
        assertEquals(loftBefore, t.app.rooms.get(loft.id))
    }

    // BDD: Whole-house changes follow the same rules as one room
    @Test
    fun `whole-house changes follow the same rules as one room`() {
        val t = TestHeating()
        house(t)
        t.api { client ->
            val before = client.zones()
            assertEquals(HttpStatusCode.BadRequest, client.house("""{"mode":"manual","target":40.0}""").status)
            assertEquals(before, client.zones())

            client.put("/api/holiday") {
                contentType(ContentType.Application.Json)
                setBody("""{"until":"2026-10-20T15:00:00+01:00"}""")
            }
            assertEquals(HttpStatusCode.Conflict, client.house("""{"boost":60}""").status)
        }
    }
}
