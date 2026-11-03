package heating.energy

import heating.ApiException
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Power, use and cost over one five minutes, hour or day from [at]. [incomplete] when a reading is missing: the figures are
 * then only what was recorded, and null when nothing was. [cost] is in minor units at the electricity price alone.
 *
 * openapi: UsagePoint
 */
@Serializable
data class UsagePoint(val at: String, val watts: Double?, val kwh: Double?, val cost: Double?, val incomplete: Boolean)

/** openapi: PlugUsage */
@Serializable
data class PlugUsage(val meter: String, val points: List<UsagePoint>)

/**
 * A room's electricity over time for its energy dashboard. [room] is the sum of the plugs at the time, in the room each was
 * in when recorded; [plugs] are those in the room now. [earliest] is how far back it can be read. Admin only.
 *
 * openapi: RoomUsage
 */
@Serializable
data class RoomUsage(val currency: String, val from: String, val to: String, val earliest: String?, val room: List<UsagePoint>, val plugs: List<PlugUsage>)

/**
 * What no plug accounts for over time, in the shape of [RoomUsage] without its plugs.
 *
 * openapi: EverythingElseUsage
 */
@Serializable
data class EverythingElseUsage(val currency: String, val from: String, val to: String, val earliest: String?, val room: List<UsagePoint>)

/** How much a usage request covers, and how finely. A null [point] is one per five-minute sample. */
enum class UsageWindow(val length: Duration, val point: ChronoUnit?) {
    DAY(Duration.ofHours(24), null),
    WEEK(Duration.ofDays(7), ChronoUnit.HOURS),
    MONTH(Duration.ofDays(30), ChronoUnit.DAYS),
    ;

    companion object {
        fun of(name: String?) = when (name ?: "24h") {
            "24h" -> DAY
            "7d" -> WEEK
            "30d" -> MONTH
            else -> throw ApiException.badRequest("window must be 24h, 7d or 30d")
        }
    }
}

/** Works out [RoomUsage] and [EverythingElseUsage] from the five-minute samples. Costs use today's price, like the Dashboard. */
class EnergyUsageReport(
    private val energy: EnergyHistory,
    private val settings: EnergySettingsRepository,
    private val monitored: MonitoredMeters,
    private val zone: ZoneId,
    private val clock: Clock,
) {
    fun ofRoom(room: String, window: UsageWindow, to: Instant?): RoomUsage {
        val end = end(to)
        val from = end.minus(window.length)
        val prices = settings.get()
        val samples = energy.roomSamples(room, from, end)
        val plugs = monitored.all().filter { monitored.roomOf(it) == room }.sorted().map { meter ->
            PlugUsage(meter, points(samples.filter { it.meter == meter }, window, prices))
        }
        return RoomUsage(prices.currency, format(from), format(end), energy.earliestInRoom(room)?.let(::format), points(samples, window, prices), plugs)
    }

    fun ofEverythingElse(window: UsageWindow, to: Instant?): EverythingElseUsage {
        val end = end(to)
        val from = end.minus(window.length)
        val prices = settings.get()
        // Plugs and whole-area meters are sampled together with the house, so each five minutes lines up. Each clamp's
        // figure comes off, and each plug not behind one.
        val areaSamples = energy.allAreaSamples(from, end)
        val clamped = AreaCounting.clamped(areaSamples)
        val plugs = (monitored.all().flatMap { energy.deviceSamples(it, from, end) }.filterNot { AreaCounting.behindClamp(it, clamped) } + areaSamples)
            .groupBy { it.at }
        val rest = energy.samples(from, end).filter { it.at > from }.map { house ->
            val here = plugs[house.at].orEmpty()
            val missing = here.any { it.kwh == null }
            // A plug's use known without its power, from before power was kept, leaves the rest's power unknown.
            val powerKnown = here.none { it.kwh != null && it.watts == null }
            Rest(
                house.at,
                house.watts?.takeIf { powerKnown }?.let { maxOf(0.0, it - here.sumOf { p -> p.watts ?: 0.0 }) },
                house.kwh?.let { maxOf(0.0, it - here.sumOf { p -> p.kwh ?: 0.0 }) },
                missing || house.kwh == null,
            )
        }
        val points = rest.groupBy { bucket(it.at, window) }.map { (at, inPoint) ->
            val kwh = inPoint.mapNotNull { it.kwh }.takeIf { it.isNotEmpty() }?.sum()
            point(at, inPoint.mapNotNull { it.watts }.takeIf { it.isNotEmpty() }?.average(), kwh, inPoint.any { it.incomplete }, prices)
        }
        return EverythingElseUsage(prices.currency, format(from), format(end), energy.earliest()?.let(::format), points)
    }

    private class Rest(val at: Instant, val watts: Double?, val kwh: Double?, val incomplete: Boolean)

    /** A point for each stretch of [samples]: use added up, and each plug's average power over what it recorded added up. */
    private fun points(samples: List<MeterSample>, window: UsageWindow, prices: EnergySettings): List<UsagePoint> =
        samples.groupBy { bucket(it.at, window) }.toSortedMap().map { (at, inPoint) ->
            val kwh = inPoint.mapNotNull { it.kwh }.takeIf { it.isNotEmpty() }?.sum()
            val watts = inPoint.groupBy { it.meter }.values
                .mapNotNull { plug -> plug.mapNotNull { it.watts }.takeIf { it.isNotEmpty() }?.average() }
                .takeIf { it.isNotEmpty() }?.sum()
            point(at, watts, kwh, inPoint.any { it.kwh == null }, prices)
        }

    private fun point(at: Instant, watts: Double?, kwh: Double?, incomplete: Boolean, prices: EnergySettings) = UsagePoint(
        format(at),
        watts?.let { round(it, 1) },
        kwh?.let { round(it, 4) },
        kwh?.let { round(it * prices.electricityPencePerKwh, 2) },
        incomplete,
    )

    /** The sample's own time; or the hour or day it counts in: a sample covers the five minutes before it. */
    private fun bucket(at: Instant, window: UsageWindow): Instant =
        window.point?.let { at.minusMillis(1).atZone(zone).truncatedTo(it).toInstant() } ?: at

    private fun end(to: Instant?): Instant = clock.instant().let { now -> if (to == null || to.isAfter(now)) now else to }

    private fun format(at: Instant) = at.atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun round(v: Double, places: Int) = BigDecimal.valueOf(v).setScale(places, RoundingMode.HALF_UP).toDouble()
}
