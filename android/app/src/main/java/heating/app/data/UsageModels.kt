package heating.app.data

import kotlinx.serialization.Serializable

/**
 * openapi: UsagePoint. Power, use and cost (pence) over one five minutes ([at] its end), hour or day ([at] its start). Null with
 * no reading; [incomplete] when a reading is missing, the figures then only what was recorded.
 */
@Serializable
data class UsagePoint(
    val at: String,
    val watts: Double? = null,
    val kwh: Double? = null,
    val cost: Double? = null,
    val incomplete: Boolean = false,
)

/** openapi: EnergyArea. A named thing with meters and no valves, such as "Lights". [id] stays the same through a rename. */
@Serializable
data class EnergyArea(
    val id: String,
    val name: String,
    /** The clamp that measures all of it. */
    val wholeMeter: String? = null,
    /** Ids of the areas it shares a circuit with. */
    val overlaps: List<String> = emptyList(),
)

/** The name an energy area is created with or renamed to. */
@Serializable
data class EnergyAreaName(val name: String)

/** openapi: PlugUsage. */
@Serializable
data class PlugUsage(val meter: String, val points: List<UsagePoint> = emptyList())

/**
 * openapi: RoomUsage and EverythingElseUsage. A room's or everything else's electricity over time. [room] is the whole of it,
 * [plugs] those in the room now (none for everything else), and [earliest] how far back it can be read.
 */
@Serializable
data class EnergyUsage(
    val currency: String = "GBP",
    val from: String,
    val to: String,
    val earliest: String? = null,
    val room: List<UsagePoint> = emptyList(),
    val plugs: List<PlugUsage> = emptyList(),
)
