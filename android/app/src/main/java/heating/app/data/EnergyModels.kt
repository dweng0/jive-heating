package heating.app.data

import kotlinx.serialization.Serializable

/** openapi: EnergySettings. Prices in the currency's minor unit (pence); admins only. */
@Serializable
data class EnergySettings(
    val gasPencePerKwh: Double = 11.0,
    val gasStandingPencePerDay: Double = 30.0,
    val boilerAverageKw: Double = 12.0,
    val electricityPencePerKwh: Double = 27.0,
    val electricityStandingPencePerDay: Double = 50.0,
    val electricityMeter: String? = null,
    /** ISO 4217 code every cost is in. */
    val currency: String = "GBP",
    val notices: List<String> = emptyList(),
    /** kWh a day the household aims to use on the house meter; null for none. */
    val dailyKwhTarget: Double? = null,
)

/** PUT /api/settings/energy with only the daily target; null clears it. */
@Serializable
data class DailyTargetRequest(val dailyKwhTarget: Double?)

/** PUT /api/settings/energy: the prices, and [currency] only when it changes; the meter is chosen separately. */
@Serializable
data class EnergyPricesRequest(
    val gasPencePerKwh: Double,
    val gasStandingPencePerDay: Double,
    val boilerAverageKw: Double,
    val electricityPencePerKwh: Double,
    val electricityStandingPencePerDay: Double,
    val currency: String? = null,
)

/** openapi: Meter. A Zigbee2MQTT clamp or plug that exposes energy and power. */
@Serializable
data class Meter(
    val name: String,
    val online: Boolean = true,
    /** Running total, kWh. */
    val energy: Double? = null,
    /** Power now, W. */
    val power: Double? = null,
    val stale: Boolean = false,
    val lastReading: String? = null,
    /** It is the house electricity meter. */
    val inUse: Boolean = false,
    /** Its use is monitored, as part of the house meter's. */
    val monitored: Boolean = false,
    /** Id of the room this monitored device is in; null when in no room. */
    val room: String? = null,
    /** Id of the energy area this monitored plug is in; a plug is in one room or one area at most. */
    val area: String? = null,
    /** Id of the energy area this meter measures as its whole-area meter. */
    val measures: String? = null,
    /** False for a meter reporting power but no energy total: it can only measure an energy area. */
    val reportsEnergy: Boolean = true,
    /** The Zigbee2MQTT device it is on: its own name, or the two-clamp meter it is one clamp of. */
    val device: String? = null,
    /** "A" or "B" for one clamp of a two-clamp meter. */
    val clamp: String? = null,
    /** The clamp reads backwards; its use is still counted. */
    val reversed: Boolean = false,
    /** The name an admin gave it, else its Zigbee2MQTT name. */
    val label: String? = null,
) {
    /** What it is shown as: its label, else its name. */
    val shownAs: String get() = label?.takeIf { it.isNotBlank() } ?: name
}

/** PUT /api/meters/{meter}/label. */
@Serializable
data class MeterLabelRequest(val label: String)

/** PUT /api/meters/{meter}/job: "house", "area" (with [area]), "monitored" (with an optional [room] or [area]) or "none". */
@Serializable
data class MeterJob(val job: String, val room: String? = null, val area: String? = null)

/** openapi: EnergyTarget. The house's electricity against the daily target; [deltaKwh] below 0 is under target. */
@Serializable
data class EnergyTarget(
    val kwh: Double,
    val usedKwh: Double,
    val expectedKwh: Double,
    val deltaKwh: Double,
    val deltaCost: Double = 0.0,
    val onTarget: Boolean,
    val projectedKwh: Double? = null,
    val incomplete: Boolean = false,
)

/** openapi: AreaPlugEnergy. */
@Serializable
data class AreaPlugEnergy(val meter: String, val kwh: Double = 0.0, val cost: Double = 0.0)

