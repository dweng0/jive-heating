package heating.mqtt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * In-process MQTT broker stand-in. Behaves like a broker for one client:
 * publishes are delivered to matching subscriptions and retained messages
 * are replayed on subscribe. Everything the service publishes is recorded.
 */
class InMemoryMqttBus : MqttBus {
    data class Message(val topic: String, val payload: String, val retained: Boolean)

    private val subscriptions = mutableListOf<Pair<String, (String, String) -> Unit>>()
    private val retainedMessages = linkedMapOf<String, String>()
    private val _published = mutableListOf<Message>()

    /** Every message the service published, oldest first. */
    val published: List<Message> get() = synchronized(this) { _published.toList() }

    override fun publish(topic: String, payload: String, retained: Boolean) {
        synchronized(this) { _published += Message(topic, payload, retained) }
        dispatch(topic, payload, retained)
    }

    override fun subscribe(filter: String, handler: (topic: String, payload: String) -> Unit) {
        val replay = synchronized(this) {
            subscriptions += filter to handler
            retainedMessages.filterKeys { TopicFilter.matches(filter, it) }.toList()
        }
        replay.forEach { (topic, payload) -> handler(topic, payload) }
    }

    /** Simulate a message arriving from another client (e.g. Zigbee2MQTT). */
    fun receive(topic: String, payload: String, retained: Boolean = false) = dispatch(topic, payload, retained)

    /** JSON payloads the service published to [topic], oldest first. */
    fun sentTo(topic: String): List<JsonObject> =
        published.filter { it.topic == topic }.map { Json.parseToJsonElement(it.payload).jsonObject }

    fun clearPublished() = synchronized(this) { _published.clear() }

    private fun dispatch(topic: String, payload: String, retained: Boolean) {
        val handlers = synchronized(this) {
            if (retained) retainedMessages[topic] = payload
            subscriptions.filter { TopicFilter.matches(it.first, topic) }.map { it.second }
        }
        handlers.forEach { it(topic, payload) }
    }
}
