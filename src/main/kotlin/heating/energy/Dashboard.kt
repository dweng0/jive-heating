package heating.energy

import heating.ApiException
import heating.history.HistoryRepository
import heating.rooms.Room
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** How far back the dashboard looks: today so far, or the last 7 or 30 days including today. */
enum class DashboardPeriod(val days: Long, private val daysAgo: Long = 0) {
    TODAY(1), YESTERDAY(1, 1), WEEK(7), MONTH(30);

    /** The period's first day, when it is [now]. */
    fun firstDay(now: ZonedDateTime): LocalDate = now.toLocalDate().minusDays(daysAgo + days - 1)

    /** When the period ends: [now] for one up to now, else the midnight it ended at. */
    fun end(now: ZonedDateTime): ZonedDateTime =
        if (daysAgo == 0L) now else now.toLocalDate().minusDays(daysAgo - 1).atStartOfDay(now.zone)

    companion object {
        fun of(name: String?) = when (name ?: "today") {
            "today" -> TODAY
            "yesterday" -> YESTERDAY
            "week" -> WEEK
            "month" -> MONTH
            else -> throw ApiException.badRequest("period must be today, yesterday, week or month")
        }
    }
}

/**
 * Use and cost for one day. Costs are in pence at today's prices. Electricity is null while no house
 * meter is chosen. [missingMinutes] is time with no record (the service was down, or the house meter
 * was stale): unknown, not zero, and any makes the day [incomplete].
 *
 * openapi: EnergyDay
 */
@Serializable
data class EnergyDay(
    val date: String,
    val boilerHours: Double,
    val gasKwh: Double,
    val gasCost: Double,
    val electricityKwh: Double?,
    val electricityCost: Double?,
    val standingCost: Double,
    val totalCost: Double,
    val missingMinutes: Int,
    val incomplete: Boolean,
    /** Electricity minus the daily target (for today, the share of it gone so far); null with no target or house meter. */
    val targetDeltaKwh: Double? = null,
)

/**
 * The house's electricity against the daily target over the period. [expectedKwh] is the target for each day, today's
 * only for the share of today gone; [deltaKwh] is [usedKwh] minus it, below zero under target. [projectedKwh] is where
 * use is heading by the end of the period at the rate so far; null in its first 30 minutes. [incomplete] when readings
 * are missing: [usedKwh] holds only what was recorded.
 *
 * openapi: EnergyTarget
 */
@Serializable
data class EnergyTarget(
    val kwh: Double,
    val usedKwh: Double,
    val expectedKwh: Double,
    val deltaKwh: Double,
    val deltaCost: Double,
    val onTarget: Boolean,
    val projectedKwh: Double?,
    val incomplete: Boolean,
)

/**
 * A monitored device's use and cost over the period. Its use is already in the house meter's
 * electricityKwh, so it is "of which", never added to the total cost. [missingMinutes] is time the plug
 * was stale (no reading): unknown, so [kwh] and [cost] are only so far and the device is [incomplete].
 * [room] is the id of the room it is in now, null when in no room. [offlineSince] is set while the plug is
 * offline now, which also makes it incomplete. [totalStuckSince] is set while it is using power but its energy
 * total is not going up; it is then incomplete too.
 *
 * openapi: DeviceEnergy
 */
@Serializable
data class DeviceEnergy(
    val meter: String,
    val kwh: Double,
    val cost: Double,
    val missingMinutes: Int = 0,
    val incomplete: Boolean = false,
    val room: String? = null,
    val offlineSince: String? = null,
    val totalStuckSince: String? = null,
)

/**
 * A room's use and cost over the period: the sum of the monitored plugs in it now. Like a device, it is
 * "of which" the house meter's electricityKwh, never added to the total cost. [incomplete] when any of its
 * plugs is; [missingMinutes] is the time any of them had no reading, counted once however many plugs.
 *
 * openapi: RoomEnergy
 */
@Serializable
data class RoomEnergy(
    val room: String,
    val name: String,
    val kwh: Double,
    val cost: Double,
    val missingMinutes: Int = 0,
    val incomplete: Boolean = false,
)

/** A plug's use in an energy area over the period: only what it used while in the area. openapi: AreaPlugEnergy */
@Serializable
data class AreaPlugEnergy(val meter: String, val kwh: Double, val cost: Double)

