package heating.app.data

import kotlinx.serialization.Serializable

/** A room's live power: the sum of its monitored plugs reporting now; null when none is. */
@Serializable
data class RoomPower(val room: String, val name: String, val watts: Double? = null)

/** A monitored device in no room and its live power; null while it is offline. */
@Serializable
data class DevicePower(val meter: String, val watts: Double? = null)

/**
 * openapi: LivePower. A message on /ws/power (admin only). [watts] is null with no house reading in the last
 * 2 minutes; [elseWatts] is the house use no room or device accounts for, null while [watts] is.
 */
@Serializable
data class LivePower(
    val watts: Double? = null,
    val at: String? = null,
    val rooms: List<RoomPower> = emptyList(),
    val devices: List<DevicePower> = emptyList(),
    val elseWatts: Double? = null,
)

/** Green up to [green] W (the typical overnight load), red from [red] W (the household's top tenth). */
@Serializable
data class PowerBands(val green: Double, val red: Double)

@Serializable
data class PowerPoint(val at: String, val watts: Double? = null)

/** openapi: PowerSummary. [bands] is null with under a day of readings; [recent] is the last 30 minutes. */
@Serializable
data class PowerSummary(val bands: PowerBands? = null, val recent: List<PowerPoint> = emptyList())
