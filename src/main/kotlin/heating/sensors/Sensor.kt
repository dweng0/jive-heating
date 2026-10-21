package heating.sensors

import kotlinx.serialization.Serializable

/**
 * Latest known state of one temperature sensor: a Zigbee2MQTT device that is not a TRVZB but
 * exposes `temperature` (e.g. a Zigbee smoke alarm). Keyed by its friendly name.
 */
@Serializable
data class Sensor(
    val name: String,
    val online: Boolean = true,
    val temperature: Double? = null,
    val battery: Int? = null,
)
