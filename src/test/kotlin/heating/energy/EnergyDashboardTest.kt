package heating.energy

import heating.heatingJson
import heating.testing.MutableClock
import heating.testing.TestHeating
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class EnergyDashboardTest {

    private fun dashboard(t: TestHeating, period: String): JsonObject {
        var result: JsonObject? = null
        t.api { client ->
            val response = client.get("/api/dashboard?period=$period")
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            result = heatingJson.parseToJsonElement(response.bodyAsText()).jsonObject
        }
        return result!!
    }

    private fun JsonObject.number(key: String) = this[key]!!.jsonPrimitive.double

    private fun JsonObject.days() = this["days"]!!.jsonArray.map { it.jsonObject }

    private fun minutes(t: TestHeating, n: Long) {
        t.clock.advance(Duration.ofMinutes(n))
        t.app.tick()
    }

    /**
     * From 09:00 UTC on 12 October: the boiler heats for 2 hours then cycles for 1, while house
     * meter "Main Clamp" counts 8.0 kWh.
     */
    private fun threeHoursAndEightKwh(t: TestHeating) {
        t.discover(meters = listOf("Main Clamp"))
        t.api { client -> assertEquals(HttpStatusCode.OK, client.put("/api/meters/Main%20Clamp/house").status) }
        t.app.history.boilerChanged("heating", t.clock.instant())
        t.meterState("Main Clamp", """{"energy":100.0,"power":400}""")
        minutes(t, 5)
        t.meterState("Main Clamp", """{"energy":104.0,"power":400}""")
        minutes(t, 5)
        t.meterState("Main Clamp", """{"energy":108.0,"power":400}""")
        minutes(t, 5)
        t.clock.advance(Duration.ofMinutes(105))
        t.app.history.boilerChanged("cycling", t.clock.instant())
        t.clock.advance(Duration.ofHours(1))
        t.app.history.boilerChanged("idle", t.clock.instant())
    }

    // BDD: The energy dashboard totals use and cost for a period
    @Test
    fun `the energy dashboard totals use and cost for a period`() {
        val t = TestHeating(clock = MutableClock(Instant.parse("2026-10-12T09:00:00Z")))
        threeHoursAndEightKwh(t)

        val today = dashboard(t, "today")
        assertEquals("today", today["period"]!!.jsonPrimitive.content)
        assertEquals(3.0, today.number("boilerHours"))
        assertEquals(36.0, today.number("gasKwh"))
        assertEquals(396.0, today.number("gasCost"))
        assertEquals(8.0, today.number("electricityKwh"))
        assertEquals(216.0, today.number("electricityCost"))
        // 396p gas + 216p electricity + 30p and 50p standing charges.
        assertEquals(692.0, today.number("totalCost"))
        assertEquals(listOf("2026-10-12"), today.days().map { it["date"]!!.jsonPrimitive.content })

        val week = dashboard(t, "week")
        assertEquals(7, week.days().size)
        assertEquals("2026-10-06", week.days().first()["date"]!!.jsonPrimitive.content)
        assertEquals("2026-10-12", week.days().last()["date"]!!.jsonPrimitive.content)
        assertEquals(3.0, week.days().last().number("boilerHours"))
        assertEquals(8.0, week.days().last().number("electricityKwh"))
        assertEquals(0.0, week.days().first().number("boilerHours"))
        assertEquals(3.0, week.number("boilerHours"))
        assertEquals(8.0, week.number("electricityKwh"))

        val month = dashboard(t, "month")
        assertEquals(30, month.days().size)
        assertEquals("2026-09-13", month.days().first()["date"]!!.jsonPrimitive.content)
        assertEquals(396.0, month.number("gasCost"))
        assertEquals(216.0, month.number("electricityCost"))
    }

    // BDD: Changing a price recalculates past costs
    @Test
    fun `changing a price recalculates past costs`() {
        val t = TestHeating(clock = MutableClock(Instant.parse("2026-10-12T09:00:00Z")))
        threeHoursAndEightKwh(t)
        assertEquals(216.0, dashboard(t, "today").number("electricityCost"))

        t.api { client ->
            val response = client.put("/api/settings/energy") {
                contentType(ContentType.Application.Json)
                setBody("""{"electricityPencePerKwh":24.5}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

        assertEquals(196.0, dashboard(t, "today").number("electricityCost"))
        assertEquals(196.0, dashboard(t, "week").number("electricityCost"))
        assertEquals(196.0, dashboard(t, "week").days().last().number("electricityCost"))
        assertEquals(196.0, dashboard(t, "month").number("electricityCost"))
        assertEquals(8.0, dashboard(t, "today").number("electricityKwh"))
        val recorded = t.app.energyHistory.samples(Instant.EPOCH, t.clock.instant()).sumOf { it.kwh ?: 0.0 }
        assertEquals(8.0, recorded, 1e-9)
    }

    // BDD: Standing charges are shown on their own line
    @Test
    fun `standing charges are shown on their own line`() {
        val t = TestHeating(clock = MutableClock(Instant.parse("2026-10-12T09:00:00Z")))
        threeHoursAndEightKwh(t)
        val week = dashboard(t, "week")
        // 7 days of 30.0p gas and 50.0p electricity standing charge.
        assertEquals(210.0, week.number("gasStandingCost"))
        assertEquals(350.0, week.number("electricityStandingCost"))
        assertEquals(560.0, week.number("standingCost"))
        // Use is apart from them: 396p gas and 216p electricity.
        assertEquals(396.0, week.number("gasCost"))
        assertEquals(216.0, week.number("electricityCost"))
        assertEquals(396.0 + 216.0 + 560.0, week.number("totalCost"))
        assertEquals(80.0, week.days().first().number("standingCost"))
        assertEquals(80.0, week.days().first().number("totalCost"))
    }

    // BDD: Days with gaps in history are marked incomplete
    @Test
    fun `days with gaps in history are marked incomplete`() {
        // Midnight on Sunday 11 October in London.
        val t = TestHeating(clock = MutableClock(Instant.parse("2026-10-10T23:00:00Z")))
        repeat(12 * 12) { minutes(t, 5) }
        // Down for two hours at midday yesterday, then started again.
        t.clock.advance(Duration.ofHours(2))
        t.restart()
        // On until 10:00 today.
        while (t.clock.instant() < Instant.parse("2026-10-12T09:00:00Z")) minutes(t, 5)

        val days = dashboard(t, "week").days().associateBy { it["date"]!!.jsonPrimitive.content }
        val yesterday = days.getValue("2026-10-11")
        assertEquals(true, yesterday["incomplete"]!!.jsonPrimitive.boolean)
        assertEquals(120, yesterday["missingMinutes"]!!.jsonPrimitive.int)
        assertEquals(false, days.getValue("2026-10-12")["incomplete"]!!.jsonPrimitive.boolean)
        // Before the service was installed there is no history at all.
        assertEquals(true, days.getValue("2026-10-10")["incomplete"]!!.jsonPrimitive.boolean)
    }

    // BDD: Gas-only totals when no meter is chosen
    @Test
    fun `gas-only totals when no meter is chosen`() {
        val t = TestHeating(clock = MutableClock(Instant.parse("2026-10-12T09:00:00Z")))
        t.app.history.boilerChanged("heating", t.clock.instant())
        t.clock.advance(Duration.ofHours(3))
        t.app.history.boilerChanged("idle", t.clock.instant())

        val today = dashboard(t, "today")
        assertEquals(JsonNull, today["electricityKwh"])
        assertEquals(JsonNull, today["electricityCost"])
        assertEquals(JsonNull, today["electricityStandingCost"])
        assertEquals(JsonNull, today["electricityMeter"])
        assertEquals(396.0, today.number("gasCost"))
        // 396p gas and the 30p gas standing charge only.
        assertEquals(426.0, today.number("totalCost"))
        assertEquals(JsonNull, today.days().single()["electricityKwh"])
    }

    // BDD: Only admins see the energy dashboard
    @Test
    fun `only admins see the energy dashboard`() = TestHeating().api {
        val sam = memberSam()
        assertEquals(HttpStatusCode.Forbidden, send(sam, HttpMethod.Get, "/api/dashboard").status)
        assertEquals(HttpStatusCode.Forbidden, send(sam, HttpMethod.Get, "/api/dashboard?period=week").status)
    }
}
