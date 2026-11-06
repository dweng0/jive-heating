package heating.app.ui.energy

import heating.app.data.EnergyTarget
import heating.app.data.HouseHistoryPoint
import heating.app.data.LivePower
import heating.app.data.TodayPeak
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A room or energy area the second dial can follow. */
data class Place(val id: String, val name: String, val watts: Double?)

/** Every room and energy area in a live power message. "Everything else" and devices in no room or area are left out. */
fun places(live: LivePower?): List<Place> {
    if (live == null) return emptyList()
    return live.rooms.map { Place("room:${it.room}", it.name, it.watts) } + live.areas.map { Place("area:${it.area}", it.name, it.watts) }
}

/** What the second dial is on, and who is catching it up since when (ms). */
data class Busiest(val current: String? = null, val challenger: String? = null, val since: Long? = null)

const val LEAD_SHARE = 0.1
const val LEAD_WATTS = 50.0
const val LEAD_MS = 30_000L

/**
 * The second dial after a reading at [now] ms: it stays on its room until another is at least 10% and 50 W ahead for 30
 * seconds, so close rooms do not flicker; it moves at once when its room stops reporting.
 */
fun followBusiest(state: Busiest, all: List<Place>, now: Long): Busiest {
    val reporting = all.filter { it.watts != null }
    if (reporting.isEmpty()) return Busiest()
    val top = reporting.maxBy { it.watts!! }
    val current = reporting.firstOrNull { it.id == state.current } ?: return Busiest(top.id)
    if (top.id == current.id) return Busiest(current.id)
    val ahead = top.watts!! >= current.watts!! * (1 + LEAD_SHARE) && top.watts - current.watts >= LEAD_WATTS
    if (!ahead) return Busiest(current.id)
    if (state.challenger != top.id || state.since == null) return Busiest(current.id, top.id, now)
    if (now - state.since >= LEAD_MS) return Busiest(top.id)
    return state
}

/** The fuel gauge: a full tank of the daily target at midnight, draining as the house uses electricity. */
data class Fuel(
    /** 0 (E) to 1 (F): what is left of the target */
    val left: Double,
    /** Where the needle should be by now, 0..1 from E */
    val pace: Double,
    val leftKwh: Double,
    val overKwh: Double,
    /** Use so far minus expected use so far: below 0 is under pace */
    val paceKwh: Double,
    /** Over 80% of the target used */
    val low: Boolean,
)

const val LOW_FUEL = 0.8

fun fuel(t: EnergyTarget): Fuel = Fuel(
    left = ((t.kwh - t.usedKwh) / t.kwh).coerceIn(0.0, 1.0),
    pace = ((t.kwh - t.expectedKwh) / t.kwh).coerceIn(0.0, 1.0),
    leftKwh = maxOf(0.0, t.kwh - t.usedKwh),
    overKwh = maxOf(0.0, t.usedKwh - t.kwh),
    paceKwh = t.usedKwh - t.expectedKwh,
    low = t.usedKwh > t.kwh * LOW_FUEL,
)

private fun one(v: Double) = String.format(Locale.UK, "%.1f", v)

/** "7.0 kWh left" or "1.0 kWh over". */
fun fuelLeft(f: Fuel): String = if (f.overKwh > 0) "${one(f.overKwh)} kWh over" else "${one(f.leftKwh)} kWh left"

/** "5.0 of 12.0 kWh". */
fun fuelUsed(t: EnergyTarget): String = "of ${formatKwh(t.kwh)}"

/** "1.0 under pace" / "0.4 over pace": in kWh, said by the target beside it. */
fun paceLine(paceKwh: Double): String = "${one(kotlin.math.abs(paceKwh))} ${if (paceKwh <= 0) "under" else "over"} pace"

const val NO_TARGET = "No daily target"
const val NO_PLACES = "No rooms reporting"

/** "18:05" in the phone's timezone. */
fun clockTime(at: String, zone: ZoneId = ZoneId.systemDefault()): String =
    OffsetDateTime.parse(at).atZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("HH:mm"))

/** Today's peak as the peak gauge reads it: "6.20 kW" and "at 18:05". */
fun peakReading(p: TodayPeak?, zone: ZoneId = ZoneId.systemDefault()): Pair<String, String> =
    if (p == null) "–" to "None yet today" else dialReading(p.watts) to "at ${clockTime(p.at, zone)}"

