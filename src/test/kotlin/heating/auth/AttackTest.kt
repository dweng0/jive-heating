package heating.auth

import heating.testing.TEST_TOKEN
import heating.testing.TestHeating
import heating.testing.anonymous
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.delete
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Security tests: each asserts the secure behaviour, so a failure is a vulnerability. */
class AttackTest {

    private suspend fun ApplicationTestBuilder.post(path: String, body: String, token: String? = null, ip: String? = null): HttpResponse =
        anonymous.post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
            token?.let { bearerAuth(it) }
            ip?.let { header("CF-Connecting-IP", it) }
        }

    private suspend fun ApplicationTestBuilder.setup(username: String = "jane", password: String = "correct horse") =
        post("/api/auth/setup", """{"serviceToken":"$TEST_TOKEN","username":"$username","password":"$password"}""")

    private suspend fun ApplicationTestBuilder.login(username: String, password: String, ip: String? = null) =
        post("/api/auth/login", """{"username":"$username","password":"$password"}""", ip = ip)

    private suspend fun ApplicationTestBuilder.tokenOf(response: HttpResponse): String =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.admin(): String = tokenOf(setup())

    private suspend fun ApplicationTestBuilder.createUser(
        adminToken: String,
        username: String,
        password: String = "correct horse",
        admin: Boolean = false,
    ) = post("/api/admin/users", """{"username":"$username","password":"$password","admin":$admin}""", adminToken)

    private suspend fun ApplicationTestBuilder.rooms(token: String) = anonymous.get("/api/rooms") { bearerAuth(token) }.status

    private suspend fun ApplicationTestBuilder.userId(adminToken: String, username: String): String =
        Json.parseToJsonElement(anonymous.get("/api/admin/users") { bearerAuth(adminToken) }.bodyAsText()).jsonArray
            .map { it.jsonObject }
            .single { it["username"]!!.jsonPrimitive.content == username }["id"]!!.jsonPrimitive.content

    private fun hmac(secret: ByteArray, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.toByteArray()))
    }

    // Attack 1: vary the username casing/whitespace to dodge the per-username lock.
    @Test
    fun `username variants share one sign-in lock`() = TestHeating().api {
        createUser(admin(), "sam")
        listOf("sam", " sam", "SAM", "sam ", "Sam").forEach { name ->
            assertEquals(HttpStatusCode.Unauthorized, login(name, "wrong horse").status)
        }
        assertEquals(HttpStatusCode.TooManyRequests, login("sam", "correct horse").status)
        assertEquals(HttpStatusCode.TooManyRequests, login(" SAM ", "correct horse").status)
    }

    // Attack 2: a different CF-Connecting-IP per request must not defeat the per-username lock.
    @Test
    fun `rotating CF-Connecting-IP cannot guess past the per-username lock`() {
        val t = TestHeating()
        t.api {
            createUser(admin(), "sam")
            var accepted = 0
            repeat(25) { i ->
                val response = login("sam", "wrong horse", ip = "203.0.113.${i + 1}")
                if (response.status == HttpStatusCode.OK) accepted++
            }
            assertEquals(0, accepted)
            assertEquals(HttpStatusCode.TooManyRequests, login("sam", "correct horse", ip = "198.51.100.9").status)
        }
    }

    // Attack 3: a member promotes themselves via PATCH on their own id.
    @Test
    fun `a member cannot promote themselves through the admin patch route`() = TestHeating().api {
        val jane = admin()
        createUser(jane, "sam")
        val sam = tokenOf(login("sam", "correct horse"))
        val samId = userId(jane, "sam")
        val response = anonymous.patch("/api/admin/users/$samId") {
            bearerAuth(sam)
            contentType(ContentType.Application.Json)
            setBody("""{"admin":true}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(HttpStatusCode.Forbidden, anonymous.get("/api/admin/users") { bearerAuth(sam) }.status)
    }

    // Attack 4: a token for a deleted user must not work for a new user with the same username.
    @Test
    fun `a token for a deleted user does not work for a recreated user with the same name`() = TestHeating().api {
        val jane = admin()
        createUser(jane, "sam")
        val oldSam = tokenOf(login("sam", "correct horse"))
        anonymous.delete("/api/admin/users/${userId(jane, "sam")}") { bearerAuth(jane) }
        assertEquals(HttpStatusCode.Created, createUser(jane, "sam", password = "battery staple").status)
        assertEquals(HttpStatusCode.Unauthorized, rooms(oldSam))
        assertEquals(HttpStatusCode.Unauthorized, anonymous.get("/api/auth/me") { bearerAuth(oldSam) }.status)
    }

    // Attack 5a: concurrent setup calls must produce exactly one admin.
    @Test
    fun `concurrent setup calls create exactly one admin`() = TestHeating().api {
        val results = coroutineScope {
            (1..8).map { i -> async(Dispatchers.Default) { setup(username = "user$i").status } }.map { it.await() }
        }
        assertEquals(1, results.count { it == HttpStatusCode.Created }, "statuses: $results")
        assertEquals(7, results.count { it == HttpStatusCode.Conflict }, "statuses: $results")
    }

    // Attack 5b: the last admin cannot be deleted, so setup can never be re-run to take over.
    @Test
    fun `setup cannot be run again by deleting the only admin`() = TestHeating().api {
        val jane = admin()
        val id = userId(jane, "jane")
        assertEquals(HttpStatusCode.Conflict, anonymous.delete("/api/admin/users/$id") { bearerAuth(jane) }.status)
        assertEquals(HttpStatusCode.Conflict, setup("eve").status)
        assertEquals(HttpStatusCode.OK, rooms(jane))
    }

    // Attack 6: a huge password must be refused quickly and without a server error.
    @Test
    fun `a one-million character password is refused quickly`() = TestHeating().api {
        val jane = admin()
        createUser(jane, "sam")
        val huge = "a".repeat(1_000_000)
        val started = System.nanoTime()
        val response = login("sam", huge)
        val millis = (System.nanoTime() - started) / 1_000_000
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(millis < 2_000, "login with 1M-char password took ${millis}ms")
        assertEquals(HttpStatusCode.BadRequest, createUser(jane, "kid", password = huge).status)
    }

    // Attack 7: malformed or forged token shapes.
    @Test
    fun `malformed and forged token shapes are rejected`() {
        val t = TestHeating()
        t.api {
            val jane = admin()
            val janeId = userId(jane, "jane")
            assertEquals(HttpStatusCode.OK, rooms(jane))
            val secret = Base64.getDecoder().decode(t.app.store.get("session-secret")!!)
            val expires = t.clock.instant().plusSeconds(86_400).epochSecond

            val extraDot = "$jane.x"
            val emptySignature = jane.substringBefore('.') + "."
            val noSignature = jane.substringBefore('.')
            val fourFields = encodePayload("$janeId:$expires:0:extra").let { "$it.${hmac(secret, it)}" }
            val validSignedThreeFields = encodePayload("$janeId:$expires:0").let { "$it.${hmac(secret, it)}" }
            val nonNumericVersion = encodePayload("$janeId:$expires:zero").let { "$it.${hmac(secret, it)}" }
            val unknownUser = encodePayload("ghost:$expires:0").let { "$it.${hmac(secret, it)}" }
            val swappedDots = "." + jane.substringBefore('.')

            assertEquals(HttpStatusCode.OK, rooms(validSignedThreeFields), "sanity: correctly signed token works")
            for ((name, token) in listOf(
                "extra dot" to extraDot,
                "empty signature" to emptySignature,
                "no signature" to noSignature,
                "four payload fields" to fourFields,
                "non-numeric version" to nonNumericVersion,
                "unknown user id" to unknownUser,
                "leading dot" to swappedDots,
                "empty token" to "",
                "lone dot" to ".",
            )) {
                assertEquals(HttpStatusCode.Unauthorized, rooms(token), "token shape accepted: $name")
            }
        }
    }

    // Attack 8: every /api route except sign-in and setup must refuse an anonymous caller.
    @Test
    fun `api routes refuse anonymous callers`() = TestHeating().api {
        admin()
        val gets = listOf("/api/rooms", "/api/valves", "/api/status", "/api/admin/users", "/api/auth/me", "/openapi.json")
        for (path in gets) {
            assertEquals(HttpStatusCode.Unauthorized, anonymous.get(path).status, "GET $path without token")
        }
        assertEquals(HttpStatusCode.Unauthorized, anonymous.put("/api/zones/z1/mode") {
            contentType(ContentType.Application.Json); setBody("""{"mode":"OFF"}""")
        }.status, "PUT zone mode without token")
        assertEquals(HttpStatusCode.Unauthorized, anonymous.post("/api/auth/password") {
            contentType(ContentType.Application.Json); setBody("""{"current":"correct horse","new":"battery staple"}""")
        }.status, "POST password without token")
        assertEquals(HttpStatusCode.Unauthorized, anonymous.post("/api/auth/logout-all").status, "POST logout-all without token")
    }

    @Test
    fun `unicode lookalikes of a username do not reach that account`() = TestHeating().api {
        createUser(admin(), "sam")
        // U+017F long s and U+0131 dotless i case-fold to ASCII under equalsIgnoreCase.
        assertEquals(HttpStatusCode.Unauthorized, login("\u017Fam", "correct horse").status)
        assertEquals(HttpStatusCode.OK, login("sam", "correct horse").status)
    }

    @Test
    fun `a burst of parallel guesses gets no more than five checked`() = TestHeating().api {
        createUser(admin(), "sam")
        val statuses = coroutineScope {
            (1..30).map { i -> async(Dispatchers.Default) { login("sam", "wrong horse $i", ip = "10.0.0.$i").status } }
                .map { it.await() }
        }
        assertEquals(5, statuses.count { it == HttpStatusCode.Unauthorized }, statuses.toString())
        assertEquals(25, statuses.count { it == HttpStatusCode.TooManyRequests })
    }

    private fun encodePayload(text: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
}
