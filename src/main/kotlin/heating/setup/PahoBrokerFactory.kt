package heating.setup

import heating.mqtt.PahoMqttBus
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.slf4j.LoggerFactory
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors
import javax.net.ssl.SSLException

/**
 * Real broker connections. Handlers run on one worker thread per connection, in arrival order, never on Paho's
 * callback thread: a handler that publishes would otherwise block it waiting for the broker's ack.
 * Paho's own reconnecting is off; [heating.mqtt.ReconnectingMqttBus] retries with backoff.
 */
class PahoBrokerFactory(private val timeoutSeconds: Int = 5) : BrokerFactory {
    private val log = LoggerFactory.getLogger(PahoBrokerFactory::class.java)

    override fun open(settings: BrokerSettings, onMessage: (String, String, Boolean) -> Unit, onLost: (String) -> Unit): BrokerClient {
        val handlers = Executors.newSingleThreadExecutor { Thread(it, "mqtt-handlers").apply { isDaemon = true } }
        val client = MqttClient(settings.url, "home-heating", MemoryPersistence())
        client.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {
                log.warn("MQTT connection lost", cause)
                onLost(cause?.let { classify(it, settings.host, settings.port).message } ?: "the broker closed the connection")
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                val payload = String(message.payload, Charsets.UTF_8)
                handlers.execute { runCatching { onMessage(topic, payload, message.isRetained) }.onFailure { e -> log.error("handler failed for {}", topic, e) } }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
        val options = PahoMqttBus.connectOptions(settings.username?.takeIf { it.isNotEmpty() }, settings.password).apply {
            isAutomaticReconnect = false
            connectionTimeout = timeoutSeconds
        }
        try {
            client.connect(options)
        } catch (e: Throwable) {
            handlers.shutdownNow()
            runCatching { client.close(true) }
            throw classify(e, settings.host, settings.port)
        }
        return object : BrokerClient {
            override fun publish(topic: String, payload: String, retained: Boolean) {
                client.publish(topic, payload.toByteArray(Charsets.UTF_8), 1, retained)
            }

            override fun subscribe(filter: String) = client.subscribe(filter, 1)
            override fun unsubscribe(filter: String) = client.unsubscribe(filter)

            override fun close() {
                handlers.shutdownNow()
                runCatching { if (client.isConnected) client.disconnect(2000) }
                runCatching { client.close(true) }
            }
        }
    }

    companion object {
        /** The reason a connection attempt failed, in words an admin can act on. */
        fun classify(error: Throwable, host: String, port: Int): BrokerFailure {
            val chain = generateSequence(error) { it.cause }.take(8).toList()
            (chain.firstOrNull { it is BrokerFailure } as BrokerFailure?)?.let { return it }
            if (chain.any { it is UnknownHostException }) return BrokerFailure("unknown_host", "Can't find \"$host\". Check the host name.")
            chain.firstOrNull { it is SSLException }?.let {
                return BrokerFailure("tls", "Couldn't make a secure connection: ${it.message?.trimEnd('.') ?: "handshake failed"}.")
            }
            val mqtt = chain.firstOrNull { it is MqttException } as MqttException?
            val code = mqtt?.reasonCode
            if (code == MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt() || code == MqttException.REASON_CODE_NOT_AUTHORIZED.toInt()) {
                return BrokerFailure("not_authorized", "The broker turned down that username and password.")
            }
            if (chain.any { it is ConnectException || it is NoRouteToHostException }) return BrokerFailure("refused", "Nothing answered on $host:$port.")
            if (chain.any { it is SocketTimeoutException } ||
                code == MqttException.REASON_CODE_CLIENT_TIMEOUT.toInt() || code == MqttException.REASON_CODE_CONNECTION_LOST.toInt()
            ) {
                return BrokerFailure("timeout", "$host:$port didn't answer within 5 seconds.")
            }
            return BrokerFailure("refused", "Nothing answered on $host:$port.")
        }
    }
}
