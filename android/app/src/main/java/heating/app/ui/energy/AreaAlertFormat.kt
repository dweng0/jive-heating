package heating.app.ui.energy

import heating.app.data.AreaAlert
import kotlin.math.roundToInt

/** "2 h", "1 h 30 min", "30 min". */
fun alertHours(hours: Double): String {
    val minutes = (hours * 60).roundToInt()
    val h = minutes / 60
    val m = minutes % 60
    return listOfNotNull(if (h > 0) "$h h" else null, if (m > 0) "$m min" else null).joinToString(" ")
}

/** "Notifies you after 2 h", "Notifies everyone after 2 h", or "Notifies jane after 2 h" when another admin set it up; null with none. */
fun alertLine(alert: AreaAlert?): String? {
    val hours = alert?.hours ?: return null
    val who = when {
        alert.notify == "everyone" -> "everyone"
        alert.mine || alert.by == null -> "you"
        else -> alert.by
    }
    return "Notifies $who after ${alertHours(hours)}"
}

/** The hours typed in the dialog, when they are from 0.5 to 24 in half hours; null otherwise. */
fun alertHoursOrNull(text: String): Double? {
    val hours = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
    return hours.takeIf { it in 0.5..24.0 && (it * 2) % 1.0 == 0.0 }
}
