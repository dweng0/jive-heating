package heating.history

import heating.heatingJson
import heating.rooms.Mode
import heating.testing.TestHeating
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HistoryTest {

    private fun lounge(t: TestHeating): String {
        t.discover("Lounge TRV 1")
        val room = t.app.rooms.create("Lounge")
        t.app.rooms.assignValve(room.id, "Lounge TRV 1")
        t.app.zones.setMode(room.id, Mode.MANUAL, 21.0)
        return room.id
    }

    private fun temp(t: TestHeating, degrees: Double) =
        t.valveState("Lounge TRV 1", """{"local_temperature":$degrees}""")

    private fun minutes(t: TestHeating, n: Long) {
        t.clock.advance(Duration.ofMinutes(n))
        t.app.tick()
    }

    private fun history(t: TestHeating, range: String = "24h"): JsonObject {
        var result: JsonObject? = null
        t.api { client ->
            result = heatingJson.parseToJsonElement(client.get("/api/history?range=$range").bodyAsText()).jsonObject
        }
        return result!!
    }

    private fun JsonObject.time(key: String): Instant? =
        this[key].takeUnless { it == null || it is JsonNull }?.jsonPrimitive?.content?.let { OffsetDateTime.parse(it).toInstant() }

    private fun JsonObject.number(key: String) = this[key]!!.jsonPrimitive.double

    private fun roomPoints(h: JsonObject, name: String) =
        h["rooms"]!!.jsonArray.map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == name }["points"]!!.jsonArray.map { it.jsonObject }

    private fun boiler(h: JsonObject) = h["boiler"]!!.jsonArray.map { it.jsonObject }

    // BDD: Room temperatures and targets are recorded every five minutes
    @Test
    fun `room temperatures and targets are recorded every five minutes`() {
        val t = TestHeating()
        lounge(t)
        t.app.rooms.create("Loft")
        temp(t, 19.5)
        t.app.tick()
        assertEquals(0, t.app.history.points(Instant.EPOCH, t.clock.instant()).size)

        minutes(t, 5)
        val first = t.app.history.points(Instant.EPOCH, t.clock.instant())
        assertEquals(1, first.size, "one point for Lounge; Loft has no temperature")
        assertEquals(19.5, first.single().current)
        assertEquals(21.0, first.single().target)

        minutes(t, 1)
        minutes(t, 1)
        assertEquals(1, t.app.history.points(Instant.EPOCH, t.clock.instant()).size, "not on every tick")
        minutes(t, 3)
        assertEquals(2, t.app.history.points(Instant.EPOCH, t.clock.instant()).size)
    }

    // BDD: Boiler states are recorded as they change
    @Test
    fun `boiler states are recorded as they change`() {
        val t = TestHeating()
        lounge(t)
        val on = t.clock.instant()
        temp(t, 20.0)
        minutes(t, 1)
        temp(t, 21.0)
        minutes(t, 3)

        val states = boiler(history(t))
        assertEquals(listOf("heating", "cycling", "idle"), states.map { it["state"]!!.jsonPrimitive.content })
        assertEquals(on, states[0].time("from"))
        assertEquals(on.plusSeconds(60), states[0].time("to"))
        assertEquals(on.plusSeconds(60), states[1].time("from"))
        assertEquals(on.plusSeconds(240), states[1].time("to"))
        assertEquals(on.plusSeconds(240), states[2].time("from"))
        assertEquals(null, states[2].time("to"))
    }

    // BDD: A boiler state left open by a restart ends when the service last recorded
    @Test
    fun `a boiler state left open by a restart ends when the service last recorded`() {
        val t = TestHeating()
        lounge(t)
        val on = t.clock.instant()
        temp(t, 20.0)
        repeat(4) { minutes(t, 5) }
        val lastRecorded = t.clock.instant()

        t.clock.advance(Duration.ofMinutes(40))
        val restartedAt = t.clock.instant()
        t.restart()

        val states = boiler(history(t))
        assertEquals("heating", states[0]["state"]!!.jsonPrimitive.content)
        assertEquals(on, states[0].time("from"))
        assertEquals(lastRecorded, states[0].time("to"))
        assertEquals(restartedAt, states[1].time("from"))
    }

    // BDD: History older than a year is deleted
    @Test
    fun `history older than a year is deleted`() {
        val t = TestHeating()
        val id = lounge(t)
        val now = t.clock.instant()
        val old = now.minus(Duration.ofDays(400))
        val yesterday = now.minus(Duration.ofDays(1))
        t.app.history.addPoints(listOf(RoomPoint(old, id, 18.0, 20.0), RoomPoint(yesterday, id, 19.0, 20.0)))
        t.app.history.boilerChanged("heating", old)
        t.app.history.boilerChanged("idle", old.plusSeconds(600))
        t.app.history.boilerChanged("heating", yesterday)

        minutes(t, 24 * 60)

        assertEquals(listOf(yesterday), t.app.history.points(Instant.EPOCH, now).map { it.at })
        val yearAgo = now.minus(Duration.ofDays(365))
        val states = t.app.history.boilerStates(Instant.EPOCH, now.plus(Duration.ofDays(2)))
        assertTrue(states.isNotEmpty())
        assertTrue(states.all { it.to == null || it.to!! > yearAgo })
    }

    // BDD: History for the last 24 hours, 7 days or 30 days is served
    @Test
    fun `history for the last 24 hours, 7 days or 30 days is served`() {
        val t = TestHeating()
        val id = lounge(t)
        val now = t.clock.instant()
        t.app.history.addPoints((0 until 30 * 288).map { k -> RoomPoint(now.minus(Duration.ofMinutes(5L * k)), id, 20.0, 21.0) })

        fun spacing(range: String): Set<Duration> {
            val points = roomPoints(history(t, range), "Lounge")
            points.forEach { assertEquals(20.0, it.number("current")); assertEquals(21.0, it.number("target")) }
            val times = points.map { it.time("at")!! }
            assertEquals(times.sorted(), times)
            assertTrue(times.first() >= now.minus(Duration.ofDays(31)))
            return times.zipWithNext { a, b -> Duration.between(a, b) }.toSet()
        }

        assertEquals(setOf(Duration.ofMinutes(5)), spacing("24h"))
        assertEquals(setOf(Duration.ofMinutes(30)), spacing("7d"))
        assertEquals(setOf(Duration.ofHours(2)), spacing("30d"))
        assertTrue(roomPoints(history(t, "24h"), "Lounge").size in 288..289)
    }

    // BDD: The whole-house line is the median of the rooms
    @Test
    fun `the whole-house line is the median of the rooms`() {
        val t = TestHeating()
        val at = t.clock.instant().minusSeconds(600)
        val points = listOf("Lounge" to 18.0, "Kitchen" to 19.0, "Bedroom" to 23.0).map { (name, current) ->
            RoomPoint(at, t.app.rooms.create(name).id, current, 21.0)
        }
        t.app.history.addPoints(points)

        val house = history(t)["house"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, house.size)
        assertEquals(at, house.single().time("at"))
        assertEquals(19.0, house.single().number("current"))
    }

    // BDD: Unknown history ranges are refused
    @Test
    fun `unknown history ranges are refused`() = TestHeating().api { client ->
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/history?range=decade").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/history?range=7d").status)
    }

    // BDD: A failed history write does not stop the heating
    @Test
    fun `a failed history write does not stop the heating`() {
        val t = TestHeating()
        DriverManager.getConnection("jdbc:sqlite:${t.dbPath}").use { c ->
            c.createStatement().use { it.execute("DROP TABLE room_points"); it.execute("DROP TABLE boiler_states") }
        }
        lounge(t)
        minutes(t, 5)
        temp(t, 20.0)
        minutes(t, 5)
        assertTrue(t.app.demand.demand)
    }

    // BDD: History survives a restart
    @Test
    fun `history survives a restart`() {
        val t = TestHeating()
        lounge(t)
        temp(t, 19.5)
        repeat(3) { minutes(t, 5) }
        val before = history(t)
        t.restart()
        val after = history(t)
        assertEquals(before["rooms"], after["rooms"])
        assertEquals(3, roomPoints(after, "Lounge").size)
        assertNotEquals(0, boiler(after).size)
        assertEquals(boiler(before).first()["from"], boiler(after).first()["from"])
    }
}