/**
 * An energy area's use and cost over the period. While its whole-area meter (a clamp) recorded, the area is the clamp's
 * figure and its plugs are part of it, so [rest] is what the clamp measured beyond its plugs (never below 0); with no
 * clamp the area is the sum of its plugs and [rest] is null. Like a room it is "of which" the house's, never added to
 * the total cost; standing charges are never split onto areas.
 *
 * openapi: AreaEnergy
 */
@Serializable
data class AreaEnergy(
    val area: String,
    val name: String,
    val kwh: Double,
    val cost: Double,
    val missingMinutes: Int = 0,
    val incomplete: Boolean = false,
    val wholeMeter: String? = null,
    val plugs: List<AreaPlugEnergy> = emptyList(),
    val rest: Double? = null,
    val restCost: Double? = null,
    /** Names of the areas it shares a circuit with: some of its use is also counted in them. */
    val overlaps: List<String> = emptyList(),
)

/**
 * The main meter's use that no monitored plug or energy area accounts for: house electricity minus each whole-area
 * meter, and minus each monitored plug not behind one (in a room, an area with no clamp, or none), never below 0. Like a room it is "of which", never added to the total cost.
 * [incomplete] when the main meter or any monitored plug is; [missingMinutes] is the longer of the main
 * meter's time with no reading and the time any plug had none.
 *
 * openapi: EverythingElse
 */
@Serializable
data class EverythingElse(
    val kwh: Double,
    val cost: Double,
    val missingMinutes: Int = 0,
    val incomplete: Boolean = false,
    /** Pairs of area names that share a circuit: it reads low by whatever both measure. */
    val sharedCircuits: List<List<String>> = emptyList(),
)

/**
 * Use and cost over a [period], with standing charges on their own lines and a day per entry in [days]
 * for charts. Gas is an estimate: boiler hours (heating or cycling) times the boiler's average kW.
 *
 * openapi: EnergyDashboard
 */
@Serializable
data class EnergyDashboard(
    val period: String,
    val from: String,
    val to: String,
    val boilerHours: Double,
    val gasKwh: Double,
    val gasCost: Double,
    val electricityMeter: String?,
    val electricityKwh: Double?,
    val electricityCost: Double?,
    val gasStandingCost: Double,
    val electricityStandingCost: Double?,
    val standingCost: Double,
    val totalCost: Double,
    val missingMinutes: Int,
    val incomplete: Boolean,
    val days: List<EnergyDay>,
    /** ISO 4217 code the costs are in. */
    val currency: String,
    /** Monitored devices, part of [electricityKwh] and [totalCost] already. */
    val devices: List<DeviceEnergy> = emptyList(),
    /** Rooms with monitored plugs, by name. Part of [devices], so never added to anything. */
    val rooms: List<RoomEnergy> = emptyList(),
    /** House electricity no monitored plug or area accounts for; null while no house meter is chosen. */
    val everythingElse: EverythingElse? = null,
    /** Energy areas, by name. "Of which" the house's, like rooms, so never added to anything. */
    val areas: List<AreaEnergy> = emptyList(),
    /** The house's electricity against the daily target; null with no target or no house meter. */
    val target: EnergyTarget? = null,
)

/**
 * How energy areas count at one five-minute sample time: a plug is behind a clamp when the clamp of the area it was in
 * was recorded at the same time (whether or not it had a reading). Shared by the dashboard, usage over time and live power.
 */
object AreaCounting {
    /** The (area, time) pairs a whole-area meter was recorded for. */
    fun clamped(areaSamples: List<MeterSample>): Set<Pair<String, Instant>> = areaSamples.map { it.room!! to it.at }.toSet()

    /** Whether plug sample [plug] is part of a clamp's figure. */
    fun behindClamp(plug: MeterSample, clamped: Set<Pair<String, Instant>>): Boolean =
        plug.area != null && (plug.area to plug.at) in clamped
}

/**
 * Works the dashboard out when it is read, from recorded boiler states and meter kWh and the current
 * [EnergySettings]. Nothing about cost is stored, so changing a price recalculates the past.
 */
