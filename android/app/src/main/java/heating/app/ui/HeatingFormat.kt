package heating.app.ui

import heating.app.data.Block
import heating.app.data.Mode
import heating.app.data.Zone
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

const val MIN_TARGET = 5.0
const val MAX_TARGET = 30.0

/** Dial arc: starts at 135° (bottom left), sweeps 270° clockwise to 45° (bottom right). */
const val DIAL_START = 135f
const val DIAL_SWEEP = 270f

val BOOST_MINUTES = listOf(30, 60, 90, 120, 150, 180)

/** "Version 0.7.0", shown on the account screen so you can tell whether an update reached the phone. */
/** The rooms screen with no rooms: an admin adds them; a member waits for an admin to give them some. */
fun noRoomsText(admin: Boolean): String =
    if (admin) "No rooms yet. Add a room, then give it its valves." else "No rooms yet. Ask an admin to give you access."

fun appVersionLabel(versionName: String = heating.app.BuildConfig.VERSION_NAME) = "Version $versionName"

fun formatTemp(t: Double?): String = when {
    t == null -> "–"
    t % 1.0 == 0.0 -> "${t.toInt()}°"
    else -> "${String.format(Locale.ROOT, "%.1f", t)}°"
}

/** Where a room or group boost starts: 2 above its [target], never below the household's [defaultTarget]. */
fun boostStart(defaultTarget: Double, target: Double?): Double = maxOf(defaultTarget, (target ?: 20.0) + 2)

/** The Account page's line for the household's boost temperature, such as "Default boost temperature 22.0°C". */
fun boostSettingLine(target: Double): String = "Default boost temperature ${String.format(Locale.ROOT, "%.1f", target)}°C"

/** Round to 0.5, move [steps] half-degrees, clamp to the allowed range. */
fun stepTarget(current: Double, steps: Int): Double {
    val rounded = (current * 2).roundToInt() / 2.0
    return (rounded + steps * 0.5).coerceIn(MIN_TARGET, MAX_TARGET)
}

fun dialFraction(target: Double?): Float =
    if (target == null) 0f else ((target - MIN_TARGET) / (MAX_TARGET - MIN_TARGET)).toFloat().coerceIn(0f, 1f)

/** Target for a touch at [angle] degrees (0° = 3 o'clock, clockwise), snapped to 0.5. */
fun targetForAngle(angle: Float): Double {
    val a = ((angle % 360f) + 360f) % 360f
    val along = ((a - DIAL_START) + 360f) % 360f // degrees into the sweep
    val fraction = if (along <= DIAL_SWEEP) {
        along / DIAL_SWEEP
    } else {
        // In the 90° gap at the bottom: nearer end wins.
        if (along - DIAL_SWEEP < (360f - along)) 1f else 0f
    }
    return stepTarget(MIN_TARGET + fraction * (MAX_TARGET - MIN_TARGET), 0)
}

data class Segment(val fraction: Float, val target: Double)

/** One segment per block, sized by how much of the 24 h day it covers. */
fun scheduleSegments(blocks: List<Block>): List<Segment> = blocks.mapIndexed { i, block ->
    val start = minutes(block.start)
    val end = blocks.getOrNull(i + 1)?.let { minutes(it.start) } ?: (24 * 60)
    Segment((end - start) / (24f * 60f), block.target)
}

fun minutes(hhmm: String): Int = hhmm.split(":").let { (h, m) -> h.toInt() * 60 + m.toInt() }

private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

/** "22:30" from an ISO-8601 timestamp, in the timestamp's own offset (the service's time zone). */
fun clockTime(iso: String): String = OffsetDateTime.parse(iso).format(HHMM)

fun boostLabel(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes == 60 -> "1 hour"
    minutes % 60 == 0 -> "${minutes / 60} hours"
    else -> "${minutes / 60.0} hours"
}

/** One line for a room tile or under the dial. */
fun zoneSummary(zone: Zone): String {
    val till = zone.nextChange?.let { " till ${clockTime(it.at)}" } ?: ""
    return when {
        zone.boost != null -> "Boost ${formatTemp(zone.boost.target)} · until ${clockTime(zone.boost.endsAt)}"
        zone.override != null -> "Held at ${formatTemp(zone.override.target)} · until ${clockTime(zone.override.until)}"
        zone.mode == Mode.OFF -> "Off · frost protected"
        zone.mode == Mode.MANUAL -> "Manual · ${formatTemp(zone.target)}"
        zone.heating -> "Heating to ${formatTemp(zone.target)} ·$till".trimEnd(' ', '·')
        else -> "Schedule · ${formatTemp(zone.target)}$till"
    }
}
