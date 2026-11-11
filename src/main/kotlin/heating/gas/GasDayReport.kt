package heating.gas

import heating.ApiException
import heating.energy.EnergySettingsRepository
import heating.history.HistoryRepository
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One half-hour of the smart gas meter, by its start. openapi: GasHalfHour */
@Serializable
data class GasHalfHour(val at: String, val kwh: Double)

/**
 * A day of the smart gas meter, half-hour by half-hour, with its [cost] at the gas price (no standing charge), the
 * nearest days either side with readings, and the boiler's average burn rate as the meter measured it over the 7 days
 * up to the latest whole day ([measuredBoilerKw], null with under 5 boiler hours), beside the [boilerAverageKw] setting
 * the estimate uses.
 *
 * openapi: GasDay
 */
@Serializable
data class GasDay(
    val date: String,
    val kwh: Double,
    val cost: Double,
    val currency: String,
    val halfHours: List<GasHalfHour>,
    val previous: String?,
    val next: String?,
    val measuredBoilerKw: Double?,
    val boilerAverageKw: Double,
)

/** Works out a [GasDay] from the kept half-hours, boiler states and current prices. */
class GasDayReport(
    private val readings: GasReadings,
    private val history: HistoryRepository,
    private val settings: EnergySettingsRepository,
    private val zone: ZoneId,
) {
    /** The day [date], or the latest whole day the meter reported; 404 when that day has no readings. */
    fun of(date: LocalDate?): GasDay {
        val latest = latestWholeDay()
        val day = date ?: latest ?: throw ApiException.notFound("no gas meter readings")
        val start = day.atStartOfDay(zone).toInstant()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant()
        val slots = readings.between(start, end)
        if (slots.isEmpty()) throw ApiException.notFound("no gas meter readings on $day")
        val prices = settings.get()
        val kwh = slots.values.sum()
        return GasDay(
            date = day.toString(),
            kwh = round(kwh, 3),
            cost = round(kwh * prices.gasPencePerKwh, 2),
            currency = prices.currency,
            halfHours = slots.toSortedMap().map { (at, used) -> GasHalfHour(FORMAT.format(at), round(used, 3)) },
            previous = readings.lastBefore(start)?.atZone(zone)?.toLocalDate()?.toString(),
            next = readings.firstFrom(end)?.atZone(zone)?.toLocalDate()?.toString(),
            measuredBoilerKw = latest?.let(::measuredKw),
            boilerAverageKw = prices.boilerAverageKw,
        )
    }

    /** The boiler's burn rate the meter measured over the 7 days up to its latest whole day; null with under 5 boiler hours or no meter. */
    fun measuredBoilerKw(): Double? = latestWholeDay()?.let(::measuredKw)

    /** The last day the meter reported all of, as the odometer's gas counter shows it. */
    private fun latestWholeDay(): LocalDate? {
        val meteredTo = readings.meteredTo() ?: return null
        val day = meteredTo.atZone(zone).toLocalDate().minusDays(1)
        return day.takeIf { readings.between(it.atStartOfDay(zone).toInstant(), it.atStartOfDay(zone).toInstant().plus(GasReadings.HALF_HOUR)).isNotEmpty() }
    }

    /** The meter's kWh over the boiler's burning hours on the 7 days up to [latest], from when the meter began. */
    private fun measuredKw(latest: LocalDate): Double? {
        val end = latest.plusDays(1).atStartOfDay(zone).toInstant()
        val first = readings.firstFrom(latest.minusDays(6).atStartOfDay(zone).toInstant()) ?: return null
        val kwh = readings.between(first, end).values.sum()
        val hours = history.boilerStates(first, end).filter { it.state in BURNING }.sumOf { period ->
            val a = maxOf(period.from, first)
            val b = minOf(period.to ?: end, end)
            if (b.isAfter(a)) Duration.between(a, b).toMillis() else 0L
        } / 3_600_000.0
        return if (hours < MIN_HOURS) null else round(kwh / hours, 1)
    }

    private fun round(v: Double, places: Int) = BigDecimal.valueOf(v).setScale(places, RoundingMode.HALF_UP).toDouble()

    private companion object {
        val BURNING = setOf("heating", "cycling")
        const val MIN_HOURS = 5.0
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
    }
}
