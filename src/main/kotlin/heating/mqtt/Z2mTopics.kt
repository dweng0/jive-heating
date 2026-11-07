package heating.mqtt

/** Zigbee2MQTT topic names. Valve friendly names must not contain '/'. The base topic can change at runtime (connection setup). */
class Z2mTopics(base: String) {
    @Volatile
    var base: String = base

    val bridgeDevices get() = "$base/bridge/devices"
    val allStates get() = "$base/+"
    val allAvailability get() = "$base/+/availability"

    fun state(valve: String) = "$base/$valve"
    fun set(valve: String) = "$base/$valve/set"
    fun get(valve: String) = "$base/$valve/get"
    fun availability(valve: String) = "$base/$valve/availability"

    /** Valve name from a state or availability topic. */
    fun valveName(topic: String): String = topic.removePrefix("$base/").substringBefore('/')
}
