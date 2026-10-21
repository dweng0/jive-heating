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

/**
 * The main meter's use that no monitored plug accounts for: house electricity minus all monitored plugs,
 * in rooms or not, never below 0. Like a room it is "of which", never added to the total cost.
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
    /** House electricity no monitored plug accounts for; null while no house meter is chosen. */
    val everythingElse: EverythingElse? = null,
)

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
            day(date, start, end, now.toInstant(), boiler, samples, prices)
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
                val kwh = (house - devices.sumOf { it.kwh }).coerceAtLeast(0.0)
                val missing = maxOf(missing, staleMinutes(devices.flatMap { it.staleAt }.toSet()))
                EverythingElse(round(kwh, 2), round(kwh * prices.electricityPencePerKwh, 1), missing, missing > 0)
            },
        )
    }

    /** A monitored device's unrounded kWh, its stale samples' times and the samples, which rooms add up by the room on each. */
    private class Device(val meter: String, val kwh: Double, val staleAt: Set<Instant>, val samples: List<MeterSample>)

    /** Each stale sample is five minutes with no reading. */
    private fun staleMinutes(staleAt: Set<Instant>) = staleAt.size * MeterRecorder.SAMPLE_INTERVAL.toMinutes().toInt()

    /** A day's figures, with the unrounded hours and kWh the period totals add up. */
    private class Day(val view: EnergyDay, val hours: Double, val kwh: Double?)

    private fun day(
        date: LocalDate,
        start: Instant,
        end: Instant,
        now: Instant,
        boiler: List<heating.history.BoilerPeriod>,
        samples: List<MeterSample>,
        prices: EnergySettings,
    ): Day {
        val hours = boiler.filter { it.state in BURNING }.sumOf { period ->
            overlap(period.from, period.to ?: now, start, end).toMillis()
        } / 3_600_000.0
        val kwh = if (prices.electricityMeter == null) null
        else samples.filter { it.at > start && it.at <= end }.sumOf { it.kwh ?: 0.0 }
        val missing = missingMinutes(start, end, now, samples)
        val gasCost = hours * prices.boilerAverageKw * prices.gasPencePerKwh
        val electricityCost = kwh?.let { it * prices.electricityPencePerKwh }
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
            ),
            hours,
            kwh,
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
