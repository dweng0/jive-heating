package heating.app.ui.energy

import heating.app.data.EnergyTarget
import heating.app.data.HouseHistoryPoint
import heating.app.data.LivePower
import heating.app.data.TodayPeak
import java.time.LocalDate
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

/**
 * The usage counter: the house's kWh today, this week, this month and this year, rolling on at the last 5 minutes'
 * average power between recorded figures. [day] is the household's day it counts, as the service says; [at] is when it
 * was last moved on, in the service's time.
 */
data class Counter(val day: String, val today: Double, val week: Double, val month: Double, val year: Double, val at: Long) {
    operator fun get(period: UsagePeriod): Double = when (period) {
        UsagePeriod.TODAY -> today
        UsagePeriod.WEEK -> week
        UsagePeriod.MONTH -> month
        UsagePeriod.YEAR -> year
    }
}

/** The usage counter's periods in the order a tap goes through them, and their labels ("USAGE THIS WEEK"). */
enum class UsagePeriod(val label: String) {
    TODAY("today"), WEEK("this week"), MONTH("this month"), YEAR("this year");

    /** The period after this one, back to today after the year. */
    fun next(): UsagePeriod = entries[(ordinal + 1) % entries.size]
}

private fun millis(at: String): Long? = runCatching { java.time.OffsetDateTime.parse(at).toInstant().toEpochMilli() }.getOrNull()

/** How far ahead of the phone's clock the service's is, in ms; 0 when the totals do not say. */
fun serviceSkew(t: heating.app.data.EnergyTotals): Long = millis(t.at)?.let { it - t.receivedAt } ?: 0L

/** [c] carried into [day]: today starts again from 0, and so do the week on a Monday, the month on the 1st and the year on 1 January. */
fun turnOver(c: Counter, day: String, at: Long): Counter {
    val d = LocalDate.parse(day)
    return Counter(
        day, 0.0,
        week = if (d.dayOfWeek == java.time.DayOfWeek.MONDAY) 0.0 else c.week,
        month = if (d.dayOfMonth == 1) 0.0 else c.month,
        year = if (d.dayOfYear == 1) 0.0 else c.year,
        at = at,
    )
}

/**
 * The usage counter at [now] (the service's time): on from [c] at [watts], and never back. Totals for the counter's day
 * are taken where higher; totals for a later day start it again from them; totals for a day it has left are ignored.
 * At the totals' dayEndsAt it turns over into the next day without waiting for new totals.
 */
fun counterAt(c: Counter?, t: heating.app.data.EnergyTotals, watts: Double?, now: Long): Counter {
    val day = t.day.ifEmpty { LocalDate.now().toString() }
    val fromTotals = Counter(day, t.today.kwh, t.week.kwh, t.month.kwh, t.year.kwh, now)
    var next = if (c == null || day > c.day) fromTotals else {
        val kwh = (watts ?: 0.0) * maxOf(0L, now - c.at) / 3_600_000_000.0
        val rolled = Counter(c.day, c.today + kwh, c.week + kwh, c.month + kwh, c.year + kwh, now)
        if (day == c.day) Counter(day, maxOf(rolled.today, t.today.kwh), maxOf(rolled.week, t.week.kwh), maxOf(rolled.month, t.month.kwh), maxOf(rolled.year, t.year.kwh), now)
        else rolled
    }
    val endsAt = millis(t.dayEndsAt)
    if (next.day == day && endsAt != null && now >= endsAt) next = turnOver(next, LocalDate.parse(day).plusDays(1).toString(), now)
    return next
}

/**
 * When the dashboard should next fetch its day figures (phone time): [every] ms after the last fetch, or just after the
 * household's midnight if that is sooner. [jitter] spreads the midnight fetches out.
 */
fun nextFetchAt(lastFetch: Long, t: heating.app.data.EnergyTotals?, every: Long, jitter: Long): Long {
    val regular = lastFetch + every
    val endsAt = t?.let { millis(it.dayEndsAt) } ?: return regular
    return minOf(regular, endsAt - serviceSkew(t) + jitter)
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

/** "GAS · THU 5 NOV" for the gas counter's day. */
fun gasDayLabel(date: String): String =
    "GAS · " + java.time.LocalDate.parse(date).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.UK)).uppercase(java.util.Locale.UK)

/** "▲ 4.2 kWh vs Wed" and whether it is more: the gas day against the day before; null with no day before. */
fun gasDelta(gas: heating.app.data.GasDayTotal): Pair<String, Boolean>? {
    val previous = gas.previousKwh ?: return null
    val delta = gas.kwh - previous
    val before = java.time.LocalDate.parse(gas.date).minusDays(1).format(java.time.format.DateTimeFormatter.ofPattern("EEE", java.util.Locale.UK))
    return "${if (delta > 0) "▲" else "▼"} ${one(kotlin.math.abs(delta))} kWh vs $before" to (delta > 0)
}

