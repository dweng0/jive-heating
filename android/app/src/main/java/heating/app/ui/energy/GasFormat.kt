package heating.app.ui.energy

import heating.app.data.GasDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "Thu 5 Nov" for a gas day. */
fun gasDayTitle(date: String): String = LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))

/** "53.5 kWh · £3.69 · busiest 18:30, 6.5 kWh": the day's use, its cost and its busiest half-hour (none on a day with no use). */
fun gasDayFigures(day: GasDay, zone: ZoneId = ZoneId.systemDefault()): String {
    val top = day.halfHours.maxByOrNull { it.kwh }?.takeIf { it.kwh > 0 }
    val busiest = top?.let {
        val time = Instant.parse(it.at).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", Locale.UK))
        "busiest $time, ${oneDecimal(it.kwh)} kWh"
    }
    return listOfNotNull(formatKwh(day.kwh), formatCost(day.cost, day.currency), busiest).joinToString(" · ")
}

/** Each half-hour as a share of the busiest one, for the bars. */
fun gasBars(day: GasDay): List<Float> {
    val max = day.halfHours.maxOfOrNull { it.kwh }?.coerceAtLeast(0.5) ?: 0.5
    return day.halfHours.map { (it.kwh / max).toFloat() }
}

/** "Boiler average from the meter: 12.1 kW, used for estimates", or null when the meter could not measure it. */
fun boilerMeasuredLine(day: GasDay): String? = day.measuredBoilerKw?.let { "Boiler average from the meter: ${oneDecimal(it)} kW, used for estimates" }
