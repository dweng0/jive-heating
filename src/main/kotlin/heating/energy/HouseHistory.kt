package heating.energy

import heating.ApiException
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * How much of the house's history the chart shows, and how finely: five minutes over a day, an hour over a week, a day
 * over a month and a month over a year. Points line up with the calendar in the household's timezone.
 */
enum class HistoryChartRange(val points: Int) {
    DAY(288), WEEK(168), MONTH(30), YEAR(12);

    /** The start of the point [at] counts in. A sample covers the five minutes before it, so one at 13:00 is in 12:00's hour. */
    fun pointOf(at: ZonedDateTime): ZonedDateTime {
        val covered = at.minusNanos(1)
        return when (this) {
            DAY -> covered.truncatedTo(ChronoUnit.MINUTES).let { it.withMinute(it.minute / 5 * 5) }
            WEEK -> covered.truncatedTo(ChronoUnit.HOURS)
            MONTH -> covered.truncatedTo(ChronoUnit.DAYS)
            YEAR -> covered.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1)
        }
    }

    fun next(point: ZonedDateTime): ZonedDateTime = when (this) {
        DAY -> point.plusMinutes(5)
        WEEK -> point.plusHours(1)
        MONTH -> point.plusDays(1)
        YEAR -> point.plusMonths(1)
    }

    fun previous(point: ZonedDateTime): ZonedDateTime = when (this) {
        DAY -> point.minusMinutes(5)
        WEEK -> point.minusHours(1)
        MONTH -> point.minusDays(1)
        YEAR -> point.minusMonths(1)
    }

    companion object {
        fun of(name: String?) = when (name ?: "day") {
            "day" -> DAY
            "week" -> WEEK
            "month" -> MONTH
            "year" -> YEAR
            else -> throw ApiException.badRequest("range must be day, week, month or year")
        }
    }
}

/**
 * One point of the house's history: use, cost at today's price and average power over its five minutes, hour, day or
 * month from [at]. [incomplete] when samples are missing or had no reading: the figures are then only what was recorded,
 * and null when nothing was. [targetKwh] is the daily target times the point's days, for a day or a month only.
 *
 * openapi: HouseHistoryPoint
 */
@Serializable
data class HouseHistoryPoint(val at: String, val kwh: Double?, val cost: Double?, val watts: Double?, val incomplete: Boolean, val targetKwh: Double?)

/**
 * The house meter's use for the history chart, from [from] to [to]. [earliest] is the first house sample, so the chart
 * knows how far back it can be dragged.
 *
 * openapi: HouseHistory
 */
@Serializable
data class HouseHistory(
    val currency: String,
    val range: String,
    val from: String,
    val to: String,
    val earliest: String?,
    val points: List<HouseHistoryPoint>,
)

/** Works out [HouseHistory] from the house meter's five-minute samples. */
class HouseHistoryReport(
    private val energy: EnergyHistory,
    private val settings: EnergySettingsRepository,
    private val zone: ZoneId,
    private val clock: Clock,
) {
    fun of(range: HistoryChartRange, to: Instant?): HouseHistory {
        val now = clock.instant()
        val end = if (to == null || to.isAfter(now)) now else to
        val last = range.pointOf(end.atZone(zone))
        val first = generateSequence(last) { range.previous(it) }.drop(range.points - 1).first()
        val prices = settings.get()
        val samples = energy.samples(first.toInstant(), end).filter { it.at > first.toInstant() }
            .groupBy { range.pointOf(it.at.atZone(zone)) }
        val earliest = energy.earliest()
        val points = generateSequence(first) { range.next(it) }.take(range.points).map { start ->
            val inPoint = samples[start].orEmpty()
            val kwh = inPoint.mapNotNull { it.kwh }.takeIf { it.isNotEmpty() }?.sum()
            val watts = inPoint.mapNotNull { it.watts }.takeIf { it.isNotEmpty() }?.average()
            HouseHistoryPoint(
                at = format(start),
                kwh = kwh?.let { round(it, 4) },
                cost = kwh?.let { round(it * prices.electricityPencePerKwh, 2) },
                watts = watts?.let { round(it, 1) },
                incomplete = incomplete(start, range.next(start), inPoint, earliest, end),
                targetKwh = prices.dailyKwhTarget?.let { target ->
                    when (range) {
                        HistoryChartRange.MONTH -> target
                        HistoryChartRange.YEAR -> target * start.toLocalDate().lengthOfMonth()
                        else -> null
                    }
                },
            )
        }.toList()
        return HouseHistory(prices.currency, range.name.lowercase(), format(first), end.atZone(zone).format(FORMAT), earliest?.let { it.atZone(zone).format(FORMAT) }, points)
    }

    /**
     * Whether a point is missing samples, or has one with no reading. Only the part of it after the first sample and
     * up to [end] is expected to have samples; one sample short is allowed, as samples need not line up with the clock.
     */
    private fun incomplete(start: ZonedDateTime, next: ZonedDateTime, samples: List<MeterSample>, earliest: Instant?, end: Instant): Boolean {
        if (samples.any { it.kwh == null }) return true
        val from = maxOf(start.toInstant(), earliest ?: return false)
        val until = minOf(next.toInstant(), end)
        if (!until.isAfter(from)) return false
        val expected = Duration.between(from, until).toMinutes() / 5
        return samples.size < expected - 1
    }

    private fun format(at: ZonedDateTime) = at.format(FORMAT)

    private fun round(v: Double, places: Int) = BigDecimal.valueOf(v).setScale(places, RoundingMode.HALF_UP).toDouble()

    private companion object {
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }
}
