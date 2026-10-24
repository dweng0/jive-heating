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

/** A monitored device in no room, and its live power; null while it is offline. */
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
)

/** Where the dial turns amber ([green]: the typical overnight load) and red ([red]: the 90th percentile). */
@Serializable
data class PowerBands(val green: Double, val red: Double)

/** One house reading for the sparkline. */
@Serializable
data class PowerPoint(val at: String, val watts: Double?)

/**
 * What the live dial needs besides the live readings: its colour bands from the last 7 days (null with under
 * a day of readings) and the house readings of the last 30 minutes, oldest first.
 *
 * openapi: PowerSummary
 */
@Serializable
data class PowerSummary(val bands: PowerBands?, val recent: List<PowerPoint>)

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
            }
        }
    }

    fun now(): LivePowerView {
        val house = settings.get().electricityMeter?.let { meters[it] }
        val watts = house?.takeIf(::isLive)?.power
        val byRoom = monitored.all().groupBy { monitored.roomOf(it) }
        val rooms = byRoom.filterKeys { it != null }.mapNotNull { (room, plugs) ->
            val name = roomName(room!!) ?: return@mapNotNull null
            val live = plugs.mapNotNull(::plugWatts)
            RoomPower(room, name, if (live.isEmpty()) null else live.sum())
        }.sortedBy { it.name }
        val devices = byRoom[null].orEmpty().map { DevicePower(it, plugWatts(it)) }
        val accounted = rooms.sumOf { it.watts ?: 0.0 } + devices.sumOf { it.watts ?: 0.0 }
        return LivePowerView(
            watts = watts,
            at = house?.lastReading?.toString(),
            rooms = rooms,
            devices = devices,
            elseWatts = watts?.let { maxOf(0.0, it - accounted) },
        )
    }

    fun summary(): PowerSummary {
        val points = synchronized(recent) {
            prune()
            recent.map { (at, watts) -> PowerPoint(at.toString(), watts) }
        }
        return PowerSummary(bands(), points)
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
