package heating.rules

import heating.heatingJson
import heating.testing.TEST_TOKEN
import heating.testing.TestHeating
import heating.testing.anonymous
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RulesTest {

    private suspend fun ApplicationTestBuilder.send(method: String, path: String, token: String, body: String? = null): HttpResponse {
        val block: HttpRequestBuilder.() -> Unit = {
            bearerAuth(token)
            body?.let { contentType(ContentType.Application.Json); setBody(it) }
        }
        return when (method) {
            "GET" -> client.get(path, block)
            "POST" -> client.post(path, block)
            "PUT" -> client.put(path, block)
            else -> client.delete(path, block)
        }
    }

    private suspend fun ApplicationTestBuilder.tokenOf(response: HttpResponse): String =
        heatingJson.parseToJsonElement(response.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content

    /** Admin "jane" and "john", member "sam", each with a phone "<name>-phone". Returns their tokens. */
    private suspend fun ApplicationTestBuilder.household(): Map<String, String> {
        val jane = tokenOf(client.post("/api/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody("""{"serviceToken":"$TEST_TOKEN","username":"jane","password":"correct horse"}""")
        })
        send("POST", "/api/admin/users", jane, """{"username":"john","password":"correct horse","admin":true}""")
        send("POST", "/api/admin/users", jane, """{"username":"sam","password":"correct horse","admin":false}""")
        val tokens = mapOf("jane" to jane) + listOf("john", "sam").associateWith { name ->
            tokenOf(client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"$name","password":"correct horse"}""")
            })
        }
        tokens.forEach { (name, token) -> send("PUT", "/api/me/devices/$name-phone", token) }
        return tokens
    }

    private suspend fun ApplicationTestBuilder.createRule(token: String, body: String): JsonObject {
        val response = send("POST", "/api/rules", token, body)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return heatingJson.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private suspend fun ApplicationTestBuilder.listRules(token: String): JsonArray =
        heatingJson.parseToJsonElement(send("GET", "/api/rules", token).bodyAsText()).jsonArray

    private fun rule(
        name: String = "Front door opened",
        topic: String = "z2m/front_door",
        conditions: String = """[{"field":"contact","operator":"=","value":false}]""",
        trigger: String = "changes to",
        title: String = "Front door",
        message: String = "Front door opened",
        notify: String = "everyone",
        cooldown: Int = 60,
        extra: String = "",
    ) = """{"name":"$name","topic":"$topic","conditions":$conditions,"trigger":"$trigger","title":"$title","message":"$message","notify":"$notify","cooldownSeconds":$cooldown$extra}"""

    private suspend fun ApplicationTestBuilder.assertRefused(token: String, body: String, field: String) {
        val response = send("POST", "/api/rules", token, body)
        assertEquals(HttpStatusCode.BadRequest, response.status, body)
        assertTrue(field in response.bodyAsText(), "${response.bodyAsText()} should name $field")
    }

    // BDD: An admin creates a rule
    @Test
    fun `an admin creates a rule`() {
        val t = TestHeating()
        t.api {
            val jane = household().getValue("jane")
            val created = createRule(jane, rule())
            val id = created["id"]!!.jsonPrimitive.content
            assertTrue(id.isNotBlank())
            assertEquals("Front door opened", created["name"]!!.jsonPrimitive.content)
            assertEquals("z2m/front_door", created["topic"]!!.jsonPrimitive.content)
            assertEquals(true, created["enabled"]!!.jsonPrimitive.boolean)
            assertEquals("jane", created["by"]!!.jsonPrimitive.content)
            assertEquals("changes to", created["trigger"]!!.jsonPrimitive.content)
            assertEquals("everyone", created["notify"]!!.jsonPrimitive.content)
            assertEquals(60, created["cooldownSeconds"]!!.jsonPrimitive.int)
            val condition = created["conditions"]!!.jsonArray.single().jsonObject
            assertEquals("contact", condition["field"]!!.jsonPrimitive.content)
            assertEquals("=", condition["operator"]!!.jsonPrimitive.content)
            assertEquals("false", condition["value"]!!.jsonPrimitive.content)

            // listed, replaced, removed
            assertEquals(listOf(id), listRules(jane).map { it.jsonObject["id"]!!.jsonPrimitive.content })
            val replaced = send("PUT", "/api/rules/$id", jane, rule(name = "Door", cooldown = 0, extra = ""","enabled":false"""))
            assertEquals(HttpStatusCode.OK, replaced.status, replaced.bodyAsText())
            val after = heatingJson.parseToJsonElement(replaced.bodyAsText()).jsonObject
            assertEquals("Door", after["name"]!!.jsonPrimitive.content)
            assertEquals(false, after["enabled"]!!.jsonPrimitive.boolean)
            assertEquals("jane", after["by"]!!.jsonPrimitive.content)
            assertEquals(id, after["id"]!!.jsonPrimitive.content)

            // kept over a restart
            t.restart()
            assertEquals(listOf("Door"), listRules(jane).map { it.jsonObject["name"]!!.jsonPrimitive.content })

            assertEquals(HttpStatusCode.NoContent, send("DELETE", "/api/rules/$id", jane).status)
            assertEquals(0, listRules(jane).size)
            assertEquals(HttpStatusCode.NotFound, send("DELETE", "/api/rules/$id", jane).status)
            assertEquals(HttpStatusCode.NotFound, send("GET", "/api/rules/nope", jane).status)
            assertEquals(HttpStatusCode.NotFound, send("PUT", "/api/rules/nope", jane, rule()).status)
        }
    }

    // BDD: An admin creates a rule
    @Test
    fun `a rule must be filled in properly`() {
        val t = TestHeating()
        t.api {
            val jane = household().getValue("jane")
            assertRefused(jane, rule(name = " "), "name")
            assertRefused(jane, rule(topic = ""), "topic")
            assertRefused(jane, rule(topic = "z2m/#/door"), "topic")
            assertRefused(jane, rule(topic = "z2m/fr+nt"), "topic")
            assertRefused(jane, rule(title = ""), "title")
            assertRefused(jane, rule(cooldown = -1), "cooldown")
            assertRefused(jane, rule(cooldown = 86401), "cooldown")
            assertRefused(jane, rule(notify = "nobody"), "notify")
            assertRefused(jane, rule(trigger = "sometimes"), "trigger")
            assertRefused(jane, rule(conditions = """[{"field":"","operator":"=","value":1}]"""), "field")
            assertRefused(jane, rule(conditions = """[{"field":"a","operator":"~","value":1}]"""), "operator")
            assertRefused(jane, rule(conditions = """[{"field":"a","operator":"="}]"""), "value")
            // the edges are fine
            createRule(jane, rule(cooldown = 0))
            createRule(jane, rule(cooldown = 86400, topic = "z2m/#"))
        }
    }

    // BDD: An admin creates a rule
    @Test
    fun `members get 403 on rules and an anonymous caller 401`() {
        val t = TestHeating()
        t.api {
            val tokens = household()
            val sam = tokens.getValue("sam")
            val id = createRule(tokens.getValue("jane"), rule())["id"]!!.jsonPrimitive.content
            assertEquals(HttpStatusCode.Forbidden, send("GET", "/api/rules", sam).status)
            assertEquals(HttpStatusCode.Forbidden, send("POST", "/api/rules", sam, rule()).status)
            assertEquals(HttpStatusCode.Forbidden, send("PUT", "/api/rules/$id", sam, rule()).status)
            assertEquals(HttpStatusCode.Forbidden, send("DELETE", "/api/rules/$id", sam).status)
            assertEquals(HttpStatusCode.Unauthorized, anonymous.get("/api/rules").status)
        }
    }

    private val frontDoor = "z2m/front_door"

    // BDD: A rule fires when a message changes to match
    @Test
    fun `a rule fires when a message changes to match`() {
        val t = TestHeating()
        t.api {
            val jane = household().getValue("jane")
            createRule(jane, rule())
            t.bus.receive(frontDoor, """{"contact":true,"battery":92}""")
            assertEquals(0, t.pushed.size)
            t.bus.receive(frontDoor, """{"contact":false,"battery":92}""")
            // everyone: jane, john and sam each get it on their phone
            assertEquals(3, t.pushed.size)
            assertEquals(setOf("Front door"), t.pushed.map { it.title }.toSet())
            assertEquals(setOf("Front door opened"), t.pushed.map { it.body }.toSet())
            t.bus.receive(frontDoor, """{"contact":false,"battery":91}""")
            assertEquals(3, t.pushed.size)

            // true then false again inside the cooldown is held back; after it, it sends
            t.bus.receive(frontDoor, """{"contact":true,"battery":91}""")
            t.bus.receive(frontDoor, """{"contact":false,"battery":91}""")
            assertEquals(3, t.pushed.size)
            t.clock.advance(Duration.ofSeconds(61))
            t.bus.receive(frontDoor, """{"contact":true,"battery":91}""")
            t.bus.receive(frontDoor, """{"contact":false,"battery":91}""")
            assertEquals(6, t.pushed.size)
        }
    }

    // BDD: A rule fires when a message changes to match
    @Test
    fun `a disabled rule and other topics send nothing and a retained message does not fire`() {
        val t = TestHeating()
        t.api {
            val jane = household().getValue("jane")
            val id = createRule(jane, rule(extra = ""","enabled":false"""))["id"]!!.jsonPrimitive.content
            t.bus.receive(frontDoor, """{"contact":false}""")
            assertEquals(0, t.pushed.size)
            send("PUT", "/api/rules/$id", jane, rule())
            t.bus.receive("z2m/back_door", """{"contact":false}""")
            t.bus.receive(frontDoor, """{"contact":false}""", retained = true)
            assertEquals(0, t.pushed.size)
            t.bus.receive(frontDoor, """{"contact":false}""")
            assertEquals(3, t.pushed.size)
        }
    }
}
