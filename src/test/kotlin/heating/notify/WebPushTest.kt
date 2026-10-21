package heating.notify

import heating.testing.TEST_TOKEN
import heating.testing.TestHeating
import heating.testing.anonymous
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

const val CONTACT = "mailto:owner@example.invalid"

fun webPushOn() = TestHeating(configure = { it.copy(webPushContact = CONTACT) })

/** Sets up admin "jane" with the service token and signs in [username] (made a member if not jane); returns their bearer token. */
suspend fun ApplicationTestBuilder.signIn(username: String = "jane"): String {
    suspend fun post(path: String, body: String, token: String? = null) = anonymous.post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
        token?.let { bearerAuth(it) }
    }.bodyAsText()
    val setup = post("/api/auth/setup", """{"serviceToken":"$TEST_TOKEN","username":"jane","password":"correct horse"}""")
    val jane = Json.parseToJsonElement(setup).jsonObject["token"]?.jsonPrimitive?.content
        ?: Json.parseToJsonElement(post("/api/auth/login", """{"username":"jane","password":"correct horse"}""")).jsonObject["token"]!!.jsonPrimitive.content
    if (username == "jane") return jane
    post("/api/admin/users", """{"username":"$username","password":"correct horse"}""", jane)
    val login = post("/api/auth/login", """{"username":"$username","password":"correct horse"}""")
    return Json.parseToJsonElement(login).jsonObject["token"]!!.jsonPrimitive.content
}

suspend fun ApplicationTestBuilder.webPushInfo(token: String): JsonObject =
    Json.parseToJsonElement(anonymous.get("/api/me/webpush") { bearerAuth(token) }.bodyAsText()).jsonObject

/** A browser's subscription JSON as `PushSubscription.toJSON()` gives it, with real keys unless overridden. */
fun subscription(
    endpoint: String,
    p256dh: String = base64Url(P256.uncompressed(KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public as ECPublicKey)),
    auth: String = base64Url(Random.nextBytes(16)),
) = """{"endpoint":"$endpoint","expirationTime":null,"keys":{"p256dh":"$p256dh","auth":"$auth"}}"""

fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

suspend fun ApplicationTestBuilder.subscribe(token: String, json: String): HttpResponse = anonymous.put("/api/me/webpush") {
    bearerAuth(token)
    contentType(ContentType.Application.Json)
    setBody(json)
}

suspend fun ApplicationTestBuilder.unsubscribe(token: String, endpoint: String): HttpResponse = anonymous.delete("/api/me/webpush") {
    bearerAuth(token)
    contentType(ContentType.Application.Json)
    setBody("""{"endpoint":"$endpoint"}""")
}

class WebPushTest {

    // BDD: The service gives browsers its push key
    @Test
    fun `the service gives browsers its push key`() {
        val heating = webPushOn()
        var first = ""
        heating.api {
            val body = webPushInfo(signIn())
            assertEquals("true", body["webPush"]!!.jsonPrimitive.content)
            first = body["publicKey"]!!.jsonPrimitive.content
            assertIsP256Point(first)
        }
        heating.restart()
        heating.api {
            assertEquals(first, webPushInfo(signIn())["publicKey"]!!.jsonPrimitive.content)
        }

        TestHeating().api {
            val response = anonymous.get("/api/me/webpush") { bearerAuth(signIn()) }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("""{"webPush":false}""", response.bodyAsText())
        }
    }

