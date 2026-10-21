package heating.valves

import kotlinx.serialization.Serializable

/** Latest known state of one TRVZB, keyed by its Zigbee2MQTT friendly name. */
@Serializable
data class Valve(
    val name: String,
    val online: Boolean = true,
    val currentTemperature: Double? = null,
    val reportedSetpoint: Double? = null,
    val runningState: String? = null,
    /** "heat" or "off" as the valve last reported it; turning the knob past its lowest point switches it off. */
    val systemMode: String? = null,
    /** The temperature (°C) the valve holds while off; the service wants [ValveCommander.FROST_PROTECTION]. */
    val frostProtection: Double? = null,
    val battery: Int? = null,
    val openWindow: Boolean = true,
    val childLock: Boolean = false,
)
