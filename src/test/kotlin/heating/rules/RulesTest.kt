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
import java.time.Instant
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

    private suspend fun ApplicationTestBuilder.history(token: String, id: String): JsonArray {
        val response = send("GET", "/api/rules/$id/history", token)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return heatingJson.parseToJsonElement(response.bodyAsText()).jsonArray
    }

    private fun JsonArray.outcomes() = map { it.jsonObject["outcome"]!!.jsonPrimitive.content }

    // BDD: An "is" rule fires on every matching message
    @Test
    fun `an is rule fires on every matching message`() {
        val t = TestHeating()
        t.api {
            val tokens = household()
            val jane = tokens.getValue("jane")
            val id = createRule(
                jane,
                rule(name = "Button pressed", topic = "z2m/hall_button", conditions = """[{"field":"action","operator":"=","value":"single"}]""",
                    trigger = "is", title = "Button", message = "Hall button pressed", cooldown = 60),
            )["id"]!!.jsonPrimitive.content
            t.bus.receive("z2m/hall_button", """{"action":"single"}""")
            t.clock.advance(Duration.ofMinutes(2))
            t.bus.receive("z2m/hall_button", """{"action":"single"}""")
            assertEquals(6, t.pushed.size) // two notifications, each to three people

            // a message inside the cooldown does not send, and is counted as skipped by cooldown
            t.clock.advance(Duration.ofSeconds(10))
            t.bus.receive("z2m/hall_button", """{"action":"single"}""")
            assertEquals(6, t.pushed.size)
            t.bus.receive("z2m/hall_button", """{"action":"double"}""")
            val entries = history(jane, id)
            assertEquals(listOf("skipped by cooldown", "sent", "sent"), entries.outcomes())
            assertEquals(HttpStatusCode.Forbidden, send("GET", "/api/rules/$id/history", tokens.getValue("sam")).status)
        }
    }

    // BDD: A wildcard rule tracks each topic on its own
    @Test
    fun `a wildcard rule tracks each topic on its own`() {
        val t = TestHeating()
        t.api {
            val jane = household().getValue("jane")
            createRule(jane, rule(name = "A door opened", topic = "z2m/+"))
            t.bus.receive("z2m/front_door", """{"contact":false}""")
            assertEquals(3, t.pushed.size)
            t.bus.receive("z2m/back_door", """{"contact":false}""")
            assertEquals(6, t.pushed.size) // its own notification, though the front door's cooldown is running

            // the cooldown is kept per topic: the front door again inside it is held back
            t.bus.receive("z2m/front_door", """{"contact":true}""")
            t.bus.receive("z2m/front_door", """{"contact":false}""")
            assertEquals(6, t.pushed.size)
            t.clock.advance(Duration.ofSeconds(61))
            t.bus.receive("z2m/back_door", """{"contact":true}""")
            t.bus.receive("z2m/back_door", """{"contact":false}""")
            assertEquals(9, t.pushed.size)

            // a topic with no contact field never matches, nor does text or another depth
            t.bus.receive("z2m/hall_motion", """{"occupancy":true}""")
            t.bus.receive("z2m/bridge_state", "online")
            t.bus.receive("z2m/deeper/front_door", """{"contact":false}""")
            assertEquals(9, t.pushed.size)
        }
    }

    // BDD: The message fills in from the event
    @Test
    fun `the message fills in from the event`() {
        val t = TestHeating()
        t.api {
            val jane = household().getValue("jane")
            createRule(jane, rule(name = "Opened", title = "{{topic.last}}", message = "{{topic.last}} opened, battery {{payload.battery}}%"))
            t.bus.receive(frontDoor, """{"contact":false,"battery":91}""")
            assertEquals(setOf("front_door"), t.pushed.map { it.title }.toSet())
            assertEquals(setOf("front_door opened, battery 91%"), t.pushed.map { it.body }.toSet())
        }
    }

    // BDD: The message fills in from the event
    @Test
    fun `a zigbee topic offers device and a missing field fills in as a question mark`() {
        val t = TestHeating()
        t.api {
            val jane = household().getValue("jane")
            createRule(
                jane,
                rule(topic = "zigbee2mqtt/Front Door", title = "{{device}}", message = "{{payload.battery}}% {{payload.nothing.here}} {{ payload.contact }} {{topic}}", notify = "me"),
            )
            t.bus.receive("zigbee2mqtt/Front Door", """{"contact":false,"battery":91}""")
            assertEquals(1, t.pushed.size)
            assertEquals("Front Door", t.pushed.single().title)
            assertEquals("91% ? false zigbee2mqtt/Front Door", t.pushed.single().body)
            // a topic outside Zigbee2MQTT has no device
            createRule(jane, rule(topic = "other/x", conditions = "[]", title = "{{device}}", notify = "me"))
            t.bus.receive("other/x", "{}")
            assertEquals("?", t.pushed.last().title)
        }
    }

    // BDD: The message fills in from the event
    @Test
    fun `the api refuses a template with an unknown placeholder`() {
        val t = TestHeating()
        t.api {
            val jane = household().getValue("jane")
            assertRefused(jane, rule(title = "{{nope}}"), "title")
            assertRefused(jane, rule(message = "Door {{payload.}} {{colour}}"), "message")
            assertRefused(jane, rule(message = "Door {{topic"), "message")
            createRule(jane, rule(title = "{{topic}} {{topic.last}} {{device}} {{payload}} {{value}} {{payload.a.b}}"))
        }
    }

    // BDD: A payload that is not JSON can still match
    @Test
    fun `a payload that is not JSON can still match`() {
        val t = TestHeating()
        t.api {
            val jane = household().getValue("jane")
            val id = createRule(
                jane,
                rule(name = "Garage cold", topic = "garage/temp", conditions = """[{"field":"value","operator":"<","value":5}]""", title = "Garage", message = "Cold: {{value}}", notify = "me"),
            )["id"]!!.jsonPrimitive.content
            t.bus.receive("garage/temp", "4.5")
            assertEquals(1, t.pushed.size)
            assertEquals("Cold: 4.5", t.pushed.single().body)
            assertEquals(listOf("sent"), history(jane, id).outcomes())
            t.bus.receive("garage/temp", "7")
            assertEquals(1, t.pushed.size)

            // a JSON rule on a text payload never matches, and logs nothing
            val json = createRule(jane, rule(topic = "garage/door", notify = "me"))["id"]!!.jsonPrimitive.content
            t.bus.receive("garage/door", "open")
            t.bus.receive("garage/door", "[1,2]")
            assertEquals(1, t.pushed.size)
            assertEquals(0, history(jane, json).size)
        }
    }

    // BDD: A payload that is not JSON can still match
    @Test
    fun `text payloads compare as text and an exists condition needs the field`() {
        val t = TestHeating()
        t.api {
            val jane = household().getValue("jane")
            createRule(jane, rule(name = "Online", topic = "bridge/state", conditions = """[{"field":"value","operator":"=","value":"online"}]""", notify = "me", trigger = "is"))
            createRule(jane, rule(name = "Has battery", topic = "dev/+", conditions = """[{"field":"battery","operator":"exists"},{"field":"contact","operator":"≠","value":true}]""", notify = "me", trigger = "is"))
            t.bus.receive("bridge/state", "offline")
            assertEquals(0, t.pushed.size)
            t.bus.receive("bridge/state", "online")
            assertEquals(1, t.pushed.size)
            t.bus.receive("dev/a", """{"contact":false}""")
            assertEquals(1, t.pushed.size)
            t.bus.receive("dev/a", """{"contact":false,"battery":50}""")
            assertEquals(2, t.pushed.size)
            t.bus.receive("dev/a", """{"contact":true,"battery":50}""")
            assertEquals(2, t.pushed.size)
        }
    }

    private val quiet = ""","quietHours":{"from":"23:00","to":"07:00"}"""
    private val quietSummary = ""","quietHours":{"from":"23:00","to":"07:00","sendSummary":true}"""

    /** Opens and closes the front door: a close (contact true) then an open (contact false). */
    private fun TestHeating.doorOpens() {
        bus.receive(frontDoor, """{"contact":true}""")
        bus.receive(frontDoor, """{"contact":false}""")
    }

    // BDD: Quiet hours hold back a rule
    @Test
    fun `quiet hours hold back a rule`() {
        val t = TestHeating()
        t.api {
            val jane = household().getValue("jane")
            val id = createRule(jane, rule(cooldown = 0, extra = quiet))["id"]!!.jsonPrimitive.content
            t.clock.set(Instant.parse("2026-10-12T01:00:00Z")) // 02:00 in London
            t.doorOpens()
            assertEquals(0, t.pushed.size)
            assertEquals(listOf("skipped by quiet hours"), history(jane, id).outcomes())
            t.clock.advance(Duration.ofHours(5)) // 07:00
            t.app.tick()
            assertEquals(0, t.pushed.size) // not sent afterwards either
            t.doorOpens()
            assertEquals(3, t.pushed.size)
            // 22:59 is not quiet, 23:00 is
            t.clock.set(Instant.parse("2026-10-12T21:59:00Z"))
            t.doorOpens()
            assertEquals(6, t.pushed.size)
            t.clock.set(Instant.parse("2026-10-12T22:00:00Z"))
            t.doorOpens()
            assertEquals(6, t.pushed.size)
        }
    }

    // BDD: Quiet hours hold back a rule
    @Test
    fun `a rule can send one summary when quiet hours end`() {
        val t = TestHeating()
        t.api {
            val jane = household().getValue("jane")
            val id = createRule(jane, rule(cooldown = 0, extra = quietSummary))["id"]!!.jsonPrimitive.content
            t.clock.set(Instant.parse("2026-10-12T01:00:00Z"))
            t.doorOpens()
            t.clock.advance(Duration.ofHours(1))
            t.doorOpens()
            t.clock.advance(Duration.ofHours(1))
            t.doorOpens()
            t.app.tick()
            assertEquals(0, t.pushed.size)
            t.clock.set(Instant.parse("2026-10-12T05:59:00Z")) // 06:59
            t.app.tick()
            assertEquals(0, t.pushed.size)
            t.clock.set(Instant.parse("2026-10-12T06:00:00Z")) // 07:00
            t.app.tick()
            assertEquals(3, t.pushed.size)
            assertEquals(setOf("Front door opened 3 times overnight"), t.pushed.map { it.body }.toSet())
            t.clock.advance(Duration.ofMinutes(1))
            t.app.tick()
            assertEquals(3, t.pushed.size)
            val entries = history(jane, id)
            assertEquals(listOf("summary sent", "held until quiet hours end", "held until quiet hours end", "held until quiet hours end"), entries.outcomes())
            assertEquals("summary sent to 3 people", entries.first().jsonObject["text"]!!.jsonPrimitive.content)
        }
    }

    // BDD: Quiet hours hold back a rule
    @Test
    fun `quiet hours that do not cross midnight and a bad window`() {
        val t = TestHeating()
        t.api {
            val jane = household().getValue("jane")
            createRule(jane, rule(cooldown = 0, notify = "me", extra = ""","quietHours":{"from":"13:00","to":"15:00"}"""))
            t.clock.set(Instant.parse("2026-10-12T12:30:00Z")) // 13:30 in London
            t.doorOpens()
            assertEquals(0, t.pushed.size)
            t.clock.set(Instant.parse("2026-10-12T14:00:00Z")) // 15:00
            t.doorOpens()
            assertEquals(1, t.pushed.size)
            assertRefused(jane, rule(extra = ""","quietHours":{"from":"25:00","to":"07:00"}"""), "quietHours")
            assertRefused(jane, rule(extra = ""","quietHours":{"from":"07:00","to":"07:00"}"""), "quietHours")
        }
    }

    private fun JsonObject.text(key: String) = this[key]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content

    private suspend fun ApplicationTestBuilder.getRule(token: String, id: String): JsonObject {
        val response = send("GET", "/api/rules/$id", token)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return heatingJson.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    // BDD: A noisy rule is held back
    @Test
    fun `a noisy rule is held back`() {
        val t = TestHeating()
        t.api {
            val tokens = household()
            val jane = tokens.getValue("jane")
            val id = createRule(
                jane,
                rule(name = "Motion", topic = "z2m/hall_motion", conditions = """[{"field":"occupancy","operator":"=","value":true}]""", trigger = "is",
                    title = "Motion", message = "Movement in the hall", cooldown = 0),
            )["id"]!!.jsonPrimitive.content
            repeat(30) {
                t.bus.receive("z2m/hall_motion", """{"occupancy":true}""")
                t.clock.advance(Duration.ofSeconds(10))
            }
            assertEquals(90, t.pushed.size) // 30 notifications to three people
            assertEquals(null, getRule(jane, id).text("pausedUntil"))

            // the 31st would be the next: it is stopped, and only the rule's owner hears of it
            t.bus.receive("z2m/hall_motion", """{"occupancy":true}""")
            assertEquals(91, t.pushed.size)
            assertEquals("Motion paused", t.pushed.last().title)
            assertEquals("It fired 30 times in 10 minutes", t.pushed.last().body)
            assertEquals("jane-phone", t.pushed.last().token)
            assertEquals("paused", history(jane, id).first().jsonObject["outcome"]!!.jsonPrimitive.content)

            // paused for an hour from 10:05 London time
            val shown = getRule(jane, id)
            assertEquals("2026-10-12T10:05:00Z", shown.text("pausedUntil"))
            assertEquals("11:05", shown.text("pausedUntilLocal"))
            t.clock.advance(Duration.ofMinutes(30))
            t.bus.receive("z2m/hall_motion", """{"occupancy":true}""")
            assertEquals(91, t.pushed.size)
            assertEquals(listOf("paused", "paused"), history(jane, id).outcomes().take(2))

            // "Resume now"
            val resumed = send("POST", "/api/rules/$id/resume", jane)
            assertEquals(HttpStatusCode.OK, resumed.status, resumed.bodyAsText())
            assertEquals(null, getRule(jane, id).text("pausedUntil"))
            t.bus.receive("z2m/hall_motion", """{"occupancy":true}""")
            assertEquals(94, t.pushed.size)
            assertEquals(HttpStatusCode.Forbidden, send("POST", "/api/rules/$id/resume", tokens.getValue("sam")).status)
            assertEquals(HttpStatusCode.NotFound, send("POST", "/api/rules/nope/resume", jane).status)
        }
    }

    // BDD: A noisy rule is held back
    @Test
    fun `the pause ends by itself after an hour and a slower rule is never paused`() {
        val t = TestHeating()
        t.api {
            val jane = household().getValue("jane")
            val motion = rule(name = "Motion", topic = "z2m/hall_motion", conditions = "[]", trigger = "is", title = "Motion", notify = "me", cooldown = 0)
            val id = createRule(jane, motion)["id"]!!.jsonPrimitive.content
            repeat(31) { t.bus.receive("z2m/hall_motion", "{}") }
            assertEquals(31, t.pushed.size) // 30 sent and the pause notice
            assertEquals("Motion paused", t.pushed.last().title)
            t.clock.advance(Duration.ofMinutes(59))
            t.bus.receive("z2m/hall_motion", "{}")
            assertEquals(31, t.pushed.size)
            t.clock.advance(Duration.ofMinutes(2))
            assertEquals(null, getRule(jane, id).text("pausedUntil"))
            t.bus.receive("z2m/hall_motion", "{}")
            assertEquals(32, t.pushed.size)

            // 30 in 10 minutes is the limit: one every 21 seconds is not it
            val slow = createRule(jane, motion.replace("hall_motion", "kitchen_motion"))["id"]!!.jsonPrimitive.content
            repeat(60) {
                t.bus.receive("z2m/kitchen_motion", "{}")
                t.clock.advance(Duration.ofSeconds(21))
            }
            assertEquals(null, getRule(jane, slow).text("pausedUntil"))
            assertEquals(92, t.pushed.size)
        }
    }

    /** A service whose event log is written when asked, so tests decide when it is drained. */
    private fun serviceWithEvents() = TestHeating(configure = { it.copy(eventFlushMillis = 0) })

    /** 25 front door messages over 7 days, oldest first: contact true, false, false, true, true, five times over. */
    private fun TestHeating.sevenDaysOfFrontDoor() {
        val contacts = listOf(true, false, false, true, true)
        repeat(25) { i ->
            bus.receive(frontDoor, """{"contact":${contacts[i % 5]},"battery":${90 - i}}""")
            app.events.flush()
            clock.advance(Duration.ofHours(6))
        }
        bus.receive("z2m/back_door", """{"contact":false}""")
        app.events.flush()
    }

    // BDD: An admin tests a rule against recent events
    @Test
    fun `an admin tests a rule against recent events`() {
        val t = serviceWithEvents()
        t.api {
            val tokens = household()
            val jane = tokens.getValue("jane")
            t.sevenDaysOfFrontDoor()
            t.pushed.clear()
            val response = send("POST", "/api/rules/test", jane, rule(title = "{{topic.last}}", message = "Door opened, battery {{payload.battery}}%"))
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val events = heatingJson.parseToJsonElement(response.bodyAsText()).jsonObject["events"]!!.jsonArray.map { it.jsonObject }
            assertEquals(20, events.size)
            assertEquals(setOf(frontDoor), events.map { it["topic"]!!.jsonPrimitive.content }.toSet())
            // newest first: the last five were contact true, false, false, true, true (oldest to newest), then the same again
            val results = events.map { it["result"]!!.jsonPrimitive.content }
            assertEquals(
                List(4) { listOf("conditions not met: contact is true", "conditions not met: contact is true", "no change", "would send", "conditions not met: contact is true") }.flatten(),
                results,
            )
            val sending = events.filter { it["result"]!!.jsonPrimitive.content == "would send" }
            assertEquals(4, sending.size)
            assertEquals("front_door", sending.first()["title"]!!.jsonPrimitive.content)
            assertEquals("Door opened, battery ${90 - 21}%", sending.first()["message"]!!.jsonPrimitive.content)
            assertEquals(
                listOf("not_met", "not_met", "no_change", "would_send", "not_met"),
                events.take(5).map { it["status"]!!.jsonPrimitive.content },
            )
            // testing sends nothing and writes no history
            assertEquals(0, t.pushed.size)
            assertEquals(HttpStatusCode.Forbidden, send("POST", "/api/rules/test", tokens.getValue("sam"), rule()).status)
            assertEquals(HttpStatusCode.BadRequest, send("POST", "/api/rules/test", jane, rule(topic = "")).status)
        }
    }

    // BDD: An admin tests a rule against recent events
    @Test
    fun `send me a test sends the filled in notification to the admin only`() {
        val t = serviceWithEvents()
        t.api {
            val tokens = household()
            t.sevenDaysOfFrontDoor()
            val response = send("POST", "/api/rules/test/send", tokens.getValue("jane"), rule(title = "{{topic.last}}", message = "Battery {{payload.battery}}%"))
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertEquals(1, t.pushed.size)
            assertEquals("jane-phone", t.pushed.single().token)
            assertEquals("front_door", t.pushed.single().title)
            assertEquals("Battery 66%", t.pushed.single().body) // the latest front door message
            val body = heatingJson.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("front_door", body["title"]!!.jsonPrimitive.content)
            assertEquals(1, body["deliveryIds"]!!.jsonArray.size)
            assertEquals(HttpStatusCode.Forbidden, send("POST", "/api/rules/test/send", tokens.getValue("sam"), rule()).status)
            assertEquals(HttpStatusCode.BadRequest, send("POST", "/api/rules/test/send", TEST_TOKEN, rule()).status)
        }
    }

    // BDD: A rule's history shows what it did
    @Test
    fun `a rules history shows what it did`() {
        val t = TestHeating()
        t.api {
            val tokens = household()
            val jane = tokens.getValue("jane")
            val id = createRule(jane, rule(cooldown = 3600, extra = quiet))["id"]!!.jsonPrimitive.content
            val other = createRule(jane, rule(name = "Other", topic = "z2m/other", notify = "me"))["id"]!!.jsonPrimitive.content
            t.clock.set(Instant.parse("2026-10-05T09:00:00Z")) // 10:00, a week before the end
            repeat(7) { day ->
                t.bus.receive(frontDoor, """{"contact":true}""")
                t.bus.receive(frontDoor, """{"contact":false,"day":$day}""") // sent
                t.clock.advance(Duration.ofMinutes(10))
                t.bus.receive(frontDoor, """{"contact":true}""")
                t.bus.receive(frontDoor, """{"contact":false,"day":$day,"again":true}""") // inside the cooldown
                t.clock.advance(Duration.ofHours(13)) // 23:10, quiet
                t.bus.receive(frontDoor, """{"contact":true}""")
                t.bus.receive(frontDoor, """{"contact":false,"day":$day,"late":true}""")
                t.clock.advance(Duration.ofHours(10).plusMinutes(50)) // back at 10:00 next day
            }
            t.bus.receive("z2m/other", """{"contact":false}""")
            val entries = history(jane, id)
            assertEquals(21, entries.size)
            assertEquals(listOf("skipped by quiet hours", "skipped by cooldown", "sent"), entries.outcomes().take(3))
            // newest first, each with its time, topic and payload
            val newest = entries[0].jsonObject
            assertEquals(frontDoor, newest["topic"]!!.jsonPrimitive.content)
            assertEquals("""{"contact":false,"day":6,"late":true}""", newest["payload"]!!.jsonPrimitive.content)
            assertEquals("2026-10-11T22:10:00Z", newest["at"]!!.jsonPrimitive.content)
            assertEquals(entries.map { it.jsonObject["at"]!!.jsonPrimitive.content }.sortedDescending(), entries.map { it.jsonObject["at"]!!.jsonPrimitive.content })
            val sent = entries[2].jsonObject
            assertEquals("sent to 3 people", sent["text"]!!.jsonPrimitive.content)
            assertEquals(3, sent["recipients"]!!.jsonPrimitive.int)
            // each sent notification links to its delivery, which links back
            val deliveryIds = sent["deliveryIds"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(3, deliveryIds.size)
            deliveryIds.forEach { delivery ->
                val view = heatingJson.parseToJsonElement(send("GET", "/api/deliveries/$delivery", jane).bodyAsText()).jsonObject
                assertEquals(id, view["ruleId"]!!.jsonPrimitive.content)
                assertEquals("Front door opened", view["source"]!!.jsonPrimitive.content)
            }
            assertEquals(listOf("skipped by quiet hours", "skipped by cooldown", "sent"), JsonArray(entries.takeLast(3)).outcomes())
            // another rule has its own history, and a limit trims it
            assertEquals(listOf("sent"), history(jane, other).outcomes())
            val limited = heatingJson.parseToJsonElement(send("GET", "/api/rules/$id/history?limit=2", jane).bodyAsText()).jsonArray
            assertEquals(2, limited.size)
            assertEquals(HttpStatusCode.NotFound, send("GET", "/api/rules/nope/history", jane).status)
            assertEquals(HttpStatusCode.Forbidden, send("GET", "/api/rules/$id/history", tokens.getValue("sam")).status)

            // old entries are dropped after 30 days
            t.clock.advance(Duration.ofDays(31))
            t.app.tick()
            assertEquals(0, history(jane, id).size)
        }
    }

    // BDD: Rules are managed in the app
    @Test
    fun `the rules list shows when each rule last sent`() {
        val t = TestHeating()
        t.api {
            val jane = household().getValue("jane")
            val id = createRule(jane, rule())["id"]!!.jsonPrimitive.content
            createRule(jane, rule(name = "Quiet one", topic = "z2m/other", notify = "me"))
            suspend fun lastSent(rule: String) = listRules(jane).first { it.jsonObject["id"]!!.jsonPrimitive.content == rule || it.jsonObject["name"]!!.jsonPrimitive.content == rule }
                .jsonObject["lastSentAt"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content
            assertEquals(null, lastSent(id))
            t.clock.set(Instant.parse("2026-10-05T09:00:00Z"))
            t.bus.receive(frontDoor, """{"contact":true}""")
            t.bus.receive(frontDoor, """{"contact":false}""")
            assertEquals("2026-10-05T09:00:00Z", lastSent(id))
            assertEquals("2026-10-05T09:00:00Z", heatingJson.parseToJsonElement(send("GET", "/api/rules/$id", jane).bodyAsText()).jsonObject["lastSentAt"]!!.jsonPrimitive.content)
            // a rule that has not sent shows none, and a skipped match is not a send
            assertEquals(null, lastSent("Quiet one"))
            t.clock.advance(Duration.ofSeconds(10))
            t.bus.receive(frontDoor, """{"contact":true}""")
            t.bus.receive(frontDoor, """{"contact":false}""") // inside the cooldown
            assertEquals("2026-10-05T09:00:00Z", lastSent(id))
        }
    }

    private val bridgeDevices = """[
        {"friendly_name":"Coordinator","type":"Coordinator","definition":null},
        {"friendly_name":"front_door","type":"EndDevice","definition":{"exposes":[
            {"type":"binary","property":"contact","name":"contact"},
            {"type":"numeric","property":"battery","name":"battery"}]}},
        {"friendly_name":"hall_leak","type":"EndDevice","definition":{"exposes":[{"type":"binary","property":"water_leak","name":"water_leak"}]}},
        {"friendly_name":"landing","type":"EndDevice","definition":{"exposes":[
            {"type":"binary","property":"occupancy"},{"type":"binary","property":"tamper"}]}},
        {"friendly_name":"hall_button","type":"EndDevice","definition":{"exposes":[
            {"type":"composite","features":[{"type":"enum","property":"action","values":["single","double"]}]}]}}
    ]"""

    private suspend fun ApplicationTestBuilder.suggestions(token: String, device: String? = null): List<JsonObject> =
        heatingJson.parseToJsonElement(send("GET", "/api/rules/suggestions" + (device?.let { "?device=$it" } ?: ""), token).bodyAsText())
            .jsonArray.map { it.jsonObject }

    // BDD: Rules are suggested for a Zigbee2MQTT device
    @Test
    fun `rules are suggested for each zigbee2mqtt device from what it exposes`() {
        val t = TestHeating(configure = { it.copy(z2mBaseTopic = "z2m") })
        t.api {
            val tokens = household()
            val jane = tokens.getValue("jane")
            assertEquals(emptyList(), suggestions(jane))
            t.bus.receive("z2m/bridge/devices", bridgeDevices, retained = true)

            val all = suggestions(jane)
            assertEquals(
                listOf(
                    "front_door opened", "front_door battery low", "hall_leak leak detected",
                    "landing motion detected", "landing tampered with", "hall_button single", "hall_button double",
                ),
                all.map { it["name"]!!.jsonPrimitive.content },
            )
            val opened = all.first()
            assertEquals("z2m/front_door", opened["topic"]!!.jsonPrimitive.content)
            assertEquals("front_door", opened["device"]!!.jsonPrimitive.content)
            assertEquals("changes to", opened["trigger"]!!.jsonPrimitive.content)
            assertEquals("front_door", opened["title"]!!.jsonPrimitive.content)
            assertEquals("front_door opened", opened["message"]!!.jsonPrimitive.content)
            assertEquals(heatingJson.parseToJsonElement("""[{"field":"contact","operator":"=","value":false}]"""), opened["conditions"])
            assertEquals(false, opened["setUp"]!!.jsonPrimitive.boolean)
            val battery = all[1]
            assertEquals(heatingJson.parseToJsonElement("""[{"field":"battery","operator":"<","value":15}]"""), battery["conditions"])
            assertEquals("front_door battery is low ({{payload.battery}}%)", battery["message"]!!.jsonPrimitive.content)
            assertEquals(heatingJson.parseToJsonElement("""[{"field":"water_leak","operator":"=","value":true}]"""), all[2]["conditions"])
            assertEquals(heatingJson.parseToJsonElement("""[{"field":"action","operator":"=","value":"double"}]"""), all[6]["conditions"])
            assertEquals("hall_button: double", all[6]["message"]!!.jsonPrimitive.content)

            // one device's only, and a rule like it shows as set up
            createRule(jane, rule(name = "Door", topic = "z2m/front_door"))
            val door = suggestions(jane, "front_door")
            assertEquals(listOf("front_door opened", "front_door battery low"), door.map { it["name"]!!.jsonPrimitive.content })
            assertEquals(listOf(true, false), door.map { it["setUp"]!!.jsonPrimitive.boolean })
            assertEquals(emptyList(), suggestions(jane, "nope"))
            assertEquals(HttpStatusCode.Forbidden, send("GET", "/api/rules/suggestions", tokens.getValue("sam")).status)
        }
    }
}