    // BDD: A browser subscribes to notifications
    @Test
    fun `a browser subscribes to notifications`() {
        val heating = webPushOn()
        heating.api {
            val john = signIn("john")
            val johnId = heating.app.users.list().single { it.username == "john" }.id
            val endpoint = "https://web.push.apple.com/QGuQyavXutnMHy-aSi1iv"
            assertEquals(HttpStatusCode.NoContent, subscribe(john, subscription(endpoint)).status)
            assertEquals(listOf(endpoint), heating.app.webPush.subscriptionsFor(johnId).map { it.endpoint })
            // Subscribing again (the browser re-sends on every start) keeps one subscription.
            subscribe(john, subscription(endpoint))
            assertEquals(1, heating.app.webPush.subscriptionsFor(johnId).size)

            // Someone else cannot remove john's subscription.
            assertEquals(HttpStatusCode.NoContent, unsubscribe(signIn(), endpoint).status)
            assertEquals(1, heating.app.webPush.subscriptionsFor(johnId).size)
            assertEquals(HttpStatusCode.NoContent, unsubscribe(john, endpoint).status)
            assertEquals(emptyList(), heating.app.webPush.subscriptionsFor(johnId))

            // Signing out on that device: the web app sends its endpoint with the sign-out.
            val cookie = anonymous.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"john","password":"correct horse","web":true}""")
            }.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
            subscribe(john, subscription(endpoint))
            val other = "https://fcm.googleapis.com/fcm/send/other-device"
            subscribe(john, subscription(other))
            val logout = anonymous.post("/api/auth/logout") {
                header(HttpHeaders.Cookie, cookie)
                header("X-Heat-Client", "web")
                contentType(ContentType.Application.Json)
                setBody("""{"webPushEndpoint":"$endpoint"}""")
            }
            assertEquals(HttpStatusCode.NoContent, logout.status)
            assertEquals(listOf(other), heating.app.webPush.subscriptionsFor(johnId).map { it.endpoint })
        }
        // Subscriptions survive a restart.
        heating.restart()
        assertEquals(1, heating.app.webPush.subscriptionsFor(heating.app.users.list().single { it.username == "john" }.id).size)
    }

    // BDD: Only real push services are accepted as subscriptions
    @Test
    fun `only real push services are accepted as subscriptions`() {
        val heating = webPushOn()
        heating.api {
            val john = signIn("john")
            val johnId = heating.app.users.list().single { it.username == "john" }.id
            val refused = listOf(
                "http://web.push.apple.com/abc",
                "https://evil.example/abc",
                "https://web.push.apple.com.evil.example/abc",
                "https://evil.example/fcm.googleapis.com/abc",
                "https://notify.windows.com.evil.example/abc",
                "https://evilnotify.windows.com/abc",
                "https://fcm.googleapis.com:8443/fcm/send/abc",
                "https://user@fcm.googleapis.com/fcm/send/abc",
                "https://127.0.0.1/abc",
                "ftp://updates.push.services.mozilla.com/abc",
                "not a url",
            )
            for (endpoint in refused) {
                assertEquals(HttpStatusCode.BadRequest, subscribe(john, subscription(endpoint)).status, endpoint)
            }
            val good = "https://fcm.googleapis.com/fcm/send/abc"
            val badKeys = listOf(
                subscription(good, p256dh = base64Url(ByteArray(65).also { it[0] = 4 })), // not on the curve
                subscription(good, p256dh = base64Url(ByteArray(33).also { it[0] = 2 })), // compressed
                subscription(good, auth = base64Url(Random.nextBytes(15))),
                subscription(good, auth = "not+base64url/at=all"),
            )
            for (json in badKeys) assertEquals(HttpStatusCode.BadRequest, subscribe(john, json).status, json)
            assertEquals(emptyList(), heating.app.webPush.subscriptionsFor(johnId))

            val accepted = listOf(
                "https://web.push.apple.com/QGuQyavXutnMHy",
                "https://fcm.googleapis.com/fcm/send/abc:APA91b",
                "https://updates.push.services.mozilla.com/wpush/v2/gAAAAA",
                "https://wns2-par02p.notify.windows.com/w/?token=BQYAAAB",
            )
            for (endpoint in accepted) {
                assertEquals(HttpStatusCode.NoContent, subscribe(john, subscription(endpoint)).status, endpoint)
            }
            assertEquals(accepted.toSet(), heating.app.webPush.subscriptionsFor(johnId).map { it.endpoint }.toSet())
        }
    }

    /** [key] is base64url with no padding, 65 bytes, an uncompressed point that lies on P-256. */
    private fun assertIsP256Point(key: String) {
        assertTrue(!key.contains('=') && !key.contains('+') && !key.contains('/'), key)
        val bytes = Base64.getUrlDecoder().decode(key)
        assertEquals(65, bytes.size)
        assertEquals(4, bytes[0].toInt())
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        val point = ECPoint(BigInteger(1, bytes.copyOfRange(1, 33)), BigInteger(1, bytes.copyOfRange(33, 65)))
        // Throws if the point is not on the curve.
        KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
    }
}
