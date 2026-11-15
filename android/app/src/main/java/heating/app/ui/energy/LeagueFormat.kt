package heating.app.ui.energy

import heating.app.data.ItemPower
import heating.app.data.LivePower
import kotlin.math.roundToInt

/** Under this a meter counts as off. */
const val OFF_UNDER_W = 5.0

enum class LeagueState { ON, ELSE, OFF, NO_READING }

/** A row of the league table; [share] is of the house's power now (0..1), null without a live house reading or when off. */
data class LeagueRow(val key: String, val name: String, val watts: Double?, val share: Double?, val state: LeagueState, val item: ItemPower? = null)

/** Whether the table ranks items (each meter) or rooms (each place). */
enum class LeagueView { ITEMS, ROOMS }

/** Something the table can rank: an item or a place, with its live watts. */
private data class Entry(val key: String, val name: String, val watts: Double?, val item: ItemPower? = null)

/**
 * Ranks [entries] biggest first: those using power, then "Everything else" ([rest], when there is a house reading),
 * then those under 5 W as "Off" in name order, then those with no reading.
 */
private fun rank(entries: List<Entry>, house: Double?, rest: Double?): List<LeagueRow> {
    fun shareOf(w: Double): Double? = if (house != null && house > 0) minOf(1.0, w / house) else null
    fun row(e: Entry, state: LeagueState) =
        LeagueRow(e.key, e.name, e.watts, if (state == LeagueState.ON) e.watts?.let(::shareOf) else null, state, e.item)
    val byName = compareBy<Entry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
    val on = entries.filter { (it.watts ?: -1.0) >= OFF_UNDER_W }.sortedWith(compareByDescending<Entry> { it.watts }.then(byName))
    val off = entries.filter { it.watts != null && it.watts < OFF_UNDER_W }.sortedWith(byName)
    val none = entries.filter { it.watts == null }.sortedWith(byName)
    val rows = on.map { row(it, LeagueState.ON) }.toMutableList()
    if (rest != null) rows += LeagueRow("everything-else", "Everything else", rest, shareOf(rest), LeagueState.ELSE)
    return rows + off.map { row(it, LeagueState.OFF) } + none.map { row(it, LeagueState.NO_READING) }
}

/** The house use none of [counted] accounts for, never counting a monitored device inside a measured area twice. */
private fun restOf(house: Double?, items: List<ItemPower>, counted: List<ItemPower>): Double? {
    if (house == null) return null
    val measured = items.filter { it.job == "area" && it.watts != null && it.area != null }.map { it.area }.toSet()
    val sum = counted.filter { it.watts != null && !(it.job == "monitored" && it.area != null && it.area in measured) }
        .sumOf { maxOf(0.0, it.watts!!) }
    return maxOf(0.0, house - sum)
}

/** Adds up [items]' readings; null when none of them has one. */
private fun total(items: List<ItemPower>): Double? = items.mapNotNull { it.watts }.takeIf { it.isNotEmpty() }?.sum()

/**
 * The rows, biggest first: items using power, then "Everything else" (the house use no item accounts for), then items
 * under 5 W as "Off" in name order, then items with no reading. A monitored device inside an energy area whose
 * whole-area meter is reading is already in that meter's figure, so it is not counted twice in everything else.
 */
fun leagueRows(live: LivePower?, view: LeagueView = LeagueView.ITEMS): List<LeagueRow> {
    if (view == LeagueView.ROOMS) return leagueRoomRows(live)
    val items = live?.items.orEmpty()
    return rank(items.map { Entry(it.meter, it.name, it.watts, it) }, live?.watts, restOf(live?.watts, items, items))
}

/**
 * The rows by place: a room (its monitored devices), an energy area (its whole-area meter while that is reading, else
 * its monitored devices in no room), "Not in a room" for monitored devices in neither, and "Everything else". Meters
 * with no job belong to no place and are left out, so their power falls in everything else.
 */
private fun leagueRoomRows(live: LivePower?): List<LeagueRow> {
    val items = live?.items.orEmpty()
    val placed = items.filter { it.job == "area" || it.job == "monitored" }
    val monitored = placed.filter { it.job == "monitored" }
    val roomName = live?.rooms.orEmpty().associate { it.room to it.name }
    val areaName = live?.areas.orEmpty().associate { it.area to it.name }
    val entries = mutableListOf<Entry>()
    monitored.mapNotNull { it.room }.distinct().forEach { room ->
        entries += Entry("room:$room", roomName[room] ?: room, total(monitored.filter { it.room == room }))
    }
    placed.filter { it.area != null && (it.job == "area" || it.room == null) }.mapNotNull { it.area }.distinct().forEach { area ->
        val whole = placed.firstOrNull { it.job == "area" && it.area == area && it.watts != null }
        val watts = whole?.watts ?: total(monitored.filter { it.area == area && it.room == null })
        entries += Entry("area:$area", areaName[area] ?: area, watts)
    }
    val loose = monitored.filter { it.room == null && it.area == null }
    if (loose.isNotEmpty()) entries += Entry("not-in-a-room", "Not in a room", total(loose))
    return rank(entries, live?.watts, restOf(live?.watts, items, placed))
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
