package heating.rules

import heating.ApiException
import heating.events.EventRecorder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** One test on a payload: [field] is a path such as "contact" or "action.name"; "value" is a text payload as a whole. [value] is unused by "exists". */
@Serializable
data class Condition(val field: String, val operator: String, val value: JsonElement? = null)

/** Hours in the house's time zone ("HH:mm") when the rule stays quiet. With [sendSummary] the held messages go out as one summary when quiet hours end. */
@Serializable
data class QuietHours(val from: String, val to: String, val sendSummary: Boolean = false)

/** A notification rule as stored. [by] is the username of the admin who made it, [byUserId] its owner; "me" notifies the owner. */
@Serializable
data class Rule(
    val id: String,
    val name: String,
    val topic: String,
    val conditions: List<Condition> = emptyList(),
    val trigger: String = Rules.CHANGES_TO,
    val title: String,
    val message: String = "",
    val notify: String = Rules.EVERYONE,
    val cooldownSeconds: Int = 0,
    val quietHours: QuietHours? = null,
    val enabled: Boolean = true,
    val by: String,
    val byUserId: String? = null,
    val createdAt: String,
)

/** POST /api/rules, PUT /api/rules/{id} and POST /api/rules/test: everything a rule is except what the service fills in. */
@Serializable
data class RuleRequest(
    val name: String = "",
    val topic: String = "",
    val conditions: List<Condition> = emptyList(),
    val trigger: String = Rules.CHANGES_TO,
    val title: String = "",
    val message: String = "",
    val notify: String = Rules.EVERYONE,
    val cooldownSeconds: Int = 0,
    val quietHours: QuietHours? = null,
    val enabled: Boolean = true,
) {
    /** The request checked and tidied; throws a 400 naming the field that is wrong. */
    fun validated(): RuleRequest {
        if (name.isBlank()) throw ApiException.badRequest("name must be filled")
        if (!EventRecorder.validFilter(topic.trim())) throw ApiException.badRequest("topic must be a valid MQTT topic filter")
        if (title.isBlank()) throw ApiException.badRequest("title must be filled")
        if (trigger != Rules.CHANGES_TO && trigger != Rules.IS) throw ApiException.badRequest("trigger must be \"changes to\" or \"is\"")
        if (notify != Rules.ME && notify != Rules.EVERYONE) throw ApiException.badRequest("notify must be \"me\" or \"everyone\"")
        if (cooldownSeconds !in 0..86400) throw ApiException.badRequest("cooldownSeconds must be 0 to 86400")
        val tidy = conditions.map { c ->
            if (c.field.isBlank()) throw ApiException.badRequest("condition field must be filled")
            val operator = Rules.OPERATOR_ALIASES[c.operator] ?: throw ApiException.badRequest("condition operator must be one of = ≠ > < ≥ ≤ contains exists")
            if (operator == "exists") {
                Condition(c.field.trim(), operator, null)
            } else {
                if (c.value !is JsonPrimitive || c.value.toString() == "null") throw ApiException.badRequest("condition value must be a text, number or true/false")
                Condition(c.field.trim(), operator, c.value)
            }
        }
        quietHours?.let {
            if (Rules.minutes(it.from) == null) throw ApiException.badRequest("quietHours.from must be HH:mm")
            if (Rules.minutes(it.to) == null) throw ApiException.badRequest("quietHours.to must be HH:mm")
            if (it.from == it.to) throw ApiException.badRequest("quietHours.from and quietHours.to must differ")
        }
        Templates.validate("title", title)
        Templates.validate("message", message)
        return copy(name = name.trim(), topic = topic.trim(), conditions = tidy)
    }
}
