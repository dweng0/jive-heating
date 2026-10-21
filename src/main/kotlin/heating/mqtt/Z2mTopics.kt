package heating.mqtt

/** Zigbee2MQTT topic names. Valve friendly names must not contain '/'. */
class Z2mTopics(private val base: String) {
    val bridgeDevices = "$base/bridge/devices"
    val allStates = "$base/+"
    val allAvailability = "$base/+/availability"

    fun state(valve: String) = "$base/$valve"
    fun set(valve: String) = "$base/$valve/set"
    fun get(valve: String) = "$base/$valve/get"
    fun availability(valve: String) = "$base/$valve/availability"

    /** Valve name from a state or availability topic. */
    fun valveName(topic: String): String = topic.removePrefix("$base/").substringBefore('/')
}
