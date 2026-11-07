package heating.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.time.Duration
import java.time.Instant

/** openapi: Condition. One test on a payload; [value] is left out for "exists". */
@Serializable
data class RuleCondition(val field: String, val operator: String, val value: JsonElement? = null)

/** openapi: QuietHours. Hours ("HH:mm", in the house's time zone) when the rule stays quiet. */
@Serializable
data class QuietHours(val from: String, val to: String, val sendSummary: Boolean = false)

/** openapi: Rule. */
@Serializable
data class Rule(
    val id: String,
    val name: String,
    val topic: String,
    val conditions: List<RuleCondition> = emptyList(),
    val trigger: String = TRIGGER_CHANGES_TO,
    val title: String = "",
    val message: String = "",
    val notify: String = NOTIFY_EVERYONE,
    val cooldownSeconds: Int = 0,
    val quietHours: QuietHours? = null,
    val enabled: Boolean = true,
    val by: String = "",
    val byUserId: String? = null,
    val createdAt: String = "",
    val pausedUntil: String? = null,
    val pausedUntilLocal: String? = null,
    /** When the rule last sent a notification (from its 30-day history), or null. */
    val lastSentAt: String? = null,
)

/** openapi: RuleRequest. POST /api/rules, PUT /api/rules/{id} and POST /api/rules/test. */
@Serializable
data class RuleRequest(
    val name: String = "",
    val topic: String = "",
    val conditions: List<RuleCondition> = emptyList(),
    val trigger: String = TRIGGER_CHANGES_TO,
    val title: String = "",
    val message: String = "",
    val notify: String = NOTIFY_EVERYONE,
    val cooldownSeconds: Int = 0,
    val quietHours: QuietHours? = null,
    val enabled: Boolean = true,
)

/** openapi: RuleHistoryEntry. One match and what became of it. */
@Serializable
data class RuleHistoryEntry(
    val at: String,
    val topic: String,
    val payload: String,
    val outcome: String,
    val text: String,
    val recipients: Int = 0,
    val deliveryIds: List<String> = emptyList(),
    val detail: String? = null,
)

/** openapi: RuleTestEvent. [status] is "would_send", "not_met" or "no_change". */
@Serializable
data class RuleTestEvent(
    val id: Long,
    val at: String,
    val topic: String,
    val payload: String,
    val status: String,
    val result: String,
    val title: String? = null,
    val message: String? = null,
)

@Serializable
data class RuleTestResult(val events: List<RuleTestEvent> = emptyList())

@Serializable
data class RuleTestSent(val title: String = "", val message: String = "", val deliveryIds: List<String> = emptyList())

const val TRIGGER_CHANGES_TO = "changes to"
const val TRIGGER_IS = "is"
const val NOTIFY_ME = "me"
const val NOTIFY_EVERYONE = "everyone"

fun Rule.toRequest() = RuleRequest(name, topic, conditions, trigger, title, message, notify, cooldownSeconds, quietHours, enabled)

enum class RuleChip(val label: String) {
    ON("On"), OFF("Off"), PAUSED("Paused"), ME("Notifies me"), EVERYONE("Notifies everyone"),
}

/** A line of the Rules list: a rule, or (with [rule] null and [areaId] set) an area's "still using power" notification. */
data class RuleRow(
    val name: String,
    val topic: String,
    val notifies: String,
    val lastSent: String,
    /** The switch: null for area notifications, which are switched off in the area. */
    val enabled: Boolean?,
    val paused: String?,
    val notifyMe: Boolean,
    val rule: Rule? = null,
    val areaId: String? = null,
)

/** How long the service keeps a rule's history, and so how far back lastSentAt can see. */
const val HISTORY_DAYS = 30L

/**
 * How long ago a rule last sent a notification. With no send in its history: "Never sent" only for a rule made within
 * the days the history is kept ([createdAt]); an older one may have sent before that.
 */
fun lastSentText(at: String?, now: Instant = Instant.now(), createdAt: String = ""): String {
    if (at == null) {
        val made = runCatching { Instant.parse(createdAt) }.getOrNull()
        return if (made != null && Duration.between(made, now) < Duration.ofDays(HISTORY_DAYS)) "Never sent" else "Not sent in the last $HISTORY_DAYS days"
    }
    val seconds = Duration.between(Instant.parse(at), now).seconds.coerceAtLeast(0)
    val ago = when {
        seconds < 60 -> "$seconds s ago"
        seconds < 3600 -> "${seconds / 60} min ago"
        seconds < 86400 -> "${seconds / 3600} h ago"
        else -> "${seconds / 86400} days ago"
    }
    return "Last sent $ago"
}

/** Rules with who they notify and when they last sent, then area notifications. */
fun ruleRows(
    rules: List<Rule>,
    areas: List<Pair<EnergyArea, AreaAlert>>,
    now: Instant = Instant.now(),
): List<RuleRow> = rules.map { r ->
    RuleRow(
        name = r.name,
        topic = r.topic,
        notifies = if (r.notify == NOTIFY_ME) "Me" else "Everyone",
        lastSent = lastSentText(r.lastSentAt, now, r.createdAt),
        enabled = r.enabled,
        paused = r.pausedUntilLocal?.let { "Paused until $it" } ?: if (r.pausedUntil != null) "Paused" else null,
        notifyMe = r.notify == NOTIFY_ME,
        rule = r,
    )
} + areas.filter { it.second.hours != null }.map { (area, alert) ->
    RuleRow(
        name = "${area.name} still using power",
        topic = "Energy area: ${area.name}",
        notifies = if (alert.notify == NOTIFY_ME) "Me" else "Everyone",
        lastSent = "",
        enabled = null,
        paused = null,
        notifyMe = alert.notify == NOTIFY_ME,
        areaId = area.id,
    )
}

/** The topics the Topic filter offers: each rule's own, once, sorted. */
fun ruleTopics(rows: List<RuleRow>): List<String> = rows.filter { it.rule != null }.map { it.topic }.distinct().sorted()

/** The rows every chip picks (On and Off, Me and Everyone exclude each other by choosing none of the others), and on [topic] when one is chosen. */
fun filterRuleRows(rows: List<RuleRow>, chips: Set<RuleChip>, topic: String? = null): List<RuleRow> = rows.filter { r ->
    (RuleChip.ON !in chips || r.enabled != false) &&
        (RuleChip.OFF !in chips || r.enabled == false) &&
        (RuleChip.PAUSED !in chips || r.paused != null) &&
        (RuleChip.ME !in chips || r.notifyMe) &&
        (RuleChip.EVERYONE !in chips || !r.notifyMe) &&
        (topic.isNullOrEmpty() || (r.rule != null && r.topic == topic))
}

fun deleteRuleQuestion(name: String) = "Delete $name?"
