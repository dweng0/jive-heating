package heating.setup

import heating.Config
import heating.heatingJson
import heating.store.SqliteStore
import heating.testing.FakeBrokers
import heating.testing.Pushed
import heating.testing.TEST_TOKEN
import heating.testing.TestHeating
import io.ktor.client.request.bearerAuth
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConnectionSetupTest {

    private val env: (Config) -> Config = {
        it.copy(
            mqttUrl = "tcp://mosquitto:1883", mqttUsername = "heating", mqttPassword = "secret", z2mBaseTopic = "zigbee2mqtt",
            secretKey = "a long secret key", discoverWait = Duration.ofMillis(300), discoverGrace = Duration.ofMillis(50),
        )
    }

    private val noEnv: (Config) -> Config = {
        it.copy(secretKey = "a long secret key", discoverWait = Duration.ofMillis(300), discoverGrace = Duration.ofMillis(50))
    }

    private fun brokers() = FakeBrokers().also { fb -> fb.broker("mosquitto").login = "heating" to "secret" }

    private suspend fun ApplicationTestBuilder.signIn(name: String = "jane", admin: Boolean = true): String {
        val token: String
        if (name == "jane") {
            val created = client.post("/api/auth/setup") {
                contentType(ContentType.Application.Json)
                setBody("""{"serviceToken":"$TEST_TOKEN","username":"jane","password":"correct horse"}""")
            }
            token = loginToken(if (created.status == HttpStatusCode.Created) created.bodyAsText() else loginText("jane"))
        } else {
            val jane = signIn()
            client.post("/api/admin/users") {
                contentType(ContentType.Application.Json)
                bearerAuth(jane)
                setBody("""{"username":"$name","password":"correct horse","admin":$admin}""")
            }
            token = loginToken(loginText(name))
        }
        return token
    }

    private fun loginToken(text: String) = heatingJson.parseToJsonElement(text).jsonObject["token"]!!.jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.loginText(name: String): String = client.post("/api/auth/login") {
        contentType(ContentType.Application.Json)
        setBody("""{"username":"$name","password":"correct horse"}""")
    }.bodyAsText()

    private suspend fun ApplicationTestBuilder.getJson(path: String, token: String): Pair<HttpStatusCode, String> =
        client.get(path) { bearerAuth(token) }.let { it.status to it.bodyAsText() }

    private suspend fun ApplicationTestBuilder.send(method: String, path: String, body: String, token: String): HttpResponse {
        val block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            contentType(ContentType.Application.Json)
            setBody(body)
            bearerAuth(token)
        }
        return if (method == "PUT") client.put(path, block) else client.post(path, block)
    }

    private fun json(text: String) = heatingJson.parseToJsonElement(text)

    private fun broker(host: String, port: Int = 1883, tls: Boolean = false, user: String? = "heating", password: String? = "secret"): String =
        buildString {
            append("""{"host":"$host","port":$port,"tls":$tls""")
            if (user != null) append(""","username":"$user"""")
            if (password != null) append(""","password":"$password"""")
            append("}")
        }

    // BDD: A new install with no broker asks for connection setup
    @Test
    fun `a new install with no broker asks for connection setup`() {
        val fb = brokers()
        val t = TestHeating(brokers = fb, configure = noEnv)
        t.api {
            val jane = signIn()
            val (status, body) = getJson("/api/setup/status", jane)
            assertEquals(HttpStatusCode.OK, status)
            assertEquals(json("""{"broker":"missing","zigbee2mqtt":"missing","complete":false}"""), json(body))
        }
        // Heating stays off: nothing connected, nothing published.
        assertTrue(fb.attempts.isEmpty())
        t.app.tick()
        assertTrue(fb.broker("mosquitto").published.isEmpty())
    }

    // BDD: An existing install is filled in from the environment
    @Test
    fun `an existing install is filled in from the environment`() {
        val fb = brokers()
        val t = TestHeating(brokers = fb, configure = env)
        t.api {
            val jane = signIn()
            val (_, body) = getJson("/api/setup/status", jane)
            assertEquals(json("""{"broker":"connected","zigbee2mqtt":"configured","complete":true}"""), json(body))
            val view = json(getJson("/api/setup/broker", jane).second).jsonObject
            assertEquals("mosquitto", view["host"]!!.jsonPrimitive.content)
            assertEquals(1883, view["port"]!!.jsonPrimitive.int)
            assertFalse(view["tls"]!!.jsonPrimitive.boolean)
            assertEquals("heating", view["username"]!!.jsonPrimitive.content)
        }
        assertEquals("zigbee2mqtt", t.app.topics.base)
        fb.broker("other", 1883)
        t.restart { it.copy(mqttUrl = "tcp://other:1883", mqttUsername = null, mqttPassword = null, z2mBaseTopic = "elsewhere") }
        t.api {
            val jane = signIn()
            assertEquals("mosquitto", json(getJson("/api/setup/broker", jane).second).jsonObject["host"]!!.jsonPrimitive.content)
        }
        assertEquals("zigbee2mqtt", t.app.topics.base)
        assertEquals(0, fb.broker("other").connections)
    }

    // BDD: An admin tests a broker before saving it
    @Test
    fun `an admin tests a broker before saving it`() {
        val fb = FakeBrokers().also { it.broker("mosquitto").login = "heating" to "secret" }
        val t = TestHeating(brokers = fb, configure = noEnv)
        t.api {
            val jane = signIn()
            val response = send("POST", "/api/setup/broker/test", broker("mosquitto"), jane)
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(json("""{"ok":true}"""), json(response.bodyAsText()))
            assertEquals(1, fb.attempts.size)
            assertEquals(0, fb.broker("mosquitto").connections)
            assertEquals(HttpStatusCode.NotFound, getJson("/api/setup/broker", jane).first)
            assertEquals("missing", json(getJson("/api/setup/status", jane).second).jsonObject["broker"]!!.jsonPrimitive.content)
        }
    }

    // BDD: A broker test explains why it failed
    @Test
    fun `a broker test explains why it failed`() {
        val fb = brokers()
        fb.broker("slow", 1883).down = true
        fb.broker("secure", 8883).tls = true
        val t = TestHeating(brokers = fb, configure = noEnv)
        t.api {
            val jane = signIn()
            suspend fun failure(body: String): JsonObject {
                val response = send("POST", "/api/setup/broker/test", body, jane)
                assertEquals(HttpStatusCode.OK, response.status)
                return json(response.bodyAsText()).jsonObject.also { assertFalse(it["ok"]!!.jsonPrimitive.boolean) }
            }
            fun JsonObject.assertIs(reason: String, message: String) {
                assertEquals(reason, this["reason"]!!.jsonPrimitive.content)
                assertEquals(message, this["message"]!!.jsonPrimitive.content)
            }
            failure(broker("mosquito")).assertIs("unknown_host", "Can't find \"mosquito\". Check the host name.")
            failure(broker("mosquitto", 1884)).assertIs("refused", "Nothing answered on mosquitto:1884.")
            failure(broker("slow")).assertIs("timeout", "slow:1883 didn't answer within 5 seconds.")
            failure(broker("mosquitto", password = "wrong")).assertIs("not_authorized", "The broker turned down that username and password.")
            val tls = failure(broker("secure", 8883, tls = false))
            assertEquals("tls", tls["reason"]!!.jsonPrimitive.content)
            assertTrue(tls["message"]!!.jsonPrimitive.content.startsWith("Couldn't make a secure connection: "))

            val attempts = fb.attempts.size
            for (bad in listOf("""{"host":"","port":1883}""", """{"port":1883}""", """{"host":"mosquitto","port":0}""", """{"host":"mosquitto","port":65536}""", """{"host":"mosquitto"}""")) {
                assertEquals(HttpStatusCode.BadRequest, send("POST", "/api/setup/broker/test", bad, jane).status, bad)
            }
            assertEquals(attempts, fb.attempts.size)
        }
    }

    // BDD: Saving a broker reconnects without a restart
    @Test
    fun `saving a broker reconnects without a restart`() {
        val fb = brokers()
        val mqttLocal = fb.broker("mqtt.local", 8883).also { it.tls = true; it.login = "heating" to "secret" }
        val t = TestHeating(brokers = fb, configure = env)
        val mosquitto = fb.broker("mosquitto")
        t.api {
            val jane = signIn()
            mosquitto.receive("zigbee2mqtt/bridge/devices", """[{"friendly_name":"Lounge TRV 1","type":"EndDevice","definition":{"model":"TRVZB"}}]""", retained = true)
            assertNotNull(t.app.valves["Lounge TRV 1"])
            val had = mosquitto.subscribed
            assertTrue("zigbee2mqtt/bridge/devices" in had)

            // A broker that fails its test is not saved and the old connection keeps running.
            val bad = send("PUT", "/api/setup/broker", broker("mqtt.local", 8883, tls = true, password = "wrong"), jane)
            assertEquals(HttpStatusCode.UnprocessableEntity, bad.status)
            val badBody = json(bad.bodyAsText()).jsonObject
            assertEquals("not_authorized", badBody["reason"]!!.jsonPrimitive.content)
            assertEquals("The broker turned down that username and password.", badBody["error"]!!.jsonPrimitive.content)
            assertEquals(1, mosquitto.connections)
            assertEquals("mosquitto", json(getJson("/api/setup/broker", jane).second).jsonObject["host"]!!.jsonPrimitive.content)

            val ok = send("PUT", "/api/setup/broker", broker("mqtt.local", 8883, tls = true), jane)
            assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
            assertEquals(0, mosquitto.connections)
            assertEquals(1, mqttLocal.connections)
            assertEquals(had.toSet(), mqttLocal.subscribed.toSet())

            // Valves carry on with no restart.
            mqttLocal.receive("zigbee2mqtt/Lounge TRV 1", """{"local_temperature":19.5}""")
            assertEquals(19.5, t.app.valves["Lounge TRV 1"]!!.currentTemperature)
            val activity = t.app.activity.recent(10)
            assertTrue(activity.any { it.kind == "setup" && it.actor == "Jane" && it.value?.content == "broker" }, activity.toString())
        }
    }

    // BDD: Broker passwords are never sent back
    @Test
    fun `broker passwords are never sent back`() {
        val fb = brokers()
        val t = TestHeating(brokers = fb, configure = env)
        t.api {
            val jane = signIn()
            val (_, body) = getJson("/api/setup/broker", jane)
            assertEquals(json("""{"host":"mosquitto","port":1883,"tls":false,"username":"heating","passwordSet":true}"""), json(body))
            assertFalse("secret" in body)

            // No password keeps the saved one.
            val kept = send("PUT", "/api/setup/broker", """{"host":"mosquitto","port":1883,"username":"heating"}""", jane)
            assertEquals(HttpStatusCode.OK, kept.status, kept.bodyAsText())
            assertTrue(json(kept.bodyAsText()).jsonObject["passwordSet"]!!.jsonPrimitive.boolean)

            // The password is stored encrypted.
            val stored = SqliteStore(t.dbPath).get("setup.connection")!!
            assertFalse("secret" in stored)
            assertTrue("enc:v1:" in stored)

            // "" clears it, and this broker wants one.
            val cleared = send("PUT", "/api/setup/broker", """{"host":"mosquitto","port":1883,"username":"heating","password":""}""", jane)
            assertEquals(HttpStatusCode.UnprocessableEntity, cleared.status)

            // Members get 403 on every setup route.
            val sam = signIn("sam", admin = false)
            for (path in listOf("/api/setup/status", "/api/setup/broker", "/api/setup/zigbee2mqtt/discover")) {
                assertEquals(HttpStatusCode.Forbidden, getJson(path, sam).first, path)
            }
            for ((method, path) in listOf("POST" to "/api/setup/broker/test", "PUT" to "/api/setup/broker", "PUT" to "/api/setup/zigbee2mqtt", "POST" to "/api/setup/test-notification", "POST" to "/api/setup/complete")) {
                assertEquals(HttpStatusCode.Forbidden, send(method, path, broker("mosquitto"), sam).status, path)
            }
        }
        // The service refuses to start when the key is missing or wrong.
        assertFailsWith<IllegalStateException> { t.restart { it.copy(secretKey = null) } }
        assertFailsWith<IllegalStateException> { t.restart { it.copy(secretKey = "another key") } }
    }

    private fun bridge(fb: FakeBrokers, base: String, version: String, devices: Int = 14, state: String = """{"state":"online"}""") {
        val broker = fb.broker("mosquitto")
        broker.receive("$base/bridge/info", """{"version":"$version"}""", retained = true)
        broker.receive("$base/bridge/devices", JsonArray(List(devices) { heatingJson.parseToJsonElement("""{"friendly_name":"Device $it","type":"EndDevice"}""") }).toString(), retained = true)
        broker.receive("$base/bridge/state", state, retained = true)
    }

    // BDD: Zigbee2MQTT is found on the broker
    @Test
    fun `zigbee2mqtt is found on the broker`() {
        val fb = brokers()
        val t = TestHeating(brokers = fb, configure = env)
        bridge(fb, "z2m", "2.1.0")
        t.api {
            val jane = signIn()
            val found = getJson("/api/setup/zigbee2mqtt/discover", jane)
            assertEquals(HttpStatusCode.OK, found.first)
            assertEquals(json("""[{"baseTopic":"z2m","version":"2.1.0","devices":14}]"""), json(found.second))

            val saved = send("PUT", "/api/setup/zigbee2mqtt", """{"baseTopic":"z2m"}""", jane)
            assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
            val subscribed = fb.broker("mosquitto").subscribed
            assertTrue("z2m/bridge/devices" in subscribed, subscribed.toString())
            assertFalse(subscribed.any { it.startsWith("zigbee2mqtt/") }, subscribed.toString())
            fb.broker("mosquitto").receive("z2m/bridge/devices", """[{"friendly_name":"Hall TRV","type":"EndDevice","definition":{"model":"TRVZB"}}]""", retained = true)
            assertNotNull(t.app.valves["Hall TRV"])
            assertEquals("z2m/Hall TRV/set", t.app.topics.set("Hall TRV"))
        }
        t.restart()
        assertEquals("z2m", t.app.topics.base)
    }

    // BDD: Zigbee2MQTT is not found on the broker
    @Test
    fun `zigbee2mqtt is not found on the broker`() {
        val fb = brokers()
        val t = TestHeating(brokers = fb, configure = env)
        t.api {
            val jane = signIn()
            assertEquals(json("[]"), json(getJson("/api/setup/zigbee2mqtt/discover", jane).second))

            val missing = send("PUT", "/api/setup/zigbee2mqtt", """{"baseTopic":"nowhere"}""", jane)
            assertEquals(HttpStatusCode.UnprocessableEntity, missing.status)
            assertEquals("Zigbee2MQTT at nowhere is offline", json(missing.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content)

            fb.broker("mosquitto").receive("down/bridge/state", """{"state":"offline"}""", retained = true)
            assertEquals(HttpStatusCode.UnprocessableEntity, send("PUT", "/api/setup/zigbee2mqtt", """{"baseTopic":"down"}""", jane).status)
            assertEquals("zigbee2mqtt", t.app.topics.base)

            // Plain "online" (older Zigbee2MQTT) counts too, and two bridges are both offered.
            fb.broker("mosquitto").receive("typed/bridge/state", "online", retained = true)
            assertEquals(HttpStatusCode.OK, send("PUT", "/api/setup/zigbee2mqtt", """{"baseTopic":"typed"}""", jane).status)
            bridge(fb, "z2m", "2.1.0", 3)
            bridge(fb, "attic", "1.9.0", 2)
            val both = json(getJson("/api/setup/zigbee2mqtt/discover", jane).second).jsonArray
            assertEquals(listOf("attic", "z2m"), both.map { it.jsonObject["baseTopic"]!!.jsonPrimitive.content })
        }
    }

    // BDD: Setup ends with a test notification
    @Test
    fun `setup ends with a test notification`() {
        val fb = brokers()
        val t = TestHeating(brokers = fb, configure = noEnv)
        t.api {
            val jane = signIn()
            bridge(fb, "zigbee2mqtt", "2.1.0")
            assertEquals(HttpStatusCode.OK, send("PUT", "/api/setup/broker", broker("mosquitto"), jane).status)
            assertEquals(HttpStatusCode.OK, send("PUT", "/api/setup/zigbee2mqtt", """{"baseTopic":"zigbee2mqtt"}""", jane).status)
            assertEquals("""{"broker":"connected","zigbee2mqtt":"configured","complete":false}""", json(getJson("/api/setup/status", jane).second).toString())

            val none = send("POST", "/api/setup/test-notification", "", jane)
            assertEquals(json("""{"sent":false,"registered":false}"""), json(none.bodyAsText()))
            assertTrue(t.pushed.isEmpty())

            client.put("/api/me/devices/jane-phone") { bearerAuth(jane) }
            val sent = send("POST", "/api/setup/test-notification", "", jane)
            assertEquals(json("""{"sent":true,"registered":true}"""), json(sent.bodyAsText()))
            assertEquals(listOf(Pushed("jane-phone", "Test notification", "Notifications from your home are working")), t.pushed)

            assertEquals(HttpStatusCode.NoContent, send("POST", "/api/setup/complete", "", jane).status)
            assertEquals(json("""{"broker":"connected","zigbee2mqtt":"configured","complete":true}"""), json(getJson("/api/setup/status", jane).second))
        }
    }

    // BDD: The broker connection drops after setup
    @Test
    fun `the broker connection drops after setup`() {
        val fb = brokers()
        val t = TestHeating(brokers = fb, configure = env)
        val mosquitto = fb.broker("mosquitto")
        val before = mosquitto.subscribed
        assertTrue(before.isNotEmpty())
        t.api {
            val jane = signIn()
            val dropped = t.clock.instant()
            mosquitto.drop("connection reset")
            val down = json(getJson("/api/setup/status", jane).second).jsonObject
            assertEquals("disconnected", down["broker"]!!.jsonPrimitive.content)
            assertEquals(dropped.toString(), down["since"]!!.jsonPrimitive.content)
            assertEquals("connection reset", down["reason"]!!.jsonPrimitive.content)

            // Backoff 1, 2, 4 seconds and so on, never more than 60.
            var tries = fb.attempts.size
            fun advance(seconds: Long): Int {
                t.clock.advance(Duration.ofSeconds(seconds))
                t.app.tick()
                return (fb.attempts.size - tries).also { tries = fb.attempts.size }
            }
            assertEquals(0, advance(0))
            assertEquals(1, advance(1))
            assertEquals(0, advance(1))
            assertEquals(1, advance(1))
            assertEquals(0, advance(3))
            assertEquals(1, advance(1))
            repeat(8) { advance(60) }
            assertEquals(1, advance(60))
            assertEquals(0, advance(59))
            assertEquals("disconnected", json(getJson("/api/setup/status", jane).second).jsonObject["broker"]!!.jsonPrimitive.content)

            // Published while it was down: gone, not replayed.
            t.app.bus.publish("heating/demand", "on", retained = true)
            mosquitto.down = false
            assertEquals(1, advance(1))
            assertEquals(1, mosquitto.connections)
            assertEquals(before.toSet(), mosquitto.subscribed.toSet())
            assertTrue(mosquitto.published.none { it.topic == "heating/demand" && it.payload == "on" })
            val up = json(getJson("/api/setup/status", jane).second).jsonObject
            assertEquals("connected", up["broker"]!!.jsonPrimitive.content)
            assertFalse("since" in up)
        }
    }
}