/** openapi: AreaEnergy. An energy area's use: its clamp's if it has one, else its plugs'; [rest] what the clamp measured beyond them. */
@Serializable
data class AreaEnergy(
    val area: String,
    val name: String,
    val kwh: Double = 0.0,
    val cost: Double = 0.0,
    val missingMinutes: Int = 0,
    val incomplete: Boolean = false,
    val wholeMeter: String? = null,
    val plugs: List<AreaPlugEnergy> = emptyList(),
    val rest: Double? = null,
    val restCost: Double? = null,
    val overlaps: List<String> = emptyList(),
)

/** openapi: EnergyDay. Costs in pence. */
@Serializable
data class EnergyDay(
    val date: String,
    val boilerHours: Double = 0.0,
    val gasKwh: Double = 0.0,
    val gasCost: Double = 0.0,
    val electricityKwh: Double? = null,
    val electricityCost: Double? = null,
    val standingCost: Double = 0.0,
    val totalCost: Double = 0.0,
    val missingMinutes: Int = 0,
    val incomplete: Boolean = false,
    /** electricityKwh minus the daily target (today: the share of it gone); null with no target. */
    val targetDeltaKwh: Double? = null,
)

/**
 * openapi: DeviceEnergy. A monitored plug's use over the period, in pence; already part of the house meter's
 * electricity, so "of which", never added. [missingMinutes] the plug was stale makes it [incomplete]: cost so far.
 * [room] is the id of the room it is in now, null when in no room. [offlineSince] is set while it is offline now.
 * [totalStuckSince] is set while it uses power but its energy total is not going up.
 */
@Serializable
data class DeviceEnergy(
    val meter: String,
    val kwh: Double = 0.0,
    val cost: Double = 0.0,
    val missingMinutes: Int = 0,
    val incomplete: Boolean = false,
    val room: String? = null,
    val offlineSince: String? = null,
    val totalStuckSince: String? = null,
)

/**
 * openapi: RoomEnergy. A room's use over the period: the sum of its plugs, in pence; "of which" the house meter's
 * electricity, never added. [incomplete] when any of its plugs is; [missingMinutes] is the time any had no reading.
 */
@Serializable
data class RoomEnergy(
    val room: String,
    val name: String,
    val kwh: Double = 0.0,
    val cost: Double = 0.0,
    val missingMinutes: Int = 0,
    val incomplete: Boolean = false,
)

/**
 * openapi: EverythingElse. House electricity no monitored plug accounts for, never below 0, in pence; "of which",
 * never added. [incomplete] when the house meter or any plug is.
 */
@Serializable
data class EverythingElse(
    val kwh: Double = 0.0,
    val cost: Double = 0.0,
    val missingMinutes: Int = 0,
    val incomplete: Boolean = false,
)

/** openapi: EnergyDashboard. Costs in pence at today's prices; electricity is null without a house meter. */
@Serializable
data class EnergyDashboard(
    val period: String,
    val from: String = "",
    val to: String = "",
    val boilerHours: Double = 0.0,
    val gasKwh: Double = 0.0,
    val gasCost: Double = 0.0,
    val electricityMeter: String? = null,
    val electricityKwh: Double? = null,
    val electricityCost: Double? = null,
    val gasStandingCost: Double = 0.0,
    val electricityStandingCost: Double? = null,
    val standingCost: Double = 0.0,
    val totalCost: Double = 0.0,
    val missingMinutes: Int = 0,
    val incomplete: Boolean = false,
    val days: List<EnergyDay> = emptyList(),
    /** ISO 4217 code the costs are in. */
    val currency: String = "GBP",
    /** Monitored devices, already part of electricityKwh and totalCost. */
    val devices: List<DeviceEnergy> = emptyList(),
    /** Rooms with monitored plugs, by name; part of devices, so never added to anything. */
    val rooms: List<RoomEnergy> = emptyList(),
    /** House electricity minus all monitored plugs; null without a house meter. */
    val everythingElse: EverythingElse? = null,
    /** Energy areas, by name; "of which", never added. */
    val areas: List<AreaEnergy> = emptyList(),
    /** The house's electricity against the daily target; null with no target or house meter. */
    val target: EnergyTarget? = null,
)
