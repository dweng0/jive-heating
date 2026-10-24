package heating.app.ui.energy

import heating.app.data.EnergyDashboard
import heating.app.data.LivePower
import heating.app.data.PowerBands
import heating.app.data.PowerPoint
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt

/** Below the overnight load, normal, the household's top tenth, and no bands yet (under a day of readings). */
enum class PowerZone { LOW, MID, HIGH, NEUTRAL }

/** Green up to the overnight load, red from the household's top tenth, amber between; neutral with no bands. */
fun powerZone(watts: Double, bands: PowerBands?): PowerZone = when {
    bands == null -> PowerZone.NEUTRAL
    watts <= bands.green -> PowerZone.LOW
    watts >= bands.red -> PowerZone.HIGH
    else -> PowerZone.MID
}

/** How long the needle takes to reach a new reading, ms. */
const val SWEEP_MS = 1000

/**
 * Where the needle is [elapsedMs] into a sweep from [from] to [to]: eases out, and never passes [to] on the
 * way, so a rise does not look like a spike.
 */
fun sweep(from: Double, to: Double, elapsedMs: Double): Double {
    val t = (elapsedMs / SWEEP_MS).coerceIn(0.0, 1.0)
    return from + (to - from) * (1 - (1 - t).pow(3))
}

/** The dial's full scale: a whole kW, well above the household's red band and what it shows now. */
fun dialMax(bands: PowerBands?, watts: Double?): Double {
    val wanted = maxOf(3000.0, (bands?.red ?: 0.0) * 1.25, (watts ?: 0.0) * 1.1)
    return ceil(wanted / 1000) * 1000
}

/** The big number on the dial: "850 W", "2.35 kW"; "No reading" while the house reading is stale. */
fun dialReading(watts: Double?): String = when {
    watts == null -> "No reading"
    watts >= 1000 -> String.format(Locale.UK, "%.2f kW", watts / 1000)
    else -> "${watts.roundToInt()} W"
}

/** "Today 8.0 kWh · £2.16" under the dial. */
fun todayLine(d: EnergyDashboard): String = "Today ${formatKwh(d.electricityKwh)} · ${formatCost(d.electricityCost, d.currency)}"

private val SPARKLINE: Duration = Duration.ofMinutes(30)

/** [recent] with [live]'s reading added (once), dropping readings more than 30 minutes older than it. */
fun withReading(recent: List<PowerPoint>, live: LivePower): List<PowerPoint> {
    val at = live.at ?: return recent
    val now = OffsetDateTime.parse(at)
    val kept = recent.filter { !OffsetDateTime.parse(it.at).isBefore(now.minus(SPARKLINE)) }
    return if (kept.lastOrNull()?.at == at) kept else kept + PowerPoint(at, live.watts)
}

/** One card under the dial: a room, a monitored device in no room, or everything else. */
data class LiveCard(
    val key: String,
    val name: String,
    val detail: String?,
    /** Live power, "–" when there is none. */
    val now: String,
    /** Share of the house's load now (0..1), while both are live. */
    val share: Double?,
    val today: String,
) {
    val shareText: String get() = share?.let { "${(it * 100).roundToInt()}% of the house" } ?: ""
}

/**
 * Rooms with monitored plugs, then devices in no room, then everything else (only while the house reading is
 * live), each with live power and [today]'s use.
 */
fun liveCards(live: LivePower, today: EnergyDashboard?): List<LiveCard> {
    val currency = today?.currency ?: "GBP"
    fun share(watts: Double?) = if (watts != null && live.watts != null && live.watts > 0) (watts / live.watts).coerceAtMost(1.0) else null
    fun day(kwh: Double?, cost: Double?) = "Today ${formatKwh(kwh)} · ${formatCost(cost, currency)}"
    val rooms = live.rooms.map { r ->
        val d = today?.rooms?.firstOrNull { it.room == r.room }
        LiveCard("room-${r.room}", r.name, null, formatPower(r.watts), share(r.watts), day(d?.kwh, d?.cost))
    }
    val devices = live.devices.map { p ->
        val d = today?.devices?.firstOrNull { it.meter == p.meter }
        LiveCard("device-${p.meter}", p.meter, "Not in a room", formatPower(p.watts), share(p.watts), day(d?.kwh, d?.cost))
    }
    val rest = live.elseWatts?.let { w ->
        val d = today?.everythingElse
        LiveCard("rest", "Everything else", "What no plug accounts for", formatPower(w), share(w), day(d?.kwh, d?.cost))
    }
    return rooms + devices + listOfNotNull(rest)
}
