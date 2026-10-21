package heating.mqtt

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Real broker connection. Reconnects automatically and re-subscribes after reconnect.
 *
 * Handlers run on one worker thread, in arrival order, never on Paho's callback thread: a handler
 * that publishes (e.g. a sensor reading passed on to valves) would otherwise block that thread
 * waiting for the broker's ack, the connection would time out, and on reconnect the retained
 * message that triggered it would arrive again, in a loop.
 */
class PahoMqttBus(url: String, clientId: String = "home-heating") : MqttBus {
    private val log = LoggerFactory.getLogger(PahoMqttBus::class.java)
    private val client = MqttClient(url, clientId, MemoryPersistence())
    private val subscriptions = CopyOnWriteArrayList<Pair<String, (String, String) -> Unit>>()
    private val handlers = Executors.newSingleThreadExecutor { Thread(it, "mqtt-handlers").apply { isDaemon = true } }

    init {
        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String) {
                if (reconnect) subscriptions.map { it.first }.distinct().forEach { client.subscribe(it, 1) }
                log.info("MQTT connected to {} (reconnect={})", serverURI, reconnect)
            }

            override fun connectionLost(cause: Throwable?) = log.warn("MQTT connection lost", cause)

            override fun messageArrived(topic: String, message: MqttMessage) {
                val payload = String(message.payload, Charsets.UTF_8)
                handlers.execute {
                    subscriptions.filter { TopicFilter.matches(it.first, topic) }.forEach {
                        runCatching { it.second(topic, payload) }
                            .onFailure { e -> log.error("handler failed for {}", topic, e) }
                    }
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
        client.connect(MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
        })
    }

    override fun publish(topic: String, payload: String, retained: Boolean) {
        client.publish(topic, payload.toByteArray(Charsets.UTF_8), 1, retained)
    }

    override fun subscribe(filter: String, handler: (topic: String, payload: String) -> Unit) {
        subscriptions += filter to handler
        client.subscribe(filter, 1)
    }
}
