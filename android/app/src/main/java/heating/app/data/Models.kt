package heating.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Mirrors the schemas in the service's src/main/resources/openapi.json (the contract).

@Serializable
enum class Mode {
    @SerialName("schedule") SCHEDULE,
    @SerialName("manual") MANUAL,
    @SerialName("off") OFF,
}

@Serializable
enum class Control {
    @SerialName("grouped") GROUPED,
    @SerialName("independent") INDEPENDENT,
}

@Serializable
enum class Day {
    @SerialName("monday") MONDAY,
    @SerialName("tuesday") TUESDAY,
    @SerialName("wednesday") WEDNESDAY,
    @SerialName("thursday") THURSDAY,
    @SerialName("friday") FRIDAY,
    @SerialName("saturday") SATURDAY,
    @SerialName("sunday") SUNDAY,
}

@Serializable
data class Boost(val target: Double, val endsAt: String)

@Serializable
data class Override(val target: Double, val until: String)

@Serializable
data class NextChange(val at: String, val target: Double)

@Serializable
data class Zone(
    val id: String,
    val valves: List<String>,
    val mode: Mode,
    val manualTarget: Double,
    val target: Double?,
    val current: Double?,
    val heating: Boolean,
    val callingForHeat: Boolean,
    val nextChange: NextChange? = null,
    val boost: Boost? = null,
    val override: Override? = null,
)

@Serializable
data class Room(
    val id: String,
    val name: String,
    val control: Control,
    val mode: Mode,
    val current: Double?,
    val target: Double?,
    val heating: Boolean,
    val valves: List<String>,
    val zones: List<Zone>,
    val notices: List<String>,
    val smartTemperatureControl: Boolean,
    /** The room's temperature sensor, if it has one. */
    val sensor: String? = null,
    /** "sensor" while an online sensor measures the room, else "valves". */
    val temperatureSource: String = "valves",
) {
    /** Has a sensor, but it is offline so the valves' own readings are used. */
    val sensorOffline: Boolean get() = sensor != null && temperatureSource != "sensor"
}

/** A Zigbee device other than a valve that reports temperature (e.g. a smoke alarm). */
@Serializable
data class Sensor(
    val name: String,
    val room: String?,
    val online: Boolean,
    val temperature: Double?,
    val battery: Int?,
    val lowBattery: Boolean,
)

@Serializable
data class Valve(
    val name: String,
    val room: String?,
    val online: Boolean,
    val currentTemperature: Double?,
    val reportedSetpoint: Double?,
    val runningState: String?,
    val battery: Int?,
    val lowBattery: Boolean,
    val openWindow: Boolean,
    val childLock: Boolean,
)

@Serializable
data class Block(val start: String, val target: Double)

@Serializable
data class DaySchedule(val blocks: List<Block>)

@Serializable
data class WeekSchedule(
    val monday: DaySchedule,
    val tuesday: DaySchedule,
    val wednesday: DaySchedule,
    val thursday: DaySchedule,
    val friday: DaySchedule,
    val saturday: DaySchedule,
    val sunday: DaySchedule,
) {
    operator fun get(day: Day): DaySchedule = when (day) {
        Day.MONDAY -> monday
        Day.TUESDAY -> tuesday
        Day.WEDNESDAY -> wednesday
        Day.THURSDAY -> thursday
        Day.FRIDAY -> friday
        Day.SATURDAY -> saturday
        Day.SUNDAY -> sunday
    }
}

@Serializable
data class Holiday(val from: String, val until: String, val target: Double, val active: Boolean)

@Serializable
data class Status(
    val heatDemand: Boolean,
    val holiday: Holiday?,
    val serverTime: String,
    val timezone: String,
    /** Boiler state; absent from an older service (fall back to [heatDemand]). */
    val boiler: Boiler? = null,
)

