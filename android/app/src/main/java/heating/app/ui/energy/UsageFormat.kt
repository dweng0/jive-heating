package heating.app.ui.energy

import heating.app.data.EnergyUsage
import heating.app.data.RoomPlug
import heating.app.data.RoomPlugs
import heating.app.data.UsagePoint
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What a chart can draw. */
enum class UsageMetric(val label: String) { WATTS("Power"), KWH("Use"), COST("Cost") }

/** How much a chart shows, and how far apart its points are. */
enum class UsageWindowChoice(val value: String, val length: Duration, val step: Duration) {
    DAY("24h", Duration.ofHours(24), Duration.ofMinutes(5)),
    WEEK("7d", Duration.ofDays(7), Duration.ofHours(1)),
    MONTH("30d", Duration.ofDays(30), Duration.ofDays(1)),
}

/** The series a chart can draw, by plug name; the room or everything else itself under [WHOLE]. */
const val WHOLE = ""

private fun instant(iso: String): Instant = OffsetDateTime.parse(iso).toInstant()

/** What has been read so far: everything from [loadedFrom] on. */
data class LoadedUsage(val series: Map<String, List<UsagePoint>>, val loadedFrom: Instant, val earliest: Instant?, val currency: String)

/**
 * [page] added to what is [loaded]. An hour or day cut in two by the start of the page is left for the next page, which asks up
 * to the start of the first whole one, so it is never counted from only part of its samples.
 */
fun withPage(loaded: LoadedUsage?, page: EnergyUsage, step: Duration): LoadedUsage {
    val earliest = page.earliest?.let(::instant)
    val from = instant(page.from)
    val first = page.room.firstOrNull()?.let { instant(it.at) }
    val cut = first != null && first < from && earliest != null && earliest < from
    val start = when {
        !cut -> from
        page.room.size > 1 -> instant(page.room[1].at)
        else -> minOf(instant(page.to), first!!.plus(step))
    }
    fun kept(points: List<UsagePoint>) = if (cut) points.filter { instant(it.at) >= start } else points
    val series = loaded?.series.orEmpty().toMutableMap()
    fun add(key: String, points: List<UsagePoint>) {
        series[key] = (series[key].orEmpty().associateBy { it.at } + kept(points).associateBy { it.at }).values.sortedBy { instant(it.at) }
    }
    add(WHOLE, page.room)
    page.plugs.forEach { add(it.meter, it.points) }
    return LoadedUsage(series, loaded?.loadedFrom?.let { minOf(it, start) } ?: start, earliest, page.currency)
}

/** Where a chart ending at [end] may go: no later than [now], and no further back than shows [earliest] at its left edge. */
fun clampEnd(end: Instant, now: Instant, earliest: Instant?, length: Duration): Instant {
    val first = earliest?.let { minOf(now, it.plus(length)) } ?: now
    return minOf(now, maxOf(first, end))
}

/** One point of a chart; a null [point] only breaks the line, where points are over two steps apart. */
data class UsageRow(val at: Instant, val point: UsagePoint?)

fun UsageRow.value(metric: UsageMetric): Double? = when (metric) {
    UsageMetric.WATTS -> point?.watts
    UsageMetric.KWH -> point?.kwh
    UsageMetric.COST -> point?.cost
}

/** A chart's rows from [from] to [to], and one either side, so the line runs off the edges instead of stopping short of them. */
fun usageRows(points: List<UsagePoint>, step: Duration, from: Instant, to: Instant): List<UsageRow> {
    val rows = mutableListOf<UsageRow>()
    points.forEach { p ->
        val at = instant(p.at)
        rows.lastOrNull()?.at?.let { last -> if (Duration.between(last, at) > step.multipliedBy(2)) rows += UsageRow(last.plus(step), null) }
        rows += UsageRow(at, p)
    }
    val first = rows.indexOfFirst { it.at >= from && it.at <= to }
    if (first < 0) return emptyList()
    val last = rows.indexOfLast { it.at >= from && it.at <= to }
    return rows.subList(max(0, first - 1), min(rows.size, last + 2))
}

/** The runs of rows with a value: the line breaks between them, instead of joining across or dropping to 0. */
fun lineRuns(rows: List<UsageRow>, metric: UsageMetric): List<List<UsageRow>> {
    val runs = mutableListOf<MutableList<UsageRow>>()
    var open = false
    rows.forEach { r ->
        if (r.value(metric) == null) open = false
        else {
            if (!open) runs += mutableListOf<UsageRow>()
            runs.last() += r
            open = true
        }
    }
    return runs
}

/** The row at [at], if one with a point is within a step of it. */
fun rowAt(rows: List<UsageRow>, at: Instant, step: Duration): UsageRow? =
    rows.filter { it.point != null }.minByOrNull { abs(Duration.between(it.at, at).toMillis()) }
        ?.takeIf { abs(Duration.between(it.at, at).toMillis()) <= step.toMillis() }

