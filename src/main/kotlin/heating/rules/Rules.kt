package heating.rules

import heating.ApiException
import heating.heatingJson
import heating.mqtt.MqttBus
import heating.mqtt.TopicFilter
import heating.notify.DeliveryEvent
import heating.notify.Deliveries
import heating.store.Store
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * The household's notification rules, kept as one document in the store. See the feature "Notification rules".
 */
class Rules(
    private val store: Store,
    private val now: () -> Instant,
    private val deliveries: Deliveries,
    private val history: RuleHistory,
    /** Everyone a rule's "everyone" reaches. */
    private val everyone: () -> List<String>,
    /** The Zigbee2MQTT base topic, for {{device}}. */
    private val z2mBase: () -> String,
    /** The house's time zone, for quiet hours. */
    private val zone: ZoneId,
) {
    /** Matches held for a rule until its quiet hours end: how many, and the last one's topic and payload. */
    private class Held(var count: Int, var topic: String, var payload: String)

    private val held = mutableMapOf<String, Held>()

    /** Rules stopped for being noisy, until when. */
    private val pausedUntil = mutableMapOf<String, Instant>()

    /** When each rule recently sent, to tell when it is noisy. */
    private val recentSends = mutableMapOf<String, ArrayDeque<Instant>>()

    /** Whether each rule's conditions matched the last message on each topic: key is rule id and topic. */
    private val matched = mutableMapOf<Pair<String, String>, Boolean>()

    /** When each rule last sent for each topic. */
    private val lastSent = mutableMapOf<Pair<String, String>, Instant>()

    /** Listens to every message on [bus] (retained ones replayed on connect are not news and are ignored). */
    fun start(bus: MqttBus) {
        bus.subscribeWithRetained("#") { topic, payload, retained ->
            if (!retained) runCatching { onMessage(topic, payload) }.onFailure { log.warn("rules failed on {}: {}", topic, it.message) }
        }
    }

    /** Runs every enabled rule on [topic] against a message. */
    @Synchronized
    fun onMessage(topic: String, payload: String) {
        rules.filter { it.enabled && TopicFilter.matches(it.topic, topic) }.forEach { rule ->
            val key = rule.id to topic
            val met = Matching.conditionsMet(rule.conditions, payload)
            val before = matched.put(key, met) ?: false
            if (met && !(rule.trigger == CHANGES_TO && before)) fire(rule, topic, payload)
        }
    }

    private fun fire(rule: Rule, topic: String, payload: String) {
        val at = now()
        val key = rule.id to topic
        pausedUntil[rule.id]?.let { until ->
            if (at < until) {
                history.add(rule.id, at, topic, payload, Outcome.PAUSED)
                return
            }
            pausedUntil.remove(rule.id)
        }
        val last = lastSent[key]
        if (last != null && Duration.between(last, at) < Duration.ofSeconds(rule.cooldownSeconds.toLong())) {
            history.add(rule.id, at, topic, payload, Outcome.COOLDOWN)
            return
        }
        rule.quietHours?.let { q ->
            if (inQuietHours(q, at)) {
                if (q.sendSummary) {
                    held.getOrPut(rule.id) { Held(0, topic, payload) }.also { it.count++; it.topic = topic; it.payload = payload }
                    history.add(rule.id, at, topic, payload, Outcome.HELD)
                } else {
                    history.add(rule.id, at, topic, payload, Outcome.QUIET)
                }
                return
            }
        }
        val sends = recentSends.getOrPut(rule.id) { ArrayDeque() }
        while (sends.isNotEmpty() && Duration.between(sends.first(), at) > NOISY_WINDOW) sends.removeFirst()
        if (sends.size >= NOISY_LIMIT) {
            pause(rule, at)
            history.add(rule.id, at, topic, payload, Outcome.PAUSED, detail = NOISY_TEXT)
            return
        }
        sends += at
        val ids = send(rule, topic, payload, at)
        lastSent[key] = at
        history.add(rule.id, at, topic, payload, Outcome.SENT, ids)
    }

    private fun pause(rule: Rule, at: Instant) {
        pausedUntil[rule.id] = at.plus(PAUSE)
        recentSends.remove(rule.id)
        deliveries.enqueue(
            listOfNotNull(rule.byUserId).filter(deliveries::hasDevices), "${rule.name} paused", NOISY_TEXT, rule.name, ruleId = rule.id,
        )
        log.info("rule {} paused for an hour: {}", heating.untrusted(rule.name), NOISY_TEXT)
    }

    /** Lets a paused rule send again now. */
    @Synchronized
    fun resume(id: String): Rule {
        get(id)
        pausedUntil.remove(id)
        recentSends.remove(id)
        return get(id)
    }

    private fun shown(rule: Rule): Rule {
        val until = pausedUntil[rule.id]?.takeIf { it > now() } ?: return rule
        return rule.copy(pausedUntil = until.toString(), pausedUntilLocal = until.atZone(zone).toLocalTime().withSecond(0).withNano(0).toString())
    }

    private fun inQuietHours(q: QuietHours, at: Instant): Boolean {
        val local = at.atZone(zone)
        val minute = local.hour * 60 + local.minute
        val from = minutes(q.from) ?: return false
        val to = minutes(q.to) ?: return false
        return if (from < to) minute in from until to else minute >= from || minute < to
    }

    /** Sends the summary of what quiet hours held back, for each rule whose quiet hours have ended. */
    val tick = heating.Periodic { checkQuietHours() }

    @Synchronized
    private fun checkQuietHours() {
        val at = now()
        held.entries.toList().forEach { (id, h) ->
            val rule = rules.firstOrNull { it.id == id }
            val q = rule?.quietHours
            if (rule == null || q == null || !q.sendSummary || !rule.enabled) {
                held.remove(id)
            } else if (!inQuietHours(q, at)) {
                held.remove(id)
                val body = "${rule.name} ${if (h.count == 1) "once" else "${h.count} times"} overnight"
                val ids = deliveries.enqueue(
                    recipients(rule), rule.name, body, rule.name, ruleId = rule.id, event = DeliveryEvent(null, h.topic, payloadJson(h.payload), at.toString()),
                )
                history.add(rule.id, at, h.topic, h.payload, Outcome.SUMMARY, ids, body)
            }
        }
    }

    private fun recipients(rule: Rule): List<String> =
        (if (rule.notify == ME) listOfNotNull(rule.byUserId) else everyone()).filter(deliveries::hasDevices)

    private fun send(rule: Rule, topic: String, payload: String, at: Instant): List<String> {
        val base = z2mBase()
        return deliveries.enqueue(
            recipients(rule), Templates.fill(rule.title, topic, payload, base), Templates.fill(rule.message, topic, payload, base),
            rule.name, ruleId = rule.id, event = DeliveryEvent(null, topic, payloadJson(payload), at.toString()),
        )
    }

    private fun payloadJson(payload: String) = runCatching { Json.parseToJsonElement(payload) }.getOrNull() ?: JsonPrimitive(payload)

    private var rules: List<Rule> = store.get(KEY)?.let { heatingJson.decodeFromString(SERIALIZER, it) } ?: emptyList()

    @Synchronized
    fun list(): List<Rule> = rules.map(::shown)

    /** Rule [id]'s history, newest first. */
    fun historyOf(id: String, limit: Int = 100): List<RuleHistoryEntry> {
        get(id)
        return history.of(id, limit)
    }

    @Synchronized
    fun get(id: String): Rule = find(id).let(::shown)

    private fun find(id: String): Rule = rules.firstOrNull { it.id == id } ?: throw ApiException.notFound("rule not found")

    /** Adds a rule from [request] owned by [byUserId] ([by] is their username). */
    @Synchronized
    fun create(request: RuleRequest, byUserId: String, by: String): Rule {
        val r = request.validated()
        val rule = Rule(
            UUID.randomUUID().toString(), r.name, r.topic, r.conditions, r.trigger, r.title, r.message, r.notify,
            r.cooldownSeconds, r.quietHours, r.enabled, by, byUserId, now().toString(),
        )
        save(rules + rule)
        return rule
    }

    /** Replaces rule [id] with [request]; who made it and when stay. */
    @Synchronized
    fun replace(id: String, request: RuleRequest): Rule {
        val old = find(id)
        val r = request.validated()
        val rule = old.copy(
            name = r.name, topic = r.topic, conditions = r.conditions, trigger = r.trigger, title = r.title, message = r.message,
            notify = r.notify, cooldownSeconds = r.cooldownSeconds, quietHours = r.quietHours, enabled = r.enabled,
        )
        save(rules.map { if (it.id == id) rule else it })
        return shown(rule)
    }

    /** Removes rule [id]. */
    @Synchronized
    fun delete(id: String): Rule {
        val old = find(id)
        save(rules - old)
        pausedUntil.remove(id)
        recentSends.remove(id)
        history.forget(id)
        held.remove(id)
        matched.keys.removeAll { it.first == id }
        lastSent.keys.removeAll { it.first == id }
        return old
    }

    private fun save(updated: List<Rule>) {
        store.put(KEY, heatingJson.encodeToString(SERIALIZER, updated))
        rules = updated
    }

    companion object {
        private val log = LoggerFactory.getLogger(Rules::class.java)
        private const val KEY = "rules"
        private val SERIALIZER = ListSerializer(Rule.serializer())

        /** A rule that would send a 31st time in 10 minutes is stopped for an hour. */
        const val NOISY_LIMIT = 30
        val NOISY_WINDOW: Duration = Duration.ofMinutes(10)
        val PAUSE: Duration = Duration.ofHours(1)
        const val NOISY_TEXT = "It fired 30 times in 10 minutes"

        const val CHANGES_TO = "changes to"
        const val IS = "is"
        const val ME = "me"
        const val EVERYONE = "everyone"

        /** The operators, with the plain-keyboard spellings that mean the same. */
        val OPERATOR_ALIASES = mapOf(
            "=" to "=", "≠" to "≠", ">" to ">", "<" to "<", "≥" to "≥", "≤" to "≤", "contains" to "contains", "exists" to "exists",
            "==" to "=", "!=" to "≠", ">=" to "≥", "<=" to "≤",
        )

        /** Minutes after midnight of "HH:mm", or null. */
        fun minutes(text: String): Int? {
            val m = Regex("""([01]\d|2[0-3]):([0-5]\d)""").matchEntire(text) ?: return null
            return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
        }
    }
}
