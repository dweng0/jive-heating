package heating.gas

import heating.heatingJson
import heating.testing.MutableClock
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
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A smart meter that has reported a half-hour of gas for each entry of [slots]. */
class FakeGasMeter(val slots: MutableMap<Instant, Double> = sortedMapOf()) : GasMeter {
    /** Each readings() call's range, in order. */
    val asked = mutableListOf<Pair<Instant, Instant>>()
    var fails = false

    override fun reported(): ClosedRange<Instant>? {
        if (fails) throw java.io.IOException("Glowmarkt unreachable")
        return if (slots.isEmpty()) null else slots.keys.min()..slots.keys.max()
    }

    override fun readings(from: Instant, to: Instant): Map<Instant, Double> {
        if (fails) throw java.io.IOException("Glowmarkt unreachable")
        asked += from to to
        return slots.filterKeys { it in from..to }
    }
}

class GasMeterTest {
    private val start = Instant.parse("2026-10-31T00:00:00Z")
    private val last = Instant.parse("2026-11-05T23:30:00Z")

    /** Half-hours from [from] to [to], each using [kwh]. */
    private fun halfHours(from: Instant, to: Instant, kwh: Double): MutableMap<Instant, Double> =
        generateSequence(from) { it.plus(HALF_HOUR) }.takeWhile { it <= to }.associateWith { kwh }.toSortedMap()

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

    // BDD: Gas readings are fetched from the smart meter
    @Test
    fun `gas readings are fetched from the smart meter`() {
        val meter = FakeGasMeter(halfHours(start, last, 0.5))
        val t = TestHeating(clock = MutableClock(Instant.parse("2026-11-07T07:00:00Z")), gasMeter = meter)
        t.app.tick()
        val kept = t.app.gasReadings.between(start, last.plus(HALF_HOUR))
        assertEquals(6 * 48, kept.size)
        assertEquals(144.0, kept.values.sum(), 1e-9)
        // No reading asked for more than ten days at once, which is all Glowmarkt gives half-hourly.
        assertTrue(meter.asked.all { (from, to) -> Duration.between(from, to) < Duration.ofDays(10) })

        // Half an hour later the meter has reported one more half-hour: only that is fetched.
        meter.slots[last.plus(HALF_HOUR)] = 0.75
        meter.asked.clear()
        t.clock.advance(Duration.ofMinutes(29))
        t.app.tick()
        assertTrue(meter.asked.isEmpty(), "fetched again before 30 minutes")
        t.clock.advance(Duration.ofMinutes(1))
        t.app.tick()
        assertEquals(listOf(last.plus(HALF_HOUR) to last.plus(HALF_HOUR)), meter.asked)
        assertEquals(0.75, t.app.gasReadings.between(last.plus(HALF_HOUR), last.plus(HALF_HOUR).plus(HALF_HOUR)).values.single())

        // A failed fetch changes nothing and is tried again 30 minutes later.
        meter.slots[last.plus(HALF_HOUR).plus(HALF_HOUR)] = 1.0
        meter.fails = true
        t.clock.advance(Duration.ofMinutes(30))
        t.app.tick()
        assertEquals(6 * 48 + 1, t.app.gasReadings.between(start, Instant.parse("2026-11-08T00:00:00Z")).size)
        meter.fails = false
        t.clock.advance(Duration.ofMinutes(15))
        t.app.tick()
        assertEquals(6 * 48 + 1, t.app.gasReadings.between(start, Instant.parse("2026-11-08T00:00:00Z")).size)
        t.clock.advance(Duration.ofMinutes(15))
        t.app.tick()
        assertEquals(6 * 48 + 2, t.app.gasReadings.between(start, Instant.parse("2026-11-08T00:00:00Z")).size)

        // Readings are kept over a restart.
        t.restart()
        assertEquals(6 * 48 + 2, t.app.gasReadings.between(start, Instant.parse("2026-11-08T00:00:00Z")).size)

        // Without Bright credentials nothing is fetched.
        val none = TestHeating(clock = MutableClock(Instant.parse("2026-11-07T07:00:00Z")))
        none.app.tick()
        assertTrue(none.app.gasReadings.between(start, last.plus(HALF_HOUR)).isEmpty())
    }