/**
 * [state] is heating, cycling (finishing its minimum run), resting (minimum rest before it may
 * start again) or idle. [until] is when cycling or resting ends.
 */
@Serializable
data class Boiler(val state: String, val until: String? = null)

/** Rooms changed together. [mode] is schedule, manual, off, or mixed when the rooms disagree. */
@Serializable
data class Group(
    val id: String,
    val name: String,
    val rooms: List<String>,
    val current: Double?,
    val target: Double?,
    val mode: String,
    /** Every room in the group heating */
    val heating: Boolean,
    /** Ids of its rooms that are heating */
    val heatingRooms: List<String> = emptyList(),
)

/**
 * One line of the activity feed: who changed what, or what the boiler did and why. The app words it
 * (see ui/ActivityFormat.kt). [actor] is null when nobody did it; [value] is a target (number) or a state (string).
 */
@Serializable
data class ActivityEvent(
    val id: Long,
    val at: String,
    val kind: String,
    val actor: String? = null,
    val room: String? = null,
    val value: kotlinx.serialization.json.JsonPrimitive? = null,
    val reason: String? = null,
    val rooms: List<String> = emptyList(),
    val count: Int? = null,
    /** The plug an "offline" event is about; null for valves and sensors (their room is named) and other kinds. */
    val device: String? = null,
)

@Serializable
data class ActivityList(val events: List<ActivityEvent> = emptyList())

/** WebSocket message from /ws. */
@Serializable
data class LiveEvent(
    val type: String,
    val room: Room? = null,
    val roomId: String? = null,
    val status: Status? = null,
    val valve: Valve? = null,
    val groups: List<Group>? = null,
    val sensor: Sensor? = null,
    val activity: ActivityEvent? = null,
) {
    companion object {
        /** Made by the app itself when the live connection opens; the service never sends it. */
        const val CONNECTED = "connected"

        /** An admin changed this user's rooms or admin rights; fetch the user and rooms again. */
        const val ACCESS_UPDATED = "access.updated"
    }
}

/** Recorded temperatures for 24h (5-minute points), 7d (30-minute) or 30d (2-hour averages). */
@Serializable
data class History(
    val from: String,
    val to: String,
    val rooms: List<RoomHistory>,
    /** Median of the rooms at each time */
    val house: List<HistoryPoint>,
    val boiler: List<BoilerPeriod>,
)

@Serializable
data class RoomHistory(val id: String, val name: String, val points: List<HistoryPoint>)

@Serializable
data class HistoryPoint(val at: String, val current: Double? = null, val target: Double? = null)

/** What the boiler did from..to; [to] is null for the state it is in now. */
@Serializable
data class BoilerPeriod(val state: String, val from: String, val to: String? = null)

@Serializable
data class ApiError(val error: String)

// Request bodies

@Serializable
data class CreateRoomRequest(val name: String)

@Serializable
data class PatchRoomRequest(
    val name: String? = null,
    val control: Control? = null,
    val sourceValve: String? = null,
    val smartTemperatureControl: Boolean? = null,
)

@Serializable
data class GroupRequest(val name: String? = null, val rooms: List<String>? = null)

/** Same body as /api/house: a mode (manual needs a target) or a boost. */
@Serializable
data class GroupControlRequest(val mode: Mode? = null, val target: Double? = null, val boost: Int? = null)

@Serializable
data class SetModeRequest(val mode: Mode, val target: Double? = null)

@Serializable
data class BoostRequest(val minutes: Int, val target: Double? = null)

@Serializable
data class CopyDayRequest(val from: Day, val to: List<Day>)

@Serializable
data class HolidayRequest(val until: String, val from: String? = null, val target: Double? = null)

@Serializable
data class PatchValveRequest(val openWindow: Boolean? = null, val childLock: Boolean? = null)

/** openapi: BoostSettings. The household's boost temperature; every user can read it, admins change it. */
@Serializable
data class BoostSettings(val defaultTarget: Double = 22.0)
