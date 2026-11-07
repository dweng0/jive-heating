package heating.setup

import kotlinx.serialization.Serializable

/** Where the MQTT broker is and how to log in. [password] is plain text in memory, encrypted when stored. */
data class BrokerSettings(
    val host: String,
    val port: Int,
    val tls: Boolean = false,
    val username: String? = null,
    val password: String? = null,
) {
    val url: String get() = "${if (tls) "ssl" else "tcp"}://$host:$port"

    companion object {
        /** Settings from an MQTT_URL such as tcp://mosquitto:1883 (ssl:// or mqtts:// switch TLS on). */
        fun fromUrl(url: String, username: String?, password: String?): BrokerSettings {
            val uri = java.net.URI(url.trim())
            val tls = uri.scheme in setOf("ssl", "tls", "mqtts", "wss")
            val host = uri.host ?: throw IllegalStateException("MQTT_URL has no host: $url")
            return BrokerSettings(host, if (uri.port > 0) uri.port else if (tls) 8883 else 1883, tls, username, password)
        }
    }
}

/** Why a broker could not be reached; [reason] is one of unknown_host, refused, timeout, not_authorized, tls. */
class BrokerFailure(val reason: String, override val message: String) : RuntimeException(message)

/** One open connection to a broker. */
interface BrokerClient {
    fun publish(topic: String, payload: String, retained: Boolean)
    fun subscribe(filter: String)
    fun unsubscribe(filter: String)
    fun close()
}

/** Opens broker connections. Production uses [PahoBrokerFactory]; tests use a fake broker. */
interface BrokerFactory {
    /**
     * Connects with [settings], handing every message to [onMessage] and calling [onLost] with a reason if
     * the connection later drops. Throws [BrokerFailure] when it cannot connect.
     */
    fun open(settings: BrokerSettings, onMessage: (topic: String, payload: String, retained: Boolean) -> Unit, onLost: (reason: String) -> Unit): BrokerClient
}

/** Connects, subscribes, disconnects. Returns null when the broker is fine, otherwise why not. */
fun BrokerFactory.test(settings: BrokerSettings): BrokerFailure? = try {
    val client = open(settings, { _, _, _ -> }, {})
    try {
        client.subscribe("heating/setup-test")
    } finally {
        runCatching { client.close() }
    }
    null
} catch (e: BrokerFailure) {
    e
}

@Serializable
data class BrokerRequest(val host: String? = null, val port: Int? = null, val tls: Boolean = false, val username: String? = null, val password: String? = null)
