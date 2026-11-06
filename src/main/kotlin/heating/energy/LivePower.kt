package heating.energy

import heating.Change
import heating.ChangeNotifier
import heating.Periodic
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil

/** A room's live power: the sum of its monitored plugs reporting now; null when none is. */
@Serializable
data class RoomPower(val room: String, val name: String, val watts: Double?)

/**
 * An energy area's live power: its whole-area meter's if it has one (null while that has no live reading, never its
 * plugs' instead), else the sum of its plugs reporting now (null when none is).
 */
@Serializable
data class AreaPower(val area: String, val name: String, val watts: Double?)

/** A monitored device in no room or area, and its live power; null while it is offline. */
@Serializable
data class DevicePower(val meter: String, val watts: Double?)

/**
 * The house's power right now, as sent on /ws/power. [watts] is null when there is no live house reading;
 * [at] is when the house meter was last read. [elseWatts] is the house use no room or device accounts for,
 * never below 0, and null while [watts] is.
 *
 * openapi: LivePower
 */
@Serializable
data class LivePowerView(
    val watts: Double?,
    val at: String?,
    val rooms: List<RoomPower> = emptyList(),
    val devices: List<DevicePower> = emptyList(),
    val elseWatts: Double? = null,
    val areas: List<AreaPower> = emptyList(),
)

/** Where the dial turns amber ([green]: the typical overnight load) and red ([red]: the 90th percentile). */
@Serializable
data class PowerBands(val green: Double, val red: Double)

/** One house reading for the sparkline. */
@Serializable
data class PowerPoint(val at: String, val watts: Double?)

/** What made up today's peak: a room, an energy area, a device in neither, or "else" (what none of them accounts for). */
@Serializable
data class PeakPart(val kind: String, val id: String?, val name: String, val watts: Double)

/**
 * The highest five-minute average house power since midnight, when it was (the sample's time), and what was using it
 * then, largest first.
 */
@Serializable
data class TodayPeak(val watts: Double, val at: String, val parts: List<PeakPart>)

/**
 * What the dials need besides the live readings: the colour bands from the last 7 days (null with under a day of
 * readings), the house readings of the last 30 minutes, oldest first, and today's peak (null before today's first sample).
 *
 * openapi: PowerSummary
 */
@Serializable
data class PowerSummary(val bands: PowerBands?, val recent: List<PowerPoint>, val todayPeak: TodayPeak? = null)

/**
 * The house meter's and monitored plugs' power for the live dial. Tells [changes] on every house or
 * monitored plug reading, and once more when the house readings stop for [LIVE_FOR], so a dial never shows
 * an old figure as live. Keeps the last 30 minutes of house readings in memory for the sparkline.
 */
