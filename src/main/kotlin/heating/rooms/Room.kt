package heating.rooms

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Control {
    @SerialName("grouped") GROUPED,
    @SerialName("independent") INDEPENDENT,
}

@Serializable
enum class Mode {
    @SerialName("schedule") SCHEDULE,
    @SerialName("manual") MANUAL,
    @SerialName("off") OFF,
}

/**
 * Everything a zone can be told to do. A grouped room has one set ([Room.settings]);
 * an independent room has one per valve ([Room.valveSettings]).
 * New behaviour (schedule, boost, override...) adds fields here.
 */
@Serializable
data class ZoneSettings(
    val mode: Mode = Mode.SCHEDULE,
    val manualTarget: Double = 20.0,
    val schedule: WeekSchedule = WeekSchedule.DEFAULT,
    val override: KnobOverride? = null,
    val boost: Boost? = null,
)

@Serializable
data class Room(
    val id: String,
    val name: String,
    val control: Control = Control.GROUPED,
    val valves: List<String> = emptyList(),
    val settings: ZoneSettings = ZoneSettings(),
    val valveSettings: Map<String, ZoneSettings> = emptyMap(),
    val notices: List<String> = emptyList(),
    val smartTemperatureControl: Boolean = true,
    /** Friendly name of the room's temperature sensor, if it has one. */
    val sensor: String? = null,
) {
    /** The zones this room controls: one for grouped, one per valve for independent. */
    fun zones(): List<Zone> = when (control) {
        Control.GROUPED -> listOf(Zone(id, id, valves, settings))
        Control.INDEPENDENT -> valves.map { Zone(zoneId(id, it), id, listOf(it), valveSettings[it] ?: settings) }
    }

    companion object {
        fun zoneId(roomId: String, valve: String) = "$roomId:$valve"
    }
}

/** The unit that has a mode, target, schedule and boost. Derived from a [Room]; never stored. */
data class Zone(
    val id: String,
    val roomId: String,
    val valves: List<String>,
    val settings: ZoneSettings,
)
