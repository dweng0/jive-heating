package heating.auth

import heating.testing.MutableClock
import heating.testing.TEST_TOKEN
import heating.testing.TestHeating
import heating.testing.anonymous
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sessions: each sign-in on a device, which an admin can see and end. */
class SessionsTest {
    private val start = Instant.parse("2026-11-05T10:00:00Z")
    private val chromeOnAndroid =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    private val firefoxOnWindows = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0"

    private suspend fun ApplicationTestBuilder.post(path: String, body: String, token: String? = null, agent: String? = null): HttpResponse =
        anonymous.post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
            token?.let { bearerAuth(it) }
            agent?.let { header(HttpHeaders.UserAgent, it) }
        }

    private suspend fun ApplicationTestBuilder.tokenOf(response: HttpResponse): String =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.jane(): String =
        tokenOf(post("/api/auth/setup", """{"serviceToken":"$TEST_TOKEN","username":"jane","password":"correct horse"}"""))

    private suspend fun ApplicationTestBuilder.signIn(username: String, agent: String? = null): String =
        tokenOf(post("/api/auth/login", """{"username":"$username","password":"correct horse"}""", agent = agent))

    private suspend fun ApplicationTestBuilder.addUser(jane: String, username: String) =
        post("/api/admin/users", """{"username":"$username","password":"correct horse","admin":false}""", jane)

    private suspend fun ApplicationTestBuilder.users(jane: String): List<JsonObject> =
        Json.parseToJsonElement(anonymous.get("/api/admin/users") { bearerAuth(jane) }.bodyAsText()).jsonArray.map { it.jsonObject }

    private suspend fun ApplicationTestBuilder.idOf(jane: String, username: String): String =
        users(jane).single { it.text("username") == username }.text("id")!!

    private suspend fun ApplicationTestBuilder.sessions(jane: String, userId: String): List<JsonObject> =
        Json.parseToJsonElement(anonymous.get("/api/admin/users/$userId/sessions") { bearerAuth(jane) }.bodyAsText()).jsonArray.map { it.jsonObject }

    private suspend fun ApplicationTestBuilder.rooms(token: String) = anonymous.get("/api/rooms") { bearerAuth(token) }.status

    private fun JsonObject.text(key: String): String? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    // BDD: A sign-in starts a session the admin can see
    @Test
    fun `a sign-in starts a session the admin can see`() {
        val t = TestHeating(clock = MutableClock(start))
        t.api {
            val jane = jane()
            addUser(jane, "sam")
            val token = signIn("sam", chromeOnAndroid)
            val sam = idOf(jane, "sam")
            val session = sessions(jane, sam).single()
            assertNotNull(session.text("id"))
            assertEquals("Chrome on Android", session.text("device"))
            assertEquals(start.toString(), Instant.parse(session.text("signedInAt")!!).toString())
            assertNotNull(session.text("lastSeen"))
            assertNull(session.text("signedOutAt"))
            // No token, password or address.
            val raw = anonymous.get("/api/admin/users/$sam/sessions") { bearerAuth(jane) }.bodyAsText()
            assertFalse(token in raw || token.substringBefore('.') in raw)
            assertFalse("correct horse" in raw || "127.0.0.1" in raw || "localhost" in raw)
            // The Android app.
            signIn("sam", "Ktor client")
            assertEquals(setOf("Chrome on Android", "Android app"), sessions(jane, sam).map { it.text("device") }.toSet())
            // A member cannot list sessions.
            assertEquals(HttpStatusCode.Forbidden, anonymous.get("/api/admin/users/$sam/sessions") { bearerAuth(token) }.status)
        }
    }

    // BDD: Each person shows whether they are active
    @Test
    fun `each person shows whether they are active`() {
        val clock = MutableClock(start)
        val t = TestHeating(clock = clock)
        t.api {
            val jane = jane()
            addUser(jane, "sam")
            addUser(jane, "kim")
            val kim = signIn("kim")
            assertEquals(HttpStatusCode.NoContent, post("/api/auth/logout", "{}", kim).status)
            clock.advance(Duration.ofDays(3))
            val sam = signIn("sam")
            clock.advance(Duration.ofMinutes(2))
            rooms(sam)
            clock.advance(Duration.ofMinutes(2))
            val after = users(jane).associateBy { it.text("username") }
            assertEquals("true", after.getValue("sam").text("activeNow"))
            assertEquals(start.plus(Duration.ofDays(3)).plus(Duration.ofMinutes(2)), Instant.parse(after.getValue("sam").text("lastSeen")!!))
            assertEquals("1", after.getValue("sam").text("sessions"))
            assertEquals("false", after.getValue("kim").text("activeNow"))
            assertEquals(start, Instant.parse(after.getValue("kim").text("lastSeen")!!))
            assertEquals("0", after.getValue("kim").text("sessions"))
            // Five minutes and more is not active now.
            clock.advance(Duration.ofMinutes(3))
            assertEquals("false", users(jane).single { it.text("username") == "sam" }.text("activeNow"))
        }
    }

    // BDD: Last seen is kept without writing on every request
    @Test
    fun `last seen is kept without writing on every request`() {
        val clock = MutableClock(start)
        val t = TestHeating(clock = clock)
        var sam = ""
        var jane = ""
        t.api {
            jane = jane()
            addUser(jane, "sam")
            sam = signIn("sam")
        }
        val writes = t.app.users.sessionWrites
        t.api {
            repeat(100) {
                clock.advance(Duration.ofMillis(500))
                rooms(sam)
            }
        }
        // 50 seconds of requests: at most one write for sam's session (and one for jane's admin calls).
        assertTrue(t.app.users.sessionWrites - writes <= 2, "${t.app.users.sessionWrites - writes} writes")
        clock.advance(Duration.ofSeconds(20))
        t.api { rooms(sam) }
        val seen = clock.instant()
        t.restart()
        t.api {
            val session = sessions(jane, idOf(jane, "sam")).single()
            assertEquals(seen, Instant.parse(session.text("lastSeen")!!))
        }
    }

    // BDD: Signing out ends only that session
    @Test
    fun `signing out ends only that session`() {
        val t = TestHeating(clock = MutableClock(start))
        t.api {
            val jane = jane()
            addUser(jane, "sam")
            val phone = signIn("sam", chromeOnAndroid)
            val laptop = signIn("sam", firefoxOnWindows)
            assertEquals(HttpStatusCode.NoContent, post("/api/auth/logout", "{}", phone).status)
            assertEquals(HttpStatusCode.Unauthorized, rooms(phone))
            assertEquals(HttpStatusCode.OK, rooms(laptop))
            val sessions = sessions(jane, idOf(jane, "sam")).associateBy { it.text("device") }
            assertNotNull(sessions.getValue("Chrome on Android").text("signedOutAt"))
            assertNull(sessions.getValue("Firefox on Windows").text("signedOutAt"))
        }
    }

    // BDD: An admin signs a device out
    @Test
    fun `an admin signs a device out`() {
        val t = TestHeating(clock = MutableClock(start))
        t.api {
            val jane = jane()
            addUser(jane, "sam")
            val phone = signIn("sam", chromeOnAndroid)
            val laptop = signIn("sam", firefoxOnWindows)
            val sam = idOf(jane, "sam")
            val phoneSession = sessions(jane, sam).single { it.text("device") == "Chrome on Android" }.text("id")!!
            assertEquals(HttpStatusCode.Forbidden, anonymous.delete("/api/admin/users/$sam/sessions/$phoneSession") { bearerAuth(laptop) }.status)
            assertEquals(HttpStatusCode.NoContent, anonymous.delete("/api/admin/users/$sam/sessions/$phoneSession") { bearerAuth(jane) }.status)
            assertEquals(HttpStatusCode.Unauthorized, rooms(phone))
            assertEquals(HttpStatusCode.OK, rooms(laptop))
            assertEquals(HttpStatusCode.NotFound, anonymous.delete("/api/admin/users/$sam/sessions/nope") { bearerAuth(jane) }.status)
            // Everywhere.
            val again = signIn("sam")
            assertEquals(HttpStatusCode.NoContent, anonymous.delete("/api/admin/users/$sam/sessions") { bearerAuth(jane) }.status)
            assertEquals(HttpStatusCode.Unauthorized, rooms(laptop))
            assertEquals(HttpStatusCode.Unauthorized, rooms(again))
            assertEquals("0", users(jane).single { it.text("username") == "sam" }.text("sessions"))
            assertEquals(HttpStatusCode.OK, rooms(jane))
        }
    }

    // BDD: Ended sessions are listed until they are old
    @Test
    fun `ended sessions are listed until they are old`() {
        val clock = MutableClock(start)
        val t = TestHeating(clock = clock)
        t.api {
            val jane = jane()
            addUser(jane, "sam")
            // A sign-in that will expire after 180 days.
            signIn("sam", firefoxOnWindows)
            clock.advance(Duration.ofDays(170))
            val phone = signIn("sam", chromeOnAndroid)
            post("/api/auth/logout", "{}", phone)
            clock.advance(Duration.ofDays(11))
            val jane2 = signIn("jane")
            val listed = sessions(jane2, idOf(jane2, "sam")).associateBy { it.text("device") }
            assertEquals(start.plus(Duration.ofDays(170)), Instant.parse(listed.getValue("Chrome on Android").text("signedOutAt")!!))
            assertEquals(start.plus(Duration.ofDays(180)), Instant.parse(listed.getValue("Firefox on Windows").text("signedOutAt")!!))
            // More than 30 days after it ended it is gone.
            clock.advance(Duration.ofDays(20))
            val jane3 = signIn("jane")
            assertEquals(listOf("Firefox on Windows"), sessions(jane3, idOf(jane3, "sam")).map { it.text("device") })
        }
    }

    // BDD: Signing in and out is in the activity feed
    @Test
    fun `signing in and out is in the activity feed`() {
        val t = TestHeating(clock = MutableClock(start))
        t.api {
            val jane = jane()
            addUser(jane, "sam")
            val phone = signIn("sam", chromeOnAndroid)
            post("/api/auth/logout", "{}", phone)
            signIn("sam", firefoxOnWindows)
            post("/api/auth/login", """{"username":"sam","password":"wrong horse"}""")
            val sam = idOf(jane, "sam")
            val laptop = sessions(jane, sam).single { it.text("signedOutAt") == null }.text("id")!!
            anonymous.delete("/api/admin/users/$sam/sessions/$laptop") { bearerAuth(jane) }

            val events = Json.parseToJsonElement(anonymous.get("/api/activity") { bearerAuth(jane) }.bodyAsText()).jsonObject["events"]!!
                .jsonArray.map { it.jsonObject }.filter { it.text("kind") == "session" }.reversed()
            assertEquals(
                listOf(
                    Triple("Jane", "in", null), // setup
                    Triple("Sam", "in", "Chrome on Android"),
                    Triple("Sam", "out", null),
                    Triple("Sam", "in", "Firefox on Windows"),
                    Triple("Sam", "out", "Jane"),
                ),
                events.map { Triple(it.text("actor"), it.text("value"), it.text("reason")) }.map {
                    if (it.second == "in" && it.first == "Jane") it.copy(third = null) else it
                },
            )
            // Members never see them.
            val samToken = signIn("sam")
            val seen = Json.parseToJsonElement(anonymous.get("/api/activity") { bearerAuth(samToken) }.bodyAsText()).jsonObject["events"]!!
                .jsonArray.map { it.jsonObject }
            assertTrue(seen.none { it.text("kind") == "session" })
        }
    }
}