class LivePower(
    private val meters: MeterRegistry,
    private val settings: EnergySettingsRepository,
    private val monitored: MonitoredMeters,
    private val roomName: (String) -> String?,
    private val history: EnergyHistory,
    private val timezone: ZoneId,
    private val changes: ChangeNotifier,
    private val clock: Clock,
    private val areas: EnergyAreas? = null,
) {
    @Volatile private var staleTold = false
    private val recent = ArrayDeque<Pair<Instant, Double?>>()

    fun start() {
        meters.onReading { meter ->
            when (meter.name) {
                settings.get().electricityMeter -> {
                    staleTold = false
                    synchronized(recent) {
                        recent.addLast(clock.instant() to meter.power)
                        prune()
                    }
                    changes.emit(Change.LivePowerChanged)
                }
                in monitored -> changes.emit(Change.LivePowerChanged)
                else -> if (areas?.measuredBy(meter.name) != null) changes.emit(Change.LivePowerChanged)
            }
        }
    }

    fun now(): LivePowerView {
        val house = settings.get().electricityMeter?.let { meters[it] }
        val watts = house?.takeIf(::isLive)?.power
        val byRoom = monitored.all().filter { monitored.areaOf(it) == null }.groupBy { monitored.roomOf(it) }
        val rooms = byRoom.filterKeys { it != null }.mapNotNull { (room, plugs) ->
            val name = roomName(room!!) ?: return@mapNotNull null
            val live = plugs.mapNotNull(::plugWatts)
            RoomPower(room, name, if (live.isEmpty()) null else live.sum())
        }.sortedBy { it.name }
        val devices = byRoom[null].orEmpty().map { DevicePower(it, plugWatts(it)) }
        val areaPower = areas?.list().orEmpty().map { area ->
            val plugs = monitored.all().filter { monitored.areaOf(it) == area.id }.mapNotNull(::plugWatts)
            val clamp = area.wholeMeter
            AreaPower(area.id, area.name, if (clamp != null) plugWatts(clamp) else plugs.takeIf { it.isNotEmpty() }?.sum())
        }
        // Each area counts once: its clamp's power, or its plugs' when it has no clamp.
        val accounted = rooms.sumOf { it.watts ?: 0.0 } + devices.sumOf { it.watts ?: 0.0 } + areaPower.sumOf { it.watts ?: 0.0 }
        return LivePowerView(
            watts = watts,
            at = house?.lastReading?.toString(),
            rooms = rooms,
            devices = devices,
            elseWatts = watts?.let { maxOf(0.0, it - accounted) },
            areas = areaPower,
        )
    }

    fun summary(): PowerSummary {
        val points = synchronized(recent) {
            prune()
            recent.map { (at, watts) -> PowerPoint(at.toString(), watts) }
        }
        return PowerSummary(bands(), points, todayPeak())
    }

    /** Today's highest five-minute house power. A sample at midnight covers the five minutes before, so counts in yesterday. */
    private fun todayPeak(): TodayPeak? {
        val now = clock.instant()
        val midnight = now.atZone(timezone).toLocalDate().atStartOfDay(timezone).toInstant()
        val peak = history.samples(midnight, now).filter { it.at > midnight && it.watts != null }.maxByOrNull { it.watts!! } ?: return null
        val house = peak.watts!!
        val clamps = history.allAreaSamples(peak.at.minusMillis(1), peak.at)
        val clamped = clamps.mapNotNull { it.room }.toSet()
        val plugs = history.deviceSamplesAt(peak.at).filter { it.watts != null && (it.area == null || it.area !in clamped) }
        val areaNames = areas?.list().orEmpty().associate { it.id to it.name }
        val parts = buildList {
            plugs.filter { it.area == null && it.room != null }.groupBy { it.room!! }.forEach { (room, here) ->
                roomName(room)?.let { add(PeakPart("room", room, it, here.sumOf { p -> p.watts!! })) }
            }
            clamps.filter { it.watts != null }.forEach { c -> areaNames[c.room]?.let { add(PeakPart("area", c.room, it, c.watts!!)) } }
            plugs.filter { it.area != null }.groupBy { it.area!! }.forEach { (area, here) ->
                areaNames[area]?.let { add(PeakPart("area", area, it, here.sumOf { p -> p.watts!! })) }
            }
            plugs.filter { it.area == null && it.room == null }.forEach { add(PeakPart("device", it.meter, it.meter!!, it.watts!!)) }
        }
        val rest = maxOf(0.0, house - parts.sumOf { it.watts })
        val all = (parts + PeakPart("else", null, "Everything else", rest)).filter { it.watts > 0 }.map { it.copy(watts = Math.round(it.watts * 10) / 10.0) }
        return TodayPeak(house, peak.at.atZone(timezone).toOffsetDateTime().toString(), all.sortedByDescending { it.watts })
    }

    val tick = Periodic {
        val meter = settings.get().electricityMeter?.let { meters[it] } ?: return@Periodic
        if (meter.lastReading != null && !isLive(meter) && !staleTold) {
            staleTold = true
            changes.emit(Change.LivePowerChanged)
        }
    }

    private fun bands(): PowerBands? {
        val now = clock.instant()
        // Only samples with average power: older ones have kWh from clamp totals that jump up and down.
        val samples = history.samples(now.minus(BANDS_FROM), now).filter { it.watts != null }
        // Under a day of readings says little about the household: one neutral colour.
        if (samples.isEmpty() || samples.first().at.isAfter(now.minus(Duration.ofDays(1)))) return null
        val known = samples.map { it.at to it.watts!! }
        val nights = known.filter { it.first.atZone(timezone).hour < OVERNIGHT_UNTIL }.map { it.second }.sorted()
        if (nights.isEmpty()) return null
        val all = known.map { it.second }.sorted()
        val median = if (nights.size % 2 == 1) nights[nights.size / 2] else (nights[nights.size / 2 - 1] + nights[nights.size / 2]) / 2
        return PowerBands(green = median, red = all[ceil(0.9 * all.size).toInt() - 1])
    }

    private fun plugWatts(name: String): Double? = meters[name]?.takeIf { meters.offlineSince(it) == null }?.power

    private fun isLive(meter: Meter): Boolean {
        val read = meter.lastReading ?: return false
        return meter.online && Duration.between(read, clock.instant()) < LIVE_FOR
    }

    private fun prune() {
        val from = clock.instant().minus(SPARKLINE)
        while (recent.isNotEmpty() && recent.first().first.isBefore(from)) recent.removeFirst()
    }

    companion object {
        /** A clamp reports every few seconds: after this long without a reading, the dial shows "No reading". */
        val LIVE_FOR: Duration = Duration.ofMinutes(2)
        private val SPARKLINE = Duration.ofMinutes(30)
        private val BANDS_FROM = Duration.ofDays(7)
        /** Overnight is 00:00 to 05:00 local time. */
        private const val OVERNIGHT_UNTIL = 5
    }
}