/** A five-minute point's use is small: "0.008 kWh", "1.6 kWh". */
fun pointKwh(kwh: Double?): String = when {
    kwh == null -> "–"
    kwh < 1 -> String.format(Locale.UK, "%.3f kWh", kwh)
    else -> String.format(Locale.UK, "%.1f kWh", kwh)
}

/** A five-minute point's cost is under a penny: "0.21p", "£1.25". */
fun pointCost(cost: Double?, currency: String): String =
    if (cost != null && cost < 10) String.format(Locale.UK, "%.2f%s", cost, if (currency == "GBP") "p" else "c") else formatCost(cost, currency)

fun metricValue(metric: UsageMetric, value: Double, currency: String): String = when (metric) {
    UsageMetric.WATTS -> formatPower(value)
    UsageMetric.KWH -> pointKwh(value)
    UsageMetric.COST -> pointCost(value, currency)
}

/** What a chart says at a point: its figures, "No reading", and "Some readings missing" where one was. */
fun pointLines(row: UsageRow?, currency: String): List<String> {
    val p = row?.point
    if (p == null || (p.watts == null && p.kwh == null)) return listOf("No reading")
    return listOfNotNull("${formatPower(p.watts)} · ${pointKwh(p.kwh)} · ${pointCost(p.cost, currency)}", "Some readings missing".takeIf { p.incomplete })
}

/** A chart for the whole room then one for each plug; a room with one plug has its chart alone, as the room's would only repeat it. */
fun usageSlides(roomName: String, meters: List<String>): List<Pair<String, String>> =
    if (meters.size == 1) listOf(meters[0] to meters[0]) else listOf(WHOLE to roomName) + meters.map { it to it }

/** An area's carousel: the whole area, then each of its [plugs] by its label in [meters]. */
fun areaSlides(areaName: String, plugs: List<String>, meters: List<heating.app.data.Meter>): List<Pair<String, String>> =
    listOf(WHOLE to areaName) + plugs.map { p -> p to (meters.firstOrNull { it.name == p }?.shownAs ?: p) }

/** What the plug table calls a plug: its label, else its meter name. */
fun plugName(plug: heating.app.data.RoomPlug): String = plug.label?.takeIf { it.isNotBlank() } ?: plug.meter

enum class PlugColumn(val label: String, val width: Int) {
    METER("Plug", 0),
    STATE("State", 72),
    POWER("Power now", 88),
    SHARE("Share today", 88),
    KWH("Use today", 88),
    COST("Cost today", 104),
}

data class PlugSort(val column: PlugColumn = PlugColumn.COST, val descending: Boolean = true)

/** A heading picked: sorts by it, and again reverses it. Words read A to Z first, figures biggest first. */
fun nextSort(sort: PlugSort, column: PlugColumn): PlugSort =
    if (sort.column == column) sort.copy(descending = !sort.descending)
    else PlugSort(column, descending = column != PlugColumn.METER && column != PlugColumn.STATE)

fun plugState(plug: RoomPlug): String = if (plug.offlineSince != null) "Offline" else "Online"

/** Share of the room's use today, "75%", or "–" when the room has used nothing. */
fun plugShare(kwh: Double, plugs: RoomPlugs): String = if (plugs.kwh > 0) "${(kwh / plugs.kwh * 100).roundToInt()}%" else "–"

/** [plugs] by [sort]; a plug with no reading of power is below every plug with one, whichever way power is sorted. */
fun sortPlugs(plugs: List<RoomPlug>, sort: PlugSort): List<RoomPlug> {
    val byName = compareBy<RoomPlug> { it.meter }
    val sign = if (sort.descending) -1 else 1
    fun signed(c: Comparator<RoomPlug>) = Comparator<RoomPlug> { a, b -> sign * c.compare(a, b) }.then(byName)
    return plugs.sortedWith(
        when (sort.column) {
            PlugColumn.METER -> signed(byName)
            PlugColumn.STATE -> signed(compareBy(::plugState))
            PlugColumn.POWER -> compareBy<RoomPlug> { it.power == null }.then(signed(compareBy { it.power ?: 0.0 }))
            PlugColumn.SHARE, PlugColumn.KWH -> signed(compareBy { it.kwh })
            PlugColumn.COST -> signed(compareBy { it.cost })
        },
    )
}

data class MovedOut(val kwh: Double, val cost: Double)

/** What plugs since moved out used here today: the room's figures less those of the plugs in it now; null when nothing. */
fun movedOut(plugs: RoomPlugs): MovedOut? {
    val kwh = plugs.kwh - plugs.plugs.sumOf { it.kwh }
    return if (kwh >= 0.005) MovedOut(kwh, max(0.0, plugs.cost - plugs.plugs.sumOf { it.cost })) else null
}

/** "Now": its power, "No reading" with nothing reporting, never an old figure; nothing yet while connecting. */
fun nowText(connected: Boolean, watts: Double?): String = when {
    !connected -> "–"
    watts == null -> "No reading"
    else -> formatPower(watts)
}
