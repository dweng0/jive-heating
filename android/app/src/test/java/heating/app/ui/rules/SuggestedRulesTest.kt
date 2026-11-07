package heating.app.ui.rules

import heating.app.data.FakeHeatingApi
import heating.app.data.KtorHeatingApi
import heating.app.data.RuleCondition
import heating.app.data.RuleRequest
import heating.app.data.RuleSuggestion
import heating.app.data.ServerConfig
import heating.app.data.TRIGGER_CHANGES_TO
import heating.app.data.draftRoute
import heating.app.data.ruleDraft
import heating.app.data.suggestionText
import heating.app.data.toRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SuggestedRulesTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun api(body: String) = KtorHeatingApi(
        ServerConfig("https://heating.example", "secret"),
        MockEngine { request ->
            requests += request
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )

    private val suggestionsJson = """[
        {"name":"front_door opened","topic":"z2m/front_door","conditions":[{"field":"contact","operator":"=","value":false}],"trigger":"changes to","title":"front_door","message":"front_door opened","device":"front_door","setUp":true},
        {"name":"front_door battery low","topic":"z2m/front_door","conditions":[{"field":"battery","operator":"<","value":15}],"trigger":"changes to","title":"front_door","message":"front_door battery is low ({{payload.battery}}%)","device":"front_door","setUp":false}
    ]"""

    private fun suggestion(name: String, device: String, field: String, operator: String, value: JsonPrimitive, setUp: Boolean = false) =
        RuleSuggestion(name, "z2m/$device", listOf(RuleCondition(field, operator, value)), TRIGGER_CHANGES_TO, device, name, device, setUp)

    // BDD: Rules are suggested for a Zigbee2MQTT device
    @Test
    fun `suggestions are read from the service, all of them or one device's, and say which are set up`() = runTest {
        val offered = api(suggestionsJson).suggestedRules("front_door")
        assertEquals(listOf("front_door opened", "front_door battery low"), offered.map { it.name })
        assertEquals(listOf(true, false), offered.map { it.setUp })
        assertEquals(RuleCondition("battery", "<", JsonPrimitive(15)), offered[1].conditions.single())
        assertEquals("front_door battery is low ({{payload.battery}}%)", offered[1].message)
        assertEquals("https://heating.example/api/rules/suggestions?device=front_door", requests.last().url.toString())
        api("[]").suggestedRules()
        assertEquals("https://heating.example/api/rules/suggestions", requests.last().url.toString())
    }

    // BDD: Rules are suggested for a Zigbee2MQTT device
    @Test
    fun `a suggestion reads as its condition and opens the rule form filled in`() {
        val opened = suggestion("front_door opened", "front_door", "contact", "=", JsonPrimitive(false))
        val battery = suggestion("front_door battery low", "front_door", "battery", "<", JsonPrimitive(15))
        val leak = suggestion("hall_leak leak detected", "hall_leak", "water_leak", "=", JsonPrimitive(true))
        assertEquals("contact changes to false", suggestionText(opened))
        assertEquals("battery < 15", suggestionText(battery))
        assertEquals("water_leak changes to true", suggestionText(leak))

        // The route carries the whole draft: name, title, message, operator and trigger, and the value keeps its type
        val action = suggestion("hall_button 1", "hall_button", "action", "=", JsonPrimitive("1"))
        listOf(opened, battery, action).forEach { s ->
            val route = draftRoute(s.toRequest())
            assertTrue(route.startsWith("rules/new?"), route)
            val draft = Regex("[?&]draft=([^&]*)").find(route)!!.groupValues[1]
            assertEquals(s.toRequest(), ruleDraft(URLDecoder.decode(draft, "UTF-8")))
        }
        assertEquals(
            RuleRequest("front_door battery low", "z2m/front_door", battery.conditions, TRIGGER_CHANGES_TO, "front_door", "front_door battery low"),
            battery.toRequest(),
        )
        assertNull(ruleDraft(""))
        assertNull(ruleDraft("not json"))
    }

    // BDD: Rules are suggested for a Zigbee2MQTT device
    @Test
    fun `the fake service offers one device's suggestions`() = runTest {
        val api = FakeHeatingApi().apply {
            suggestions += suggestion("front_door opened", "front_door", "contact", "=", JsonPrimitive(false), setUp = true)
            suggestions += suggestion("hall_leak leak detected", "hall_leak", "water_leak", "=", JsonPrimitive(true))
        }
        assertEquals(listOf("hall_leak leak detected"), api.suggestedRules("hall_leak").map { it.name })
        assertEquals(2, api.suggestedRules().size)
    }
}
