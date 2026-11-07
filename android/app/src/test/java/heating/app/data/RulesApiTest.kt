package heating.app.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RulesApiTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun api(body: String) = KtorHeatingApi(
        ServerConfig("https://heating.example", "secret"),
        MockEngine { request ->
            requests += request
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )

    private val ruleJson = """{"id":"r1","name":"Front door contact false","topic":"z2m/front_door","conditions":[{"field":"contact","operator":"=","value":false},{"field":"battery","operator":"exists"}],"trigger":"changes to","title":"front_door","message":"m","notify":"me","cooldownSeconds":60,"quietHours":{"from":"22:00","to":"07:00","sendSummary":true},"enabled":true,"by":"jane","byUserId":"u1","createdAt":"2026-11-07T10:00:00Z","pausedUntil":"2026-11-07T19:10:00Z","pausedUntilLocal":"19:10"}"""

    private fun path(r: HttpRequestData) = r.url.toString().removePrefix("https://heating.example/")

    // BDD: Rules are managed in the app
    @Test
    fun `rules are read, written, resumed, deleted and their history read by id`() = runTest {
        val rule = api("[$ruleJson]").rules().single()
        assertEquals(RuleCondition("contact", "=", JsonPrimitive(false)), rule.conditions[0])
        assertEquals(null, rule.conditions[1].value)
        assertEquals(QuietHours("22:00", "07:00", true), rule.quietHours)
        assertEquals("19:10", rule.pausedUntilLocal)
        val one = api(ruleJson)
        one.createRule(rule.toRequest())
        one.updateRule("r1", rule.toRequest())
        one.resumeRule("r1")
        one.deleteRule("r1")
        assertEquals(listOf(HttpMethod.Get, HttpMethod.Post, HttpMethod.Put, HttpMethod.Post, HttpMethod.Delete), requests.map { it.method })
        assertEquals(listOf("api/rules", "api/rules", "api/rules/r1", "api/rules/r1/resume", "api/rules/r1"), requests.map(::path))
        val sent = (requests[1].body as TextContent).text
        assertTrue(sent.contains(""""notify":"me"""") && !sent.contains("pausedUntil") && !sent.contains(""""id""""))
        val history = api("""[{"at":"2026-11-07T11:00:00Z","topic":"t","payload":"{}","outcome":"sent","text":"sent to 1 person","recipients":1,"deliveryIds":["d1"]}]""").ruleHistory("r1", 5)
        assertEquals("d1", history.single().deliveryIds.single())
        assertEquals("api/rules/r1/history?limit=5", path(requests.last()))
    }
}