class EnergyDashboards(
    private val history: HistoryRepository,
    private val energy: EnergyHistory,
    private val settings: EnergySettingsRepository,
    private val monitored: MonitoredMeters,
    private val meters: MeterRegistry,
    private val rooms: () -> List<Room>,
    private val now: () -> ZonedDateTime,
    private val areas: () -> List<EnergyArea> = { emptyList() },
) {
    fun of(period: DashboardPeriod): EnergyDashboard {
        val clock = now()
        val firstDay = period.firstDay(clock)
        // Everything below runs up to the period's end, which is now unless it is a past day.
        val now = period.end(clock)
        val prices = settings.get()
        val from = firstDay.atStartOfDay(now.zone)
        val boiler = history.boilerStates(from.toInstant(), now.toInstant())
        val samples = energy.samples(from.toInstant().minus(GAP), now.toInstant())

        val days = (0 until period.days).map { i ->
            val date = firstDay.plusDays(i)
            val start = date.atStartOfDay(now.zone).toInstant()
            val end = minOf(date.plusDays(1).atStartOfDay(now.zone).toInstant(), now.toInstant())
            day(date, start, end, now.toInstant(), boiler, samples, prices, date.plusDays(1).atStartOfDay(now.zone).toInstant())
        }
        val boilerHours = days.sumOf { it.hours }
        val electricityKwh = if (prices.electricityMeter == null) null else days.sumOf { it.kwh ?: 0.0 }
        val gasKwh = boilerHours * prices.boilerAverageKw
        val gasCost = gasKwh * prices.gasPencePerKwh
        val electricityCost = electricityKwh?.let { it * prices.electricityPencePerKwh }
        val gasStanding = period.days * prices.gasStandingPencePerDay
        val electricityStanding = prices.electricityMeter?.let { period.days * prices.electricityStandingPencePerDay }
        val standing = gasStanding + (electricityStanding ?: 0.0)
        val missing = days.sumOf { it.view.missingMinutes }
        val devices = monitored.all().map { meter ->
            // A sample covers the five minutes before it, so one at midnight is yesterday's.
            val samples = energy.deviceSamples(meter, from.toInstant(), now.toInstant()).filter { it.at > from.toInstant() }
            Device(meter, samples.sumOf { it.kwh ?: 0.0 }, samples.filter { it.kwh == null }.map { it.at }.toSet(), samples)
        }
        // Use counts in the room the plug was in when it was recorded, not the room it is in now.
        val byRoom = devices.flatMap { it.samples }.filter { it.room != null }.groupBy { it.room!! }
        // A sample covers the five minutes before it, so one at midnight is yesterday's.
        val areaSamples = energy.allAreaSamples(from.toInstant(), now.toInstant())
        val areaList = areas().sortedBy { it.name.lowercase() }
        val clamped = AreaCounting.clamped(areaSamples)
        val plugSamples = devices.flatMap { it.samples }
        // Everything else takes off each clamp's figure, and each plug not behind one.
        val accounted = areaSamples.sumOf { it.kwh ?: 0.0 } +
            plugSamples.filterNot { AreaCounting.behindClamp(it, clamped) }.sumOf { it.kwh ?: 0.0 }
        val elseStale = areaSamples.filter { it.kwh == null }.map { it.at } + devices.flatMap { it.staleAt }
        return EnergyDashboard(
            period = period.name.lowercase(),
            from = from.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            to = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            boilerHours = round(boilerHours, 2),
            gasKwh = round(gasKwh, 2),
            gasCost = round(gasCost, 1),
            electricityMeter = prices.electricityMeter,
            electricityKwh = electricityKwh?.let { round(it, 2) },
            electricityCost = electricityCost?.let { round(it, 1) },
            gasStandingCost = round(gasStanding, 1),
            electricityStandingCost = electricityStanding?.let { round(it, 1) },
            standingCost = round(standing, 1),
            totalCost = round(gasCost + (electricityCost ?: 0.0) + standing, 1),
            missingMinutes = missing,
            incomplete = days.any { it.view.incomplete },
            days = days.map { it.view },
            currency = prices.currency,
            devices = devices.map { d ->
                val missing = staleMinutes(d.staleAt)
                val offlineSince = meters[d.meter]?.let(meters::offlineSince)
                val totalStuckSince = meters[d.meter]?.let(meters::totalStuckSince)
                DeviceEnergy(
                    d.meter, round(d.kwh, 2), round(d.kwh * prices.electricityPencePerKwh, 1), missing,
                    missing > 0 || offlineSince != null || totalStuckSince != null, monitored.roomOf(d.meter),
                    offlineSince?.atZone(now.zone)?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    totalStuckSince?.atZone(now.zone)?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                )
            },
            rooms = rooms().filter { it.id in byRoom }.sortedBy { it.name }.map { room ->
                val samples = byRoom.getValue(room.id)
                val kwh = samples.sumOf { it.kwh ?: 0.0 }
                // Plugs are sampled together, so time two plugs were stale at once counts once.
                val missing = staleMinutes(samples.filter { it.kwh == null }.map { it.at }.toSet())
                RoomEnergy(room.id, room.name, round(kwh, 2), round(kwh * prices.electricityPencePerKwh, 1), missing, missing > 0)
            },
            everythingElse = electricityKwh?.let { house ->
                val kwh = (house - accounted).coerceAtLeast(0.0)
                val missing = maxOf(missing, staleMinutes(elseStale.toSet()))
                EverythingElse(round(kwh, 2), round(kwh * prices.electricityPencePerKwh, 1), missing, missing > 0, sharedCircuits(areaList))
            },
            areas = areaList.map { area ->
                area(area, areaSamples.filter { it.room == area.id }, plugSamples.filter { it.area == area.id }, clamped, prices)
                    .copy(overlaps = overlapNames(area, areaList))
            },
            target = target(days, prices, from.toInstant(), now.toInstant(), period),
        )
    }

    /** The period against the daily target, or null with no target or no house meter. */
    private fun target(days: List<Day>, prices: EnergySettings, from: Instant, now: Instant, period: DashboardPeriod): EnergyTarget? {
        val daily = prices.dailyKwhTarget ?: return null
        if (prices.electricityMeter == null) return null
        val used = days.sumOf { it.kwh ?: 0.0 }
        val expected = days.sumOf { it.share * daily }
        val total = period.days * daily
        val delta = used - expected
        val gone = days.sumOf { it.share }
        val projected = if (Duration.between(from, now) < Duration.ofMinutes(30) || gone <= 0.0) null else used / gone * period.days
        return EnergyTarget(
            kwh = round(total, 2),
            usedKwh = round(used, 3),
            expectedKwh = round(expected, 3),
            deltaKwh = round(delta, 3),
            deltaCost = round(delta * prices.electricityPencePerKwh, 1),
            onTarget = delta <= 0.0,
            projectedKwh = projected?.let { round(it, 2) },
            incomplete = days.any { it.view.incomplete },
        )
    }

    /**
     * An area's figure: at each sample time, its clamp's if the clamp was recorded then, else the sum of its plugs. Time
     * with no reading counts once however many meters had none: the clamp's while clamped, else any plug's.
     */
    private fun area(area: EnergyArea, clamp: List<MeterSample>, plugs: List<MeterSample>, clamped: Set<Pair<String, Instant>>, prices: EnergySettings): AreaEnergy {
        val (behind, unclamped) = plugs.partition { AreaCounting.behindClamp(it, clamped) }
        val kwh = clamp.sumOf { it.kwh ?: 0.0 } + unclamped.sumOf { it.kwh ?: 0.0 }
        val staleAt = clamp.filter { it.kwh == null }.map { it.at } + unclamped.filter { it.kwh == null }.map { it.at }
        val missing = staleMinutes(staleAt.toSet())
        val price = prices.electricityPencePerKwh
        val rest = if (clamp.isEmpty()) null else (clamp.sumOf { it.kwh ?: 0.0 } - behind.sumOf { it.kwh ?: 0.0 }).coerceAtLeast(0.0)
        return AreaEnergy(
            area = area.id,
            name = area.name,
            kwh = round(kwh, 2),
            cost = round(kwh * price, 1),
            missingMinutes = missing,
            incomplete = missing > 0,
            wholeMeter = area.wholeMeter,
            plugs = plugs.groupBy { it.meter!! }.toSortedMap().map { (meter, samples) ->
                val used = samples.sumOf { it.kwh ?: 0.0 }
                AreaPlugEnergy(meter, round(used, 2), round(used * price, 1))
            },
            rest = rest?.let { round(it, 2) },
            restCost = rest?.let { round(it * price, 1) },
        )
    }

    /** A monitored device's unrounded kWh, its stale samples' times and the samples, which rooms add up by the room on each. */
    private class Device(val meter: String, val kwh: Double, val staleAt: Set<Instant>, val samples: List<MeterSample>)

    /** Each stale sample is five minutes with no reading. */
    private fun staleMinutes(staleAt: Set<Instant>) = staleAt.size * MeterRecorder.SAMPLE_INTERVAL.toMinutes().toInt()

    /** A day's figures, with the unrounded hours and kWh the period totals add up. */
    private class Day(val view: EnergyDay, val hours: Double, val kwh: Double?, val share: Double)

    private fun day(
        date: LocalDate,
        start: Instant,
        end: Instant,
        now: Instant,
        boiler: List<heating.history.BoilerPeriod>,
        samples: List<MeterSample>,
        prices: EnergySettings,
        /** The midnight the day ends at, even when [end] is now. */
        dayEnd: Instant,
    ): Day {
        val hours = boiler.filter { it.state in BURNING }.sumOf { period ->
            overlap(period.from, period.to ?: now, start, end).toMillis()
        } / 3_600_000.0
        val kwh = if (prices.electricityMeter == null) null
        else samples.filter { it.at > start && it.at <= end }.sumOf { it.kwh ?: 0.0 }
        val missing = missingMinutes(start, end, now, samples)
        val gasCost = hours * prices.boilerAverageKw * prices.gasPencePerKwh
        val electricityCost = kwh?.let { it * prices.electricityPencePerKwh }
        // The share of the day gone: 1 for a past day, the hours so far over the day's length (23 or 25 on a clock change) for today.
        val length = Duration.between(start, dayEnd)
        val share = (Duration.between(start, end).toMillis().toDouble() / length.toMillis()).coerceIn(0.0, 1.0)
        val targetDelta = if (kwh == null) null else prices.dailyKwhTarget?.let { kwh - it * share }
        val standing = prices.gasStandingPencePerDay + (if (prices.electricityMeter == null) 0.0 else prices.electricityStandingPencePerDay)
        return Day(
            EnergyDay(
                date = date.toString(),
                boilerHours = round(hours, 2),
                gasKwh = round(hours * prices.boilerAverageKw, 2),
                gasCost = round(gasCost, 1),
                electricityKwh = kwh?.let { round(it, 2) },
                electricityCost = electricityCost?.let { round(it, 1) },
                standingCost = round(standing, 1),
                totalCost = round(gasCost + (electricityCost ?: 0.0) + standing, 1),
                missingMinutes = missing,
                incomplete = missing > 0,
                targetDeltaKwh = targetDelta?.let { round(it, 3) },
            ),
            hours,
            kwh,
            share,
        )
    }

    /**
     * Minutes of [start]..[end] with no good sample. Each sample vouches for the time since the
     * previous one (if that was less than [GAP] before, else just its own five minutes), unless the
     * house meter had no reading then. The time since the last sample up to [now] counts as covered.
     */
    private fun missingMinutes(start: Instant, end: Instant, now: Instant, samples: List<MeterSample>): Int {
        if (!end.isAfter(start)) return 0
        var covered = Duration.ZERO
        var previous: Instant? = null
        val points = samples.map { it.at to (it.meter == null || it.kwh != null) } + (now to true)
        for ((at, good) in points) {
            val since = previous?.takeIf { Duration.between(it, at) < GAP } ?: at.minus(MeterRecorder.SAMPLE_INTERVAL)
            if (good) covered += overlap(since, at, start, end)
            previous = at
        }
        val missing = Duration.between(start, end).minus(covered)
        return missing.toMinutes().toInt().coerceAtLeast(0)
    }

    private fun overlap(from: Instant, to: Instant, start: Instant, end: Instant): Duration {
        val a = maxOf(from, start)
        val b = minOf(to, end)
        return if (b.isAfter(a)) Duration.between(a, b) else Duration.ZERO
    }

    private fun round(v: Double, places: Int) = BigDecimal.valueOf(v).setScale(places, RoundingMode.HALF_UP).toDouble()

    companion object {
        /** Boiler states that burn gas. */
        private val BURNING = setOf("heating", "cycling")

        /** Samples further apart than this mean the service was down in between. */
        val GAP: Duration = Duration.ofMinutes(15)
    }
}
