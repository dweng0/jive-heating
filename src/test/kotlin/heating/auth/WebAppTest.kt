package heating.auth

import heating.testing.TEST_TOKEN
import heating.testing.TestHeating
import heating.testing.anonymous
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.websocket.close
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebAppTest {

    private suspend fun ApplicationTestBuilder.json(path: String, body: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): HttpResponse =
        anonymous.post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
            block()
        }

    /** Admin "jane" set up, member "sam" signed in on the web; returns sam's cookie header value. */
    private suspend fun ApplicationTestBuilder.webSam(admin: Boolean = false): String {
        val jane = Json.parseToJsonElement(
            json("/api/auth/setup", """{"serviceToken":"$TEST_TOKEN","username":"jane","password":"correct horse"}""").bodyAsText(),
        ).jsonObject["token"]!!.jsonPrimitive.content
        json("/api/admin/users", """{"username":"sam","password":"correct horse","admin":$admin}""") { header(HttpHeaders.Authorization, "Bearer $jane") }
        val login = json("/api/auth/login", """{"username":"sam","password":"correct horse","web":true}""")
        return login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
    }

    // BDD: Web sign-in sets an HttpOnly session cookie and returns no token
    @Test
    fun `web sign-in sets an HttpOnly session cookie and returns no token`() = TestHeating().api {
        json("/api/auth/setup", """{"serviceToken":"$TEST_TOKEN","username":"sam","password":"correct horse"}""")
        val login = json("/api/auth/login", """{"username":"sam","password":"correct horse","web":true}""")
        assertEquals(HttpStatusCode.OK, login.status)
        val cookie = login.headers[HttpHeaders.SetCookie]!!
        assertTrue(cookie.startsWith("__Host-heat="), cookie)
        listOf("HttpOnly", "Secure", "SameSite=Strict", "Path=/").forEach { assertTrue(cookie.contains(it), "$it in $cookie") }
        assertEquals("", Json.parseToJsonElement(login.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content)
    }

    // BDD: The session cookie authenticates reads
    @Test
    fun `the session cookie authenticates reads`() = TestHeating().api {
        val cookie = webSam()
        assertEquals(HttpStatusCode.OK, anonymous.get("/api/rooms") { header(HttpHeaders.Cookie, cookie) }.status)
    }

    // BDD: Changes made with the cookie need the web client header
    @Test
    fun `changes made with the cookie need the web client header`() = TestHeating().api {
        // An admin, since only admins create rooms.
        val cookie = webSam(admin = true)
        val bare = json("/api/rooms", """{"name":"Lounge"}""") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.Unauthorized, bare.status)
        val web = json("/api/rooms", """{"name":"Lounge"}""") {
            header(HttpHeaders.Cookie, cookie)
            header("X-Heat-Client", "web")
        }
        assertEquals(HttpStatusCode.Created, web.status)
    }

    // BDD: Changing password on the web keeps that browser signed in
    @Test
    fun `changing password on the web keeps that browser signed in`() = TestHeating().api {
        val old = webSam()
        val change = json("/api/auth/password", """{"current":"correct horse","new":"battery staple"}""") {
            header(HttpHeaders.Cookie, old)
            header("X-Heat-Client", "web")
        }
        assertEquals(HttpStatusCode.OK, change.status)
        val fresh = change.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        assertTrue(fresh.startsWith("__Host-heat=") && fresh != old, fresh)
        assertEquals("", Json.parseToJsonElement(change.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, anonymous.get("/api/rooms") { header(HttpHeaders.Cookie, fresh) }.status)
        assertEquals(HttpStatusCode.Unauthorized, anonymous.get("/api/rooms") { header(HttpHeaders.Cookie, old) }.status)
    }

    // BDD: Web sign-out clears the cookie
    @Test
    fun `web sign-out clears the cookie`() = TestHeating().api {
        val response = json("/api/auth/logout", "{}")
        assertEquals(HttpStatusCode.NoContent, response.status)
        val cookie = response.headers[HttpHeaders.SetCookie]!!
        assertTrue(cookie.startsWith("__Host-heat=;") || cookie.startsWith("__Host-heat=\"\""), cookie)
        assertTrue(cookie.contains("Max-Age=0"), cookie)
    }

    // BDD: A cookie WebSocket from another origin is refused
    @Test
    fun `a cookie websocket from another origin is refused`() = TestHeating().api {
        val cookie = webSam()
        val ws = createClient { install(WebSockets) }
        assertFailsWith<Exception> {
            ws.webSocketSession("/ws") {
                header(HttpHeaders.Cookie, cookie)
                header(HttpHeaders.Origin, "https://evil.example")
            }
        }
        val ok = ws.webSocketSession("/ws") {
            header(HttpHeaders.Cookie, cookie)
            header(HttpHeaders.Origin, "http://localhost")
        }
        ok.close()
    }

    // BDD: The web app is served with a strict content security policy
    @Test
    fun `the web app is served with a strict content security policy`() {
        val dir = Files.createTempDirectory("web")
        Files.writeString(dir.resolve("index.html"), "<html>heating-web-marker</html>")
        TestHeating(webDir = dir.toString()).api {
            for (path in listOf("/", "/rooms/abc")) {
                val page = anonymous.get(path)
                assertEquals(HttpStatusCode.OK, page.status, path)
                assertTrue(page.bodyAsText().contains("heating-web-marker"), path)
                assertTrue(page.headers["Content-Security-Policy"]!!.contains("default-src 'self'"), path)
            }
            assertEquals(HttpStatusCode.NotFound, anonymous.get("/api/nope") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }.status)
        }
    }
}
