package heating.mqtt

/**
 * The only way the service talks MQTT. Production uses [PahoMqttBus];
 * tests use [InMemoryMqttBus].
 *
 * Handlers may be called on any thread. Components guard their own state.
 */
interface MqttBus {
    fun publish(topic: String, payload: String, retained: Boolean = false)
    fun subscribe(filter: String, handler: (topic: String, payload: String) -> Unit)

    /** Like [subscribe], but also told whether each message was retained (a retained message replayed on connect, or live). */
    fun subscribeWithRetained(filter: String, handler: (topic: String, payload: String, retained: Boolean) -> Unit) =
        subscribe(filter) { topic, payload -> handler(topic, payload, false) }
}

/** MQTT topic filter matching with `+` (one level) and `#` (rest) wildcards. */
object TopicFilter {
    fun matches(filter: String, topic: String): Boolean {
        val f = filter.split('/')
        val t = topic.split('/')
        for (i in f.indices) {
            if (f[i] == "#") return true
            if (i >= t.size) return false
            if (f[i] != "+" && f[i] != t[i]) return false
        }
        return f.size == t.size
    }
}
