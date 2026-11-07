package heating.app.data

/** Whether an MQTT topic filter ("z2m/+", "z2m/#") matches a topic. */
fun topicMatches(filter: String, topic: String): Boolean {
    val f = filter.split('/')
    val t = topic.split('/')
    for (i in f.indices) {
        if (f[i] == "#") return true
        if (i >= t.size) return false
        if (f[i] != "+" && f[i] != t[i]) return false
    }
    return f.size == t.size
}

/** Whether [topic] is a filter rather than one topic. */
fun isWildcard(topic: String): Boolean = topic.split('/').any { it == "+" } || topic.endsWith("#")

/** "Any device like this": the last level becomes "+". Null for a topic with one level or a wildcard there already. */
fun widenedTopic(topic: String): String? {
    val parts = topic.split('/')
    if (parts.size < 2 || parts.last() == "+" || parts.last() == "#") return null
    return (parts.dropLast(1) + "+").joinToString("/")
}

/** The topics [filter] matches, leaving out Zigbee2MQTT's own bridge topics. */
fun matchingTopics(filter: String, topics: List<EventTopic>): List<EventTopic> =
    topics.filter { topicMatches(filter, it.topic) && topicDevice(it.topic) != "bridge" }

/** Whether [topic]'s latest payload has [field]. */
fun hasField(topic: EventTopic, field: String): Boolean = topic.json && payloadFields(topic.lastPayload).any { it.path == field }

/** A topic a widened rule matches, and whether it has every field the rule's conditions test. */
data class TopicMatch(val topic: String, val hasFields: Boolean)

fun widenedMatches(filter: String, conditions: List<RuleCondition>, topics: List<EventTopic>): List<TopicMatch> {
    val fields = conditions.map { it.field }.filter { it.isNotBlank() }
    return matchingTopics(filter.trim(), topics).map { t -> TopicMatch(t.topic, fields.isNotEmpty() && fields.all { hasField(t, it) }) }
}

/** [text] with every use of the device [from] replaced by [to]. */
fun swapDevice(text: String, from: String, to: String): String = if (from.isNotEmpty() && from != to) text.replace(from, to) else text

/** One of the rules "Pick devices from a list" makes: [request] for [topic], with the device it [started] from swapped for this one in its texts. */
fun perDevice(request: RuleRequest, started: String, topic: String): RuleRequest {
    val from = topicDevice(started)
    val to = topicDevice(topic)
    fun swap(text: String) = swapDevice(swapDevice(swapDevice(text, "{{device}}", to), from, to), readable(from), readable(to))
    return request.copy(topic = topic, name = swap(request.name), title = swap(request.title), message = swap(request.message))
}
