package heating.app.ui

import heating.app.data.Control
import heating.app.data.Group
import heating.app.data.Mode
import heating.app.data.Room
import heating.app.data.Status
import java.time.Instant
import java.time.OffsetDateTime

enum class BoilerState { HEATING, CYCLING, RESTING, IDLE }

/** The boiler's state, falling back to heat demand on/off for an older service. */
fun boilerState(status: Status?): BoilerState = when (status?.boiler?.state) {
    "heating" -> BoilerState.HEATING
    "cycling" -> BoilerState.CYCLING
    "resting" -> BoilerState.RESTING
    "idle" -> BoilerState.IDLE
    else -> if (status?.heatDemand == true) BoilerState.HEATING else BoilerState.IDLE
}

/** Whole seconds from [now] until the ISO-8601 time [until]; null if it can't be read. */
fun secondsUntil(until: String?, now: Instant): Long? {
    if (until == null) return null
    val end = runCatching { OffsetDateTime.parse(until).toInstant() }.getOrNull() ?: return null
    val ms = end.toEpochMilli() - now.toEpochMilli()
    return if (ms <= 0) 0 else (ms + 999) / 1000
}

/** "3:12" for a countdown; "any moment" once it reaches zero. */
fun formatCountdown(seconds: Long): String =
    if (seconds <= 0) "any moment" else "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

/** Chip text; [remaining] is the countdown in seconds for cycling and resting. */
fun boilerChipLabel(state: BoilerState, remaining: Long?): String {
    val countdown = remaining?.let { " · ${formatCountdown(it)}" } ?: ""
    return when (state) {
        BoilerState.HEATING -> "Heating"
        BoilerState.CYCLING -> "Finishing run$countdown"
        BoilerState.RESTING -> "Resting$countdown"
        BoilerState.IDLE -> "Idle"
    }
}

fun boilerExplanation(state: BoilerState): String = when (state) {
    BoilerState.HEATING -> "A room needs heat, so the boiler is firing."
    BoilerState.CYCLING ->
        "No room needs heat any more, but the boiler finishes its minimum 4-minute run so it doesn't " +
            "switch on and off too often (short-cycling wears it and wastes gas). It turns off when the countdown ends."
    BoilerState.RESTING ->
        "A room wants heat, but the boiler only stopped a moment ago. It rests for 5 minutes between " +
            "runs to avoid short-cycling, then starts."
    BoilerState.IDLE -> "No room needs heat right now."
}

/** A room is heating if any of its zones calls for heat or the room says so. */
fun isHeating(room: Room): Boolean = room.heating || room.zones.any { it.callingForHeat }

/** What the room is heating to: a boost's target, else the room's target; null when mixed. */
fun heatingTarget(room: Room): Double? {
    if (room.control == Control.INDEPENDENT && room.zones.size > 1) {
        return room.zones.map { it.boost?.target ?: it.target }.distinct().singleOrNull()
    }
    return room.zones.firstNotNullOfOrNull { it.boost?.target } ?: room.target
}

private fun isOff(room: Room): Boolean =
    if (room.zones.isEmpty()) room.mode == Mode.OFF
    else room.zones.all { it.mode == Mode.OFF && it.boost == null }

/** One line for the top of the home screen describing what the heating is doing. */
fun headline(rooms: List<Room>, groups: List<Group>, status: Status?): String {
    val heating = rooms.filter(::isHeating)
    val target = heating.map(::heatingTarget).distinct().singleOrNull()
    val to = target?.let { " to ${formatTemp(it)}" } ?: ""
    if (heating.size == 1) return "Heating ${heating[0].name}$to"
    if (heating.size >= 2) {
        val ids = heating.map { it.id }.toSet()
        groups.firstOrNull { it.rooms.toSet() == ids }?.let { return "Heating the ${it.name} group$to" }
        return "Heating ${heating.size} rooms$to"
    }
    return when {
        boilerState(status) == BoilerState.CYCLING -> "Boiler finishing its run"
        boilerState(status) == BoilerState.RESTING -> "Boiler resting"
        status?.holiday?.active == true -> "On holiday"
        rooms.isNotEmpty() && rooms.all(::isOff) -> "Heating is off"
        else -> "All rooms comfortable"
    }
}
