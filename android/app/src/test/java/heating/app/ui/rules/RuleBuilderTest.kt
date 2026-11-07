package heating.app.ui.rules

import heating.app.data.FakeHeatingApi
import heating.app.data.RuleCondition
import heating.app.data.RuleTestEvent
import heating.app.data.RuleTestResult
import heating.app.data.RuleTestSent
import heating.app.data.testSentLabel
import heating.app.data.TRIGGER_CHANGES_TO
import heating.app.data.draftFromEvent
import heating.app.data.previewLabel
import heating.app.data.ruleRoute
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class RuleBuilderTest {
    private fun payload(text: String): JsonElement = Json.parseToJsonElement(text)

    // BDD: A rule is built from an event
    @Test
    fun `a rule is prefilled from the event and field chosen`() {
        val last = payload("""{"contact":false,"battery":91}""")
        val draft = draftFromEvent("z2m/front_door", last, "contact", "false")
        assertEquals("z2m/front_door", draft.topic)
        assertEquals(listOf(RuleCondition("contact", "=", JsonPrimitive(false))), draft.conditions)
        assertEquals(TRIGGER_CHANGES_TO, draft.trigger)
        assertEquals("Front door contact false", draft.name)
        assertEquals("front_door", draft.title)
        assertEquals("front_door: contact is false", draft.message)
        // Numbers keep their type, text stays text, and a topic alone has no condition
        assertEquals(RuleCondition("battery", "=", JsonPrimitive(91)), draftFromEvent("z2m/front_door", last, "battery", "91").conditions.single())
        assertEquals(RuleCondition("action", "=", JsonPrimitive("single")), draftFromEvent("z2m/hall", payload("""{"action":"single"}"""), "action", "single").conditions.single())
        assertEquals(emptyList(), draftFromEvent("z2m/hall", null, null, null).conditions)
        assertEquals("Hall", draftFromEvent("z2m/hall", null, null, null).name)
        // A text that looks like a number or flag stays text when the payload says it is text
        assertEquals(JsonPrimitive("true"), draftFromEvent("t/x", payload("""{"mode":"true"}"""), "mode", "true").conditions.single().value)
    }

    // BDD: A rule is built from an event
    @Test
    fun `the builder shows the last 5 events on the topic, each marked whether it would have sent`() = runTest {
        val api = FakeHeatingApi()
        api.testResult = RuleTestResult(
            (1L..7L).map { RuleTestEvent(it, "2026-11-07T11:0$it:00Z", "z2m/front_door", """{"contact":${it % 2L == 0L}}""", if (it % 2L == 0L) "would_send" else "not_met", if (it % 2L == 0L) "would send" else "conditions not met: contact is true", "front_door", "m") },
        )
        val draft = draftFromEvent("z2m/front_door", payload("""{"contact":false}"""), "contact", "false")
        val shown = api.testRule(draft).events.take(5)
        assertEquals(draft, api.testedRules.single())
        assertEquals(5, shown.size)
        assertEquals("Would send", previewLabel(shown[1]))
        assertEquals("Would not send: conditions not met: contact is true", previewLabel(shown[0]))
        assertEquals("Would not send: no change", previewLabel(RuleTestEvent(9, "t", "t", "{}", "no_change", "no change")))
    }

    // BDD: A rule is built from an event
    @Test
    fun `conditions read as text in the builder`() {
        assertEquals("contact = false", heating.app.data.conditionText(RuleCondition("contact", "=", JsonPrimitive(false))))
        assertEquals("action = single", heating.app.data.conditionText(RuleCondition("action", "=", JsonPrimitive("single"))))
        assertEquals("battery exists", heating.app.data.conditionText(RuleCondition("battery", "exists")))
    }

    // BDD: A rule is built from an event
    @Test
    fun `notify me when opens the builder with the topic and field`() {
        assertEquals("rules/new?topic=z2m%2Ffront_door&field=contact&value=false", ruleRoute("z2m/front_door", "contact", "false"))
    }

    // BDD: Rules are managed in the app
    @Test
    fun `a test send says what was sent and that it went only to you`() {
        assertEquals("Sent to you: front_door — front_door: contact is false", testSentLabel(RuleTestSent("front_door", "front_door: contact is false", listOf("d1"))))
        assertEquals("Nothing was delivered: front_door — m", testSentLabel(RuleTestSent("front_door", "m", emptyList())))
    }
}
