package heating.testing

import heating.mqtt.InMemoryMqttBus
import heating.mqtt.TopicFilter
import heating.setup.BrokerClient
import heating.setup.BrokerFactory
import heating.setup.BrokerFailure
import heating.setup.BrokerSettings

/** A few pretend brokers, found by host and port, that [heating.mqtt.ReconnectingMqttBus] can connect to. */
class FakeBrokers : BrokerFactory {
    class Broker(val host: String, val port: Int) {
        var login: Pair<String, String>? = null
        var tls: Boolean = false

        /** When true the broker is switched off: connecting to it times out. */
        var down = false

        /** Every message the service published to this broker. */
        val published = mutableListOf<InMemoryMqttBus.Message>()
        private val retained = linkedMapOf<String, String>()
        internal val clients = mutableListOf<Client>()

        /** Subscription filters of the clients connected now. */
        val subscribed: List<String> get() = synchronized(this) { clients.filter { it.open }.flatMap { it.filters }.distinct() }

        /** How many connections are open now. */
        val connections: Int get() = synchronized(this) { clients.count { it.open } }

        /** A message from another client, such as Zigbee2MQTT. */
        fun receive(topic: String, payload: String, retained: Boolean = false) {
            val targets = synchronized(this) {
                if (retained) this.retained[topic] = payload
                clients.filter { it.open && it.filters.any { f -> TopicFilter.matches(f, topic) } }
            }
            targets.forEach { it.onMessage(topic, payload, retained) }
        }

        /** Messages the service published to [topic]. */
        fun sentTo(topic: String) = published.filter { it.topic == topic }

        /** The broker goes away: every open connection is lost. */
        fun drop(reason: String = "connection reset") {
            down = true
            val lost = synchronized(this) { clients.filter { it.open }.also { list -> list.forEach { it.open = false } } }
            lost.forEach { it.onLost(reason) }
        }

        internal fun retainedFor(filter: String) = synchronized(this) { retained.filterKeys { TopicFilter.matches(filter, it) }.toList() }
    }

    class Client(val broker: Broker, val onMessage: (String, String, Boolean) -> Unit, val onLost: (String) -> Unit) : BrokerClient {
        @Volatile var open = true
        val filters = java.util.concurrent.CopyOnWriteArrayList<String>()

        override fun publish(topic: String, payload: String, retained: Boolean) {
            check(open) { "not connected" }
            synchronized(broker) { broker.published += InMemoryMqttBus.Message(topic, payload, retained) }
            broker.receive(topic, payload, retained)
        }

        override fun subscribe(filter: String) {
            if (filter !in filters) filters += filter
            broker.retainedFor(filter).forEach { (topic, payload) -> onMessage(topic, payload, true) }
        }

        override fun unsubscribe(filter: String) {
            filters -= filter
        }

        override fun close() {
            open = false
        }
    }

    private val brokers = mutableListOf<Broker>()

    /** Every connection attempt, as "host:port", in order. */
    val attempts = mutableListOf<String>()

    fun broker(host: String, port: Int = 1883): Broker = brokers.firstOrNull { it.host == host && it.port == port } ?: Broker(host, port).also { brokers += it }

    override fun open(settings: BrokerSettings, onMessage: (String, String, Boolean) -> Unit, onLost: (String) -> Unit): BrokerClient {
        attempts += "${settings.host}:${settings.port}"
        val host = settings.host
        val port = settings.port
        if (brokers.none { it.host == host }) throw BrokerFailure("unknown_host", "Can't find \"$host\". Check the host name.")
        val broker = brokers.firstOrNull { it.host == host && it.port == port } ?: throw BrokerFailure("refused", "Nothing answered on $host:$port.")
        if (broker.down) throw BrokerFailure("timeout", "$host:$port didn't answer within 5 seconds.")
        if (broker.tls != settings.tls) throw BrokerFailure("tls", "Couldn't make a secure connection: handshake failed.")
        broker.login?.let { if (it != (settings.username to settings.password)) throw BrokerFailure("not_authorized", "The broker turned down that username and password.") }
        return Client(broker, onMessage, onLost).also { synchronized(broker) { broker.clients += it } }
    }
}
