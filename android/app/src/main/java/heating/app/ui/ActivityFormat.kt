package heating.app.ui

import heating.app.data.ActivityEvent
import kotlinx.serialization.json.doubleOrNull
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "Utility Room" becomes "Utility": phones drop the generic word and let the line ellipsize. */
fun shortRoom(name: String): String = name.replace(Regex("\\s+room$", RegexOption.IGNORE_CASE), "").ifBlank { name }

/**
 * An activity event in words: a [brief] few-word line for a phone, or the full sentence for a wide
 * screen and for TalkBack, which always reads the full one.
 */
fun activityText(e: ActivityEvent, brief: Boolean): String {
    val who = e.actor
    val room = e.room.orEmpty()
    val short = shortRoom(room)
    val house = room == "Whole house"
    val number = e.value?.takeIf { !it.isString }?.doubleOrNull
    val target = number?.let { formatTemp(it) }.orEmpty()
    val state = e.value?.takeIf { it.isString }?.content
    fun pick(wide: String, narrow: String) = if (brief) narrow else wide
    return when (e.kind) {
        "setpoint" ->
            if (house) pick("$who set the whole house to $target", "$who · House $target")
            else pick("$who turned the $room thermostat to $target", "$who · $short $target")
        "mode" -> {
            val name = if (house) "the whole house" else room
            val wording = when (state) { "schedule" -> "Auto"; else -> state.orEmpty() }
            pick(
                if (state == "off") "$who turned $name off" else "$who set $name to $wording",
                "$who · ${if (house) "House" else short} ${wording.lowercase()}",
            )
        }
        "boost" ->
            if (number != null) pick("$who boosted $room to $target", "$who · $short boost $target")
            else if (who != null) pick("$who cancelled the boost in $room", "$who · $short boost off")
            else pick("The boost in $room ended", "$short boost ended")
        "holiday" -> {
            val start = state == "start"
            if (who == null) pick(if (start) "Holiday started" else "Holiday ended", if (start) "Holiday on" else "Holiday over")
            else pick(if (start) "$who started a holiday" else "$who ended the holiday", "$who · ${if (start) "Holiday on" else "Holiday over"}")
        }
        "knob" ->
            if (state == "off") pick("The $room thermostat was turned off", "$short knob off")
            else pick("The $room thermostat was turned to $target", "$short knob $target")
        "schedule" ->
            if (number != null) pick("Schedule set $room to $target", "$short $target (schedule)")
            else pick("Schedule turned $room off", "$short off (schedule)")
        "boiler" -> boilerText(e, state, brief)
        "offline" -> {
            val device = e.device
            if (device != null) {
                val where = if (e.room != null) "$device in ${e.room}" else device
                if (state == "offline") pick("$where went offline", "$device offline")
                else pick("$where is back online", "$device online")
            } else if (state == "offline") pick("$room went offline", "$short offline")
            else pick("$room is back online", "$short online")
        }
        else -> pick("${who ?: "Something"} changed ${room.ifBlank { "the heating" }}", "${short.ifBlank { "Heating" }} changed")
    }
}

private fun boilerText(e: ActivityEvent, state: String?, brief: Boolean): String {
    fun pick(wide: String, narrow: String) = if (brief) narrow else wide
    return when (state) {
        "heating" -> pick("Boiler is now heating", "Boiler heating")
        "cycling" ->
            if (e.reason == "reached-temp" && e.rooms.isNotEmpty()) {
                pick(
                    "${joinNames(e.rooms)} ${if (e.rooms.size == 1) "has" else "have"} reached temperature, boiler cycling down",
                    "${joinNames(e.rooms.map(::shortRoom))} at temp",
                )
            } else pick("Boiler finishing its minimum run", "Boiler finishing run")
        "resting" -> pick("Boiler resting, waiting out its minimum rest", "Boiler resting")
        "cycled" -> pick("Boiler cycled ${e.count} times", "Boiler cycled ×${e.count}")
        else -> pick("Boiler is now idle", "Boiler idle")
    }
}

private fun joinNames(names: List<String>): String = when (names.size) {
    0, 1 -> names.firstOrNull().orEmpty()
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
}

/** "now", "5m ago", "2h ago", "yesterday", then the date. */
fun agoText(at: String, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
    val then = OffsetDateTime.parse(at).toInstant()
    val minutes = Duration.between(then, now).toMinutes().coerceAtLeast(0)
    if (minutes < 1) return "now"
    if (minutes < 60) return "${minutes}m ago"
    if (minutes < 24 * 60) return "${minutes / 60}h ago"
    val days = LocalDate.ofInstant(now, zone).toEpochDay() - LocalDate.ofInstant(then, zone).toEpochDay()
    if (days <= 1) return "yesterday"
    return DateTimeFormatter.ofPattern("d MMM", Locale.UK).withZone(zone).format(then)
}

/** What TalkBack reads for a line: always the full sentence, then when it happened, whatever the line shows. */
fun activityDescription(e: ActivityEvent, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    activityText(e, brief = false) + ", " + agoText(e.at, now, zone)

/** What the tray says in place of events: only once the first page has loaded with nothing in it, and not over the retry row. */
fun activityEmptyText(loaded: Boolean, failed: Boolean, events: List<ActivityEvent>): String? =
    if (loaded && !failed && events.isEmpty()) "Nothing has changed yet" else null

/** The tray shows the brief wording below this width; a tablet-width screen gets sentences. */
const val ACTIVITY_WIDE_DP = 600