    // BDD: The dashboard's gas is the meter's where it has reported
    @Test
    fun `the dashboard's gas is the meter's where it has reported`() {
        // 2 November: 48 half-hours that add up to 52.4 kWh. Other days 1.0 kWh a half-hour, up to the end of 5 November.
        val slots = halfHours(start, last, 1.0)
        halfHours(Instant.parse("2026-11-02T00:00:00Z"), Instant.parse("2026-11-02T23:30:00Z"), 52.4 / 48).forEach { (at, kwh) -> slots[at] = kwh }
        val meter = FakeGasMeter(slots)
        val t = TestHeating(clock = MutableClock(Instant.parse("2026-11-06T09:00:00Z")), gasMeter = meter)
        t.app.tick()
        // The boiler heats 09:00-11:00 on 6 November (not yet reported) and 09:00-10:00 on 5 November (reported: ignored).
        t.app.history.boilerChanged("heating", Instant.parse("2026-11-05T09:00:00Z"))
        t.app.history.boilerChanged("idle", Instant.parse("2026-11-05T10:00:00Z"))
        t.app.history.boilerChanged("heating", Instant.parse("2026-11-06T09:00:00Z"))
        t.app.history.boilerChanged("idle", Instant.parse("2026-11-06T11:00:00Z"))
        t.clock.set(Instant.parse("2026-11-07T07:00:00Z"))
        t.app.tick()

        val week = dashboard(t, "week")
        val days = week["days"]!!.jsonArray.map { it.jsonObject }.associateBy { it["date"]!!.jsonPrimitive.content }
        val prices = t.app.energySettings.get()
        days.getValue("2026-11-02").let {
            assertEquals(52.4, it.number("gasKwh"), 1e-9)
            assertEquals(0.0, it.number("gasEstimatedKwh"))
            assertEquals(Math.round(52.4 * prices.gasPencePerKwh * 10) / 10.0, it.number("gasCost"), 1e-9)
        }
        assertEquals(48.0, days.getValue("2026-11-05").number("gasKwh"), 1e-9)
        // Not reported yet: boiler hours times boilerAverageKw.
        days.getValue("2026-11-06").let {
            assertEquals(2 * prices.boilerAverageKw, it.number("gasKwh"), 1e-9)
            assertEquals(2 * prices.boilerAverageKw, it.number("gasEstimatedKwh"), 1e-9)
        }
        assertEquals(0.0, days.getValue("2026-11-07").number("gasKwh"))
        assertEquals(52.4 + 48.0 * 4 + 2 * prices.boilerAverageKw, week.number("gasKwh"), 1e-9)
        assertEquals(2 * prices.boilerAverageKw, week.number("gasEstimatedKwh"), 1e-9)
        assertEquals("2026-11-06T00:00:00Z", week["gasMeteredTo"]!!.jsonPrimitive.content)

        // A day reported only up to 10:00: the meter's kWh to then, plus the estimate after.
        meter.slots.keys.removeIf { it >= Instant.parse("2026-11-05T10:00:00Z") }
        val partial = TestHeating(clock = MutableClock(Instant.parse("2026-11-05T08:00:00Z")), gasMeter = meter)
        partial.app.history.boilerChanged("heating", Instant.parse("2026-11-05T09:00:00Z"))
        partial.app.history.boilerChanged("idle", Instant.parse("2026-11-05T12:00:00Z"))
        partial.clock.set(Instant.parse("2026-11-05T13:00:00Z"))
        partial.app.tick()
        val today = dashboard(partial, "today")
        assertEquals(20.0 + 2 * prices.boilerAverageKw, today.number("gasKwh"), 1e-9)
        assertEquals(2 * prices.boilerAverageKw, today.number("gasEstimatedKwh"), 1e-9)

        // With no meter readings, gas is all estimate and gasMeteredTo is null.
        val none = TestHeating(clock = MutableClock(Instant.parse("2026-11-07T07:00:00Z")))
        val noMeter = dashboard(none, "today")
        assertEquals(JsonNull, noMeter["gasMeteredTo"])
        assertEquals(noMeter.number("gasKwh"), noMeter.number("gasEstimatedKwh"))
    }

    // BDD: The car dashboard shows the gas meter's latest day
    @Test
    fun `the car dashboard shows the gas meter's latest day`() {
        // 4 November 63.8 kWh, 5 November 68.0 kWh, reported up to the end of 5 November.
        val slots = halfHours(Instant.parse("2026-11-04T00:00:00Z"), Instant.parse("2026-11-04T23:30:00Z"), 63.8 / 48)
        slots.putAll(halfHours(Instant.parse("2026-11-05T00:00:00Z"), last, 68.0 / 48))
        val t = TestHeating(clock = MutableClock(Instant.parse("2026-11-07T07:00:00Z")), gasMeter = FakeGasMeter(slots))
        t.app.tick()
        val gas = totals(t)["gas"]!!.jsonObject
        assertEquals("2026-11-05", gas["date"]!!.jsonPrimitive.content)
        assertEquals(68.0, gas.number("kwh"), 1e-9)
        assertEquals(Math.round(68.0 * t.app.energySettings.get().gasPencePerKwh * 100) / 100.0, gas.number("cost"), 1e-9)
        assertEquals(63.8, gas.number("previousKwh"), 1e-9)

        // Only 5 November reported: no day before.
        val one = TestHeating(clock = MutableClock(Instant.parse("2026-11-07T07:00:00Z")), gasMeter = FakeGasMeter(halfHours(Instant.parse("2026-11-05T00:00:00Z"), last, 1.0)))
        one.app.tick()
        assertEquals(JsonNull, totals(one)["gas"]!!.jsonObject["previousKwh"])

        // Reported only part of 5 November: no whole day yet.
        val part = TestHeating(clock = MutableClock(Instant.parse("2026-11-07T07:00:00Z")), gasMeter = FakeGasMeter(halfHours(Instant.parse("2026-11-05T00:00:00Z"), Instant.parse("2026-11-05T10:00:00Z"), 1.0)))
        part.app.tick()
        assertEquals(JsonNull, totals(part)["gas"])
        assertEquals(JsonNull, totals(TestHeating())["gas"])
    }

    private fun totals(t: TestHeating): JsonObject {
        var result: JsonObject? = null
        t.api { client ->
            val response = client.get("/api/energy/totals")
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            result = heatingJson.parseToJsonElement(response.bodyAsText()).jsonObject
        }
        return result!!
    }

    private companion object {
        val HALF_HOUR: Duration = Duration.ofMinutes(30)
    }
}
