package heating.rooms

import kotlinx.serialization.Serializable

/** A temporary boost: heat to [target] until [endsAt] (ISO-8601 date-time with offset). */
@Serializable
data class Boost(val target: Double, val endsAt: String)
