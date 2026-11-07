package heating.setup

import heating.Config
import heating.heatingJson
import heating.store.Store
import kotlinx.serialization.Serializable

@Serializable
private data class StoredBroker(
    val host: String,
    val port: Int,
    val tls: Boolean = false,
    val username: String? = null,
    /** Encrypted with HEATING_SECRET_KEY; never plain. */
    val password: String? = null,
)

@Serializable
private data class StoredConnection(
    val broker: StoredBroker? = null,
    val baseTopic: String? = null,
    val complete: Boolean = false,
)

/**
 * The broker and Zigbee2MQTT base topic, kept in the database. The environment (MQTT_URL, MQTT_USERNAME,
 * MQTT_PASSWORD, Z2M_BASE_TOPIC) only fills them in the first time the service starts with none saved.
 * The service refuses to start when saved secrets cannot be read with HEATING_SECRET_KEY.
 */
class ConnectionSettings(private val store: Store, config: Config) {
    private val box = config.secretKey?.takeIf { it.isNotBlank() }?.let(::SecretBox)
    private var stored: StoredConnection

    init {
        val saved = store.get(KEY)?.let { heatingJson.decodeFromString(StoredConnection.serializer(), it) }
        stored = saved ?: seed(config)
        // Fail at start rather than at first use.
        stored.broker?.let { broker(it) }
    }

    private fun seed(config: Config): StoredConnection {
        val url = config.mqttUrl ?: return StoredConnection()
        val from = BrokerSettings.fromUrl(url, config.mqttUsername, config.mqttPassword)
        return StoredConnection(StoredBroker(from.host, from.port, from.tls, from.username, from.password?.let(::encrypt)), config.z2mBaseTopic, complete = true)
            .also { save(it) }
    }

    private fun encrypt(plain: String): String =
        (box ?: throw IllegalStateException("HEATING_SECRET_KEY must be set to keep the broker password safe")).encrypt(plain)

    private fun broker(b: StoredBroker): BrokerSettings {
        val password = b.password?.let { stored ->
            val key = box ?: throw IllegalStateException("HEATING_SECRET_KEY must be set: the database holds an encrypted broker password")
            key.decrypt(stored) ?: throw IllegalStateException("HEATING_SECRET_KEY does not match the secrets saved in the database")
        }
        return BrokerSettings(b.host, b.port, b.tls, b.username, password)
    }

    private fun save(value: StoredConnection) {
        store.put(KEY, heatingJson.encodeToString(StoredConnection.serializer(), value))
        stored = value
    }

    @Synchronized
    fun broker(): BrokerSettings? = stored.broker?.let(::broker)

    @Synchronized
    fun baseTopic(): String? = stored.baseTopic

    @Synchronized
    fun complete(): Boolean = stored.complete

    @Synchronized
    fun saveBroker(settings: BrokerSettings) {
        save(
            stored.copy(
                broker = StoredBroker(settings.host, settings.port, settings.tls, settings.username, settings.password?.takeIf { it.isNotEmpty() }?.let(::encrypt)),
            ),
        )
    }

    @Synchronized
    fun saveBaseTopic(base: String) = save(stored.copy(baseTopic = base))

    @Synchronized
    fun markComplete() = save(stored.copy(complete = true))

    companion object {
        const val KEY = "setup.connection"
    }
}