/** One line of the peak's breakdown: name, watts and share, such as "Kitchen", "3.50 kW · 56%". */
fun peakLines(p: TodayPeak): List<Pair<String, String>> = p.parts.map { part ->
    val share = if (p.watts > 0) Math.round(part.watts / p.watts * 100) else 0
    part.name to "${dialReading(part.watts)} · $share%"
}

/** The chart's ranges: how much one shows at a time and each of its points, in ms. */
enum class HistoryRange(val label: String, val key: String, val span: Long, val step: Long) {
    DAY("Day", "day", 86_400_000L, 300_000L),
    WEEK("Week", "week", 7 * 86_400_000L, 3_600_000L),
    MONTH("Month", "month", 30 * 86_400_000L, 86_400_000L),
    YEAR("Year", "year", 365 * 86_400_000L, (30.4 * 86_400_000L).toLong()),
    ;

    /** Day and week are a line of power; month and year bars of kWh. */
    val bars: Boolean get() = this == MONTH || this == YEAR
}

/** Where the chart's right edge can be: not past now, and not so far back it shows nothing after the first sample. */
fun clampEnd(end: Long, span: Long, now: Long, earliest: Long?): Long {
    val least = if (earliest == null) now else minOf(now, earliest + span)
    return minOf(now, maxOf(least, end))
}

/** The latest time in [from]..[to] no loaded stretch covers, working back from [to]; null when all of it is covered. */
fun uncovered(covered: List<LongRange>, from: Long, to: Long): Long? {
    var end = to
    while (true) {
        val holding = covered.firstOrNull { it.first < end && it.last >= end } ?: return end
        end = holding.first
        if (end <= from) return null
    }
}

/** A point's time as the chart's readout says it: "Thu 5 Nov, 18:05", "Thu 5 Nov" or "Nov 2026". */
fun pointLabel(at: String, range: HistoryRange, zone: ZoneId = ZoneId.systemDefault()): String {
    val t = OffsetDateTime.parse(at).atZoneSameInstant(zone)
    return when (range) {
        HistoryRange.YEAR -> t.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.UK))
        HistoryRange.MONTH -> t.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))
        else -> t.format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.UK))
    }
}

/** The chart's readout for one point: its time, then label and value lines. */
fun pointReadout(p: HouseHistoryPoint, range: HistoryRange, currency: String, zone: ZoneId = ZoneId.systemDefault()): List<String> = buildList {
    add(pointLabel(p.at, range, zone))
    if (!range.bars) add("Power ${formatPower(p.watts)}")
    add("Used ${p.kwh?.let { if (range.bars) formatKwh(it) else String.format(Locale.UK, "%.2f kWh", it) } ?: "–"}")
    add("Cost ${formatCost(p.cost, currency)}")
    p.targetKwh?.let { add("Target ${formatKwh(it)}") }
    if (p.incomplete) add(READINGS_MISSING)
}

/** The trip counter: today's kWh rolling on at the last 5 minutes' average power between recorded figures. */
data class Trip(val day: String, val kwh: Double, val at: Long)

/**
 * The trip counter at [now] ms: on from [trip] at [watts], and never back, except that a new [day] starts from today's
 * [recorded] figure. A newer recorded figure that is higher is taken as it is.
 */
fun tripAt(trip: Trip?, day: String, recorded: Double, watts: Double?, now: Long): Trip {
    if (trip == null || trip.day != day) return Trip(day, recorded, now)
    val rolled = trip.kwh + (watts ?: 0.0) * maxOf(0L, now - trip.at) / 3_600_000_000.0
    return Trip(day, maxOf(rolled, recorded), now)
}

/** The house's average power over the 5 minutes up to [now] from its readings (ms, W); null with none. */
fun fiveMinuteAverage(readings: List<Pair<Long, Double?>>, now: Long): Double? =
    readings.filter { (at, w) -> w != null && at > now - 300_000 && at <= now }.map { it.second!! }.takeIf { it.isNotEmpty() }?.average()

/** "▲ 1.2 kWh vs yesterday" (and true: more than yesterday by now) or "▼ 0.5 kWh vs yesterday"; null with nothing yesterday. */
fun versusYesterday(todayKwh: Double, yesterday: heating.app.data.EnergyTotal?): Pair<String, Boolean>? {
    if (yesterday == null) return null
    val delta = todayKwh - yesterday.kwh
    return "${if (delta > 0) "▲" else "▼"} ${one(kotlin.math.abs(delta))} kWh vs yesterday" to (delta > 0)
}

enum class OdometerPeriod(val label: String) { TODAY("Day"), WEEK("Week"), MONTH("Month"), YEAR("Year") }

fun odometerText(kwh: Double): String = one(kwh)
