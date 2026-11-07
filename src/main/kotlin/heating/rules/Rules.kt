package heating.rules

import heating.ApiException
import heating.heatingJson
import heating.store.Store
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant
import java.util.UUID

/**
 * The household's notification rules, kept as one document in the store. See the feature "Notification rules".
 */
class Rules(
    private val store: Store,
    private val now: () -> Instant,
) {
    private var rules: List<Rule> = store.get(KEY)?.let { heatingJson.decodeFromString(SERIALIZER, it) } ?: emptyList()

    @Synchronized
    fun list(): List<Rule> = rules

    @Synchronized
    fun get(id: String): Rule = rules.firstOrNull { it.id == id } ?: throw ApiException.notFound("rule not found")

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
        val old = get(id)
        val r = request.validated()
        val rule = old.copy(
            name = r.name, topic = r.topic, conditions = r.conditions, trigger = r.trigger, title = r.title, message = r.message,
            notify = r.notify, cooldownSeconds = r.cooldownSeconds, quietHours = r.quietHours, enabled = r.enabled,
        )
        save(rules.map { if (it.id == id) rule else it })
        return rule
    }

    /** Removes rule [id]. */
    @Synchronized
    fun delete(id: String): Rule {
        val old = get(id)
        save(rules - old)
        return old
    }

    private fun save(updated: List<Rule>) {
        store.put(KEY, heatingJson.encodeToString(SERIALIZER, updated))
        rules = updated
    }

    companion object {
        private const val KEY = "rules"
        private val SERIALIZER = ListSerializer(Rule.serializer())

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
