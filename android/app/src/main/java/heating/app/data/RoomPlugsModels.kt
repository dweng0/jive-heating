package heating.app.data

import kotlinx.serialization.Serializable

/** A time a plug had no reading. ISO offset date-times in the household's time zone. */
@Serializable
data class OfflinePeriod(val from: String, val to: String)

/**
 * openapi: RoomPlug. One plug today: [power] now in W (null while offline or stale), use and cost so far in
 * pence at the electricity price alone. [incomplete] when it had no reading at some point; [offline] says when.
 * [offlineSince] is set while it is offline now: when it went. [totalStuckSince] is set while it uses power but its
 * energy total is not going up: when the total last changed.
 */
@Serializable
data class RoomPlug(
    val meter: String,
    val power: Double? = null,
    val kwh: Double = 0.0,
    val cost: Double = 0.0,
    val missingMinutes: Int = 0,
    val incomplete: Boolean = false,
    val offline: List<OfflinePeriod> = emptyList(),
    val offlineSince: String? = null,
    val totalStuckSince: String? = null,
)

/** openapi: RoomPlugs. A room's plugs today: the room's sum, "of which" the house meter's use, and each plug by name. */
@Serializable
data class RoomPlugs(
    val room: String,
    val currency: String = "GBP",
    val kwh: Double = 0.0,
    val cost: Double = 0.0,
    val missingMinutes: Int = 0,
    val incomplete: Boolean = false,
    val plugs: List<RoomPlug> = emptyList(),
)

/** openapi: EnergyBar. A room's use over one hour or day from [at]; [incomplete] when a plug had no reading for some of it, [kwh] null when for all. */
@Serializable
data class EnergyBar(val at: String, val kwh: Double? = null, val incomplete: Boolean = false)

/** openapi: RoomEnergyHistory. A room's use over time as bars, from the plug use recorded in it; admin only. */
@Serializable
data class RoomEnergyHistory(val room: String, val from: String, val to: String, val points: List<EnergyBar> = emptyList())
