package heating.rooms

import kotlinx.serialization.Serializable

/** Someone turned the valve knob in schedule mode: hold [target] until [until] (ISO-8601 date-time, the next block start). */
@Serializable
data class KnobOverride(val target: Double, val until: String)
