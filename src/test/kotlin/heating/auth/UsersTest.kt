package heating.auth

import heating.testing.TEST_TOKEN
import heating.testing.TestHeating
import heating.testing.anonymous
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UsersTest {

    private suspend fun ApplicationTestBuilder.post(path: String, body: String, token: String? = null): HttpResponse =
        anonymous.post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
            token?.let { bearerAuth(it) }
        }

    private suspend fun ApplicationTestBuilder.setup(username: String = "jane", password: String = "correct horse") =
        post("/api/auth/setup", """{"serviceToken":"$TEST_TOKEN","username":"$username","password":"$password"}""")

    private suspend fun ApplicationTestBuilder.login(username: String, password: String) =
        post("/api/auth/login", """{"username":"$username","password":"$password"}""")

    private suspend fun ApplicationTestBuilder.tokenOf(response: HttpResponse): String =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content

    /** Admin "jane" via setup; returns jane's session token. */
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

    // BDD: Setup is required while there are no users
    @Test
    fun `setup is required while there are no users`() = TestHeating().api {
        assertEquals("""{"setupRequired":true}""", anonymous.get("/api/auth/status").bodyAsText())
    }

    // BDD: First admin is created with the service token
    @Test
    fun `first admin is created with the service token`() = TestHeating().api {
        val response = setup()
        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["token"]!!.jsonPrimitive.content.isNotBlank())
        assertEquals("jane", body["user"]!!.jsonObject["username"]!!.jsonPrimitive.content)
        assertEquals("true", body["user"]!!.jsonObject["admin"]!!.jsonPrimitive.content)
        assertEquals("""{"setupRequired":false}""", anonymous.get("/api/auth/status").bodyAsText())
    }

    // BDD: Setup is refused once a user exists
    @Test
    fun `setup is refused once a user exists`() = TestHeating().api {
        setup()
        assertEquals(HttpStatusCode.Conflict, setup("eve").status)
    }

    // BDD: Setup is refused with the wrong service token
    @Test
    fun `setup is refused with the wrong service token`() = TestHeating().api {
        val response = post("/api/auth/setup", """{"serviceToken":"nope","username":"jane","password":"correct horse"}""")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("""{"setupRequired":true}""", anonymous.get("/api/auth/status").bodyAsText())
    }

    // BDD: A user signs in and uses the API with their session token
    @Test
    fun `a user signs in and uses the api with their session token`() = TestHeating().api {
        createUser(admin(), "sam")
        val response = login("sam", "correct horse")
        assertEquals(HttpStatusCode.OK, response.status)
        val token = tokenOf(response)
        assertEquals(HttpStatusCode.OK, rooms(token))
        val me = Json.parseToJsonElement(anonymous.get("/api/auth/me") { bearerAuth(token) }.bodyAsText()).jsonObject
        assertEquals("sam", me["username"]!!.jsonPrimitive.content)
        assertEquals("false", me["admin"]!!.jsonPrimitive.content)
    }

    // BDD: Usernames are matched without regard to case
    @Test
    fun `usernames are matched without regard to case`() = TestHeating().api {
        createUser(admin(), "Sam")
        assertEquals(HttpStatusCode.OK, login("sam", "correct horse").status)
    }

    // BDD: Wrong password and unknown user get the same answer
    @Test
    fun `wrong password and unknown user get the same answer`() = TestHeating().api {
        createUser(admin(), "sam")
        val wrong = login("sam", "wrong horse")
        val unknown = login("nobody", "wrong horse")
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals(HttpStatusCode.Unauthorized, unknown.status)
        assertEquals("""{"error":"wrong username or password"}""", wrong.bodyAsText())
        assertEquals(wrong.bodyAsText(), unknown.bodyAsText())
    }

    // BDD: Sign-in is throttled after five failures for one username
    @Test
    fun `sign-in is throttled after five failures for one username`() = TestHeating().api {
        createUser(admin(), "sam")
        repeat(5) { assertEquals(HttpStatusCode.Unauthorized, login("sam", "wrong horse").status) }
        val response = login("sam", "correct horse")
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("60", response.headers["Retry-After"])
    }

    // BDD: The sign-in lock doubles with each further failure up to an hour
    @Test
    fun `the sign-in lock doubles with each further failure up to an hour`() {
        val t = TestHeating()
        t.api {
            createUser(admin(), "sam")
            repeat(5) { login("sam", "wrong horse") }
            val locks = mutableListOf<Int>()
            repeat(8) {
                val locked = login("sam", "wrong horse")
                assertEquals(HttpStatusCode.TooManyRequests, locked.status)
                val wait = locked.headers["Retry-After"]!!.toInt()
                locks += wait
                t.clock.advance(Duration.ofSeconds(wait.toLong()))
                login("sam", "wrong horse")
            }
            assertEquals(listOf(60, 120, 240, 480, 960, 1920, 3600, 3600), locks)
        }
    }

    // BDD: A successful sign-in clears the failure count
    @Test
    fun `a successful sign-in clears the failure count`() = TestHeating().api {
        createUser(admin(), "sam")
        repeat(4) { login("sam", "wrong horse") }
        assertEquals(HttpStatusCode.OK, login("sam", "correct horse").status)
        repeat(4) { login("sam", "wrong horse") }
        assertEquals(HttpStatusCode.OK, login("sam", "correct horse").status)
    }

    // BDD: Sign-in is throttled per address across usernames
    @Test
    fun `sign-in is throttled per address across usernames`() = TestHeating().api {
        createUser(admin(), "sam")
        repeat(20) { i -> login("nobody$i", "wrong horse") }
        assertEquals(HttpStatusCode.TooManyRequests, login("sam", "correct horse").status)
    }

    // BDD: Passwords shorter than ten characters are refused
    @Test
    fun `passwords shorter than ten characters are refused`() = TestHeating().api {
        assertEquals(HttpStatusCode.BadRequest, createUser(admin(), "kid", password = "short").status)
    }

    // BDD: Passwords are never stored in plain text
    @Test
    fun `passwords are never stored in plain text`() {
        val t = TestHeating()
        t.api { createUser(admin(), "sam", password = "plaintext-canary") }
        assertFalse(t.app.store.get("users")!!.contains("plaintext-canary"))
        assertTrue(t.app.store.get("users")!!.contains("sam"))
    }

    // BDD: A tampered or expired session token is rejected
    @Test
    fun `a tampered or expired session token is rejected`() {
        val t = TestHeating()
        t.api {
            val token = admin()
            assertEquals(HttpStatusCode.OK, rooms(token))
            val tampered = token.dropLast(1) + (if (token.last() == 'A') 'B' else 'A')
            assertEquals(HttpStatusCode.Unauthorized, rooms(tampered))
            t.clock.advance(Duration.ofDays(181))
            assertEquals(HttpStatusCode.Unauthorized, rooms(token))
        }
    }

    // BDD: The service token still works for scripts
    @Test
    fun `the service token still works for scripts`() = TestHeating().api { client ->
        setup()
        assertEquals(HttpStatusCode.OK, client.get("/api/rooms").status)
    }

    // BDD: Admin creates, lists and deletes users
    @Test
    fun `admin creates, lists and deletes users`() = TestHeating().api {
        val jane = admin()
        assertEquals(HttpStatusCode.Created, createUser(jane, "sam").status)
        val samToken = tokenOf(login("sam", "correct horse"))
        val list = anonymous.get("/api/admin/users") { bearerAuth(jane) }.bodyAsText()
        assertEquals(listOf("jane", "sam"), Json.parseToJsonElement(list).jsonArray.map { it.jsonObject["username"]!!.jsonPrimitive.content })
        assertFalse(list.contains("hash", ignoreCase = true))
        assertFalse(list.contains("pbkdf2"))

        val deleted = anonymous.delete("/api/admin/users/${userId(jane, "sam")}") { bearerAuth(jane) }
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        assertEquals(HttpStatusCode.Unauthorized, login("sam", "correct horse").status)
        assertEquals(HttpStatusCode.Unauthorized, rooms(samToken))
    }

    // BDD: Duplicate usernames are refused
    @Test
    fun `duplicate usernames are refused`() = TestHeating().api {
        val jane = admin()
        createUser(jane, "sam")
        assertEquals(HttpStatusCode.Conflict, createUser(jane, "SAM").status)
    }

    // BDD: Members cannot use admin endpoints
    @Test
    fun `members cannot use admin endpoints`() = TestHeating().api {
        createUser(admin(), "sam")
        val sam = tokenOf(login("sam", "correct horse"))
        assertEquals(HttpStatusCode.Forbidden, anonymous.get("/api/admin/users") { bearerAuth(sam) }.status)
        assertEquals(HttpStatusCode.Forbidden, createUser(sam, "eve").status)
    }

    // BDD: The last admin cannot be deleted or demoted
    @Test
    fun `the last admin cannot be deleted or demoted`() = TestHeating().api {
        val jane = admin()
        val id = userId(jane, "jane")
        assertEquals(HttpStatusCode.Conflict, anonymous.delete("/api/admin/users/$id") { bearerAuth(jane) }.status)
        val demote = anonymous.patch("/api/admin/users/$id") {
            bearerAuth(jane)
            contentType(ContentType.Application.Json)
            setBody("""{"admin":false}""")
        }
        assertEquals(HttpStatusCode.Conflict, demote.status)
    }

    // BDD: Admin resets a password and the user's sessions end
    @Test
    fun `admin resets a password and the user's sessions end`() = TestHeating().api {
        val jane = admin()
        createUser(jane, "sam")
        val samToken = tokenOf(login("sam", "correct horse"))
        val reset = anonymous.patch("/api/admin/users/${userId(jane, "sam")}") {
            bearerAuth(jane)
            contentType(ContentType.Application.Json)
            setBody("""{"password":"battery staple"}""")
        }
        assertEquals(HttpStatusCode.OK, reset.status)
        assertEquals(HttpStatusCode.Unauthorized, rooms(samToken))
        assertEquals(HttpStatusCode.OK, login("sam", "battery staple").status)
    }

    // BDD: A user changes their own password
    @Test
    fun `a user changes their own password`() = TestHeating().api {
        createUser(admin(), "sam")
        val first = tokenOf(login("sam", "correct horse"))
        val other = tokenOf(login("sam", "correct horse"))
        val wrong = post("/api/auth/password", """{"current":"nope nope nope","new":"battery staple"}""", first)
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)

        val changed = post("/api/auth/password", """{"current":"correct horse","new":"battery staple"}""", first)
        assertEquals(HttpStatusCode.OK, changed.status)
        val fresh = tokenOf(changed)
        assertNotEquals(first, fresh)
        assertEquals(HttpStatusCode.OK, rooms(fresh))
        assertEquals(HttpStatusCode.Unauthorized, rooms(other))
        assertEquals(HttpStatusCode.OK, login("sam", "battery staple").status)
    }

    // BDD: Sign out everywhere ends every session of that user
    @Test
    fun `sign out everywhere ends every session of that user`() = TestHeating().api {
        createUser(admin(), "sam")
        val one = tokenOf(login("sam", "correct horse"))
        val two = tokenOf(login("sam", "correct horse"))
        assertEquals(HttpStatusCode.NoContent, post("/api/auth/logout-all", "{}", one).status)
        assertEquals(HttpStatusCode.Unauthorized, rooms(one))
        assertEquals(HttpStatusCode.Unauthorized, rooms(two))
    }
}
