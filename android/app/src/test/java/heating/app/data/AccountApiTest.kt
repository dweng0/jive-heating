package heating.app.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AccountApiTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private var unauthorized = 0

    private fun api(token: String?, status: HttpStatusCode, body: String, headers: Map<String, String> = emptyMap()) =
        AccountApi("https://heat.example", token, MockEngine { request ->
            requests += request
            respond(body, status, headersOf(*(headers.map { it.key to listOf(it.value) } + (HttpHeaders.ContentType to listOf("application/json"))).toTypedArray()))
        }, onUnauthorized = { unauthorized++ })

    @Test
    fun `sign-in posts the credentials without a bearer token and returns the session`() = runTest {
        val session = api(null, HttpStatusCode.OK, """{"token":"t1","user":{"id":"u1","username":"sam","admin":false}}""")
            .login("sam", "correct horse")
        assertEquals("t1", session.token)
        assertEquals("sam", session.user.username)
        val request = requests.single()
        assertEquals("/api/auth/login", request.url.encodedPath)
        assertNull(request.headers[HttpHeaders.Authorization])
        assertEquals("""{"username":"sam","password":"correct horse"}""", (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
    }

    @Test
    fun `a locked sign-in says how long to wait`() = runTest {
        val e = assertFailsWith<HeatingApiException> {
            api(null, HttpStatusCode.TooManyRequests, """{"error":"too many attempts, try again later"}""", mapOf(HttpHeaders.RetryAfter to "240")).login("sam", "x")
        }
        assertEquals(429, e.status)
        assertEquals("Too many attempts. Try again in 4 minutes.", e.message)
    }

    @Test
    fun `a rejected session signs out but a wrong current password does not`() = runTest {
        runCatching { api("old", HttpStatusCode.Unauthorized, """{"error":"sign in first"}""").users() }
        assertEquals(1, unauthorized)
        runCatching { api("live", HttpStatusCode.Unauthorized, """{"error":"wrong current password"}""").changePassword("a", "b") }
        assertEquals(1, unauthorized)
    }

    @Test
    fun `a phone registers and unregisters its notification token`() = runTest {
        api("t", HttpStatusCode.NoContent, "").registerDevice("fcm:abc/1")
        api("t", HttpStatusCode.NoContent, "").unregisterDevice("fcm:abc/1")
        assertEquals(listOf("PUT", "DELETE"), requests.map { it.method.value })
        assertEquals("/api/me/devices/fcm:abc%2F1", requests[0].url.encodedPath)
    }

    // BDD: The Android app asks to notify and has a switch for each notification
    @Test
    fun `notification switches load and save`() = runTest {
        val prefs = api("t", HttpStatusCode.OK, """{"heatingOn":true,"heatingFinished":false}""").notifications()
        assertEquals(NotificationPrefs(heatingOn = true, heatingFinished = false), prefs)
        api("t", HttpStatusCode.OK, """{"heatingOn":false,"heatingFinished":false}""").setNotifications(prefs.copy(heatingOn = false))
        val put = requests.last()
        assertEquals("/api/me/notifications", put.url.encodedPath)
        assertEquals("""{"heatingOn":false,"heatingFinished":false}""", (put.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
    }
}
