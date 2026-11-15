package heating.app.ui.energy

import heating.app.data.ItemPower
import heating.app.data.LivePower
import kotlin.math.roundToInt

/** Under this a meter counts as off. */
const val OFF_UNDER_W = 5.0

enum class LeagueState { ON, ELSE, OFF, NO_READING }

/** A row of the league table; [share] is of the house's power now (0..1), null without a live house reading or when off. */
data class LeagueRow(val key: String, val name: String, val watts: Double?, val share: Double?, val state: LeagueState, val item: ItemPower? = null)

/**
 * The rows, biggest first: items using power, then "Everything else" (the house use no item accounts for), then items
 * under 5 W as "Off" in name order, then items with no reading. A monitored device inside an energy area whose
 * whole-area meter is reading is already in that meter's figure, so it is not counted twice in everything else.
 */
fun leagueRows(live: LivePower?): List<LeagueRow> {
    val items = live?.items.orEmpty()
    val house = live?.watts
    fun shareOf(w: Double): Double? = if (house != null && house > 0) minOf(1.0, w / house) else null
    fun row(i: ItemPower, state: LeagueState) =
        LeagueRow(i.meter, i.name, i.watts, if (state == LeagueState.ON) i.watts?.let(::shareOf) else null, state, i)
    val byName = compareBy<ItemPower, String>(String.CASE_INSENSITIVE_ORDER) { it.name }

    val on = items.filter { (it.watts ?: -1.0) >= OFF_UNDER_W }.sortedWith(compareByDescending<ItemPower> { it.watts }.then(byName))
    val off = items.filter { it.watts != null && it.watts < OFF_UNDER_W }.sortedWith(byName)
    val none = items.filter { it.watts == null }.sortedWith(byName)

    val rows = on.map { row(it, LeagueState.ON) }.toMutableList()
    if (house != null) {
        val measured = items.filter { it.job == "area" && it.watts != null && it.area != null }.map { it.area }.toSet()
        val counted = items.filter { it.watts != null && !(it.job == "monitored" && it.area != null && it.area in measured) }
            .sumOf { maxOf(0.0, it.watts!!) }
        val rest = maxOf(0.0, house - counted)
        rows += LeagueRow("everything-else", "Everything else", rest, shareOf(rest), LeagueState.ELSE)
    }
    return rows + off.map { row(it, LeagueState.OFF) } + none.map { row(it, LeagueState.NO_READING) }
}

/** "9.50 kW", "90 W"; "Off" under 5 W, "No reading" without one. */
fun leagueWatts(row: LeagueRow): String = when (row.state) {
    LeagueState.OFF -> "Off"
    LeagueState.NO_READING -> "No reading"
    else -> dialReading(row.watts)
}

/** "86%", or blank when it has no share. */
fun leagueShare(row: LeagueRow): String = row.share?.let { "${(it * 100).roundToInt()}%" } ?: ""

/** A row changes place at most this often, so figures flickering around each other do not swap back and forth. */
const val SWAP_EVERY_MS = 2000L

/** The order the table is drawn in, and when each row last changed place. */
data class Placing(val order: List<String> = emptyList(), val movedAt: Map<String, Long> = emptyMap())

/**
 * Moves the drawn order towards [desired] by swapping neighbours, except that a row which changed place less than 2 s
 * ago holds its place (and nothing passes it) until its time is up. New rows go straight to their place; gone rows drop.
 */
fun placeRows(prev: Placing, desired: List<String>, now: Long): Placing {
    val rank = desired.withIndex().associate { (i, k) -> k to i }
    val order = prev.order.filter { it in rank }.toMutableList()
    val held = order.toSet()
    desired.forEachIndexed { i, k -> if (k !in held) order.add(minOf(i, order.size), k) }
    fun locked(k: String) = prev.movedAt[k]?.let { now - it < SWAP_EVERY_MS } ?: false
    val moved = mutableSetOf<String>()
    var swapped = true
    while (swapped) {
        swapped = false
        for (i in 0 until order.size - 1) {
            val a = order[i]
            val b = order[i + 1]
            if (rank.getValue(a) > rank.getValue(b) && !locked(a) && !locked(b)) {
                order[i] = b
                order[i + 1] = a
                moved += a
                moved += b
                swapped = true
            }
        }
    }
    val movedAt = order.mapNotNull { k -> if (k in moved) k to now else prev.movedAt[k]?.let { k to it } }.toMap()
    return Placing(order, movedAt)
}

/** When the next held row is free to move, or null when the drawn order already matches [desired]. */
fun nextUnlock(p: Placing, desired: List<String>, now: Long): Long? =
    if (p.order == desired) null else p.movedAt.values.map { it + SWAP_EVERY_MS }.filter { it > now }.minOrNull()
