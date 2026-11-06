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
    /** Energy areas' power now: the whole-area meter's, else the sum of its plugs reporting. */
    val areas: List<AreaPower> = emptyList(),
)

/** An energy area's live power; null while its clamp has no live reading. */
@Serializable
data class AreaPower(val area: String, val name: String, val watts: Double? = null)

/** Green up to [green] W (the typical overnight load), red from [red] W (the household's top tenth). */
@Serializable
data class PowerBands(val green: Double, val red: Double)

@Serializable
data class PowerPoint(val at: String, val watts: Double? = null)

/** What made up today's peak: kind is room, area, device (in neither) or else (what none of them accounts for). */
@Serializable
data class PeakPart(val kind: String, val id: String? = null, val name: String, val watts: Double)

/** The highest five-minute house power since midnight, when it was, and what was using it then, largest first. */
@Serializable
data class TodayPeak(val watts: Double, val at: String, val parts: List<PeakPart> = emptyList())

/**
 * openapi: PowerSummary. [bands] is null with under a day of readings; [recent] is the last 30 minutes; [todayPeak] is
 * null before today's first sample (and from older services).
 */
@Serializable
data class PowerSummary(val bands: PowerBands? = null, val recent: List<PowerPoint> = emptyList(), val todayPeak: TodayPeak? = null)

/** openapi: HouseHistoryPoint. One point of the house's history chart from [at]. */
@Serializable
data class HouseHistoryPoint(
    val at: String,
    val kwh: Double? = null,
    val cost: Double? = null,
    val watts: Double? = null,
    val incomplete: Boolean = false,
    /** The daily target times the point's days, for a day or a month only. */
    val targetKwh: Double? = null,
)

/** openapi: HouseHistory. The house meter's use from [from] to [to]; [earliest] is how far back the chart can go. */
@Serializable
data class HouseHistory(
    val currency: String = "GBP",
    val range: String,
    val from: String,
    val to: String,
    val earliest: String? = null,
    val points: List<HouseHistoryPoint> = emptyList(),
)
