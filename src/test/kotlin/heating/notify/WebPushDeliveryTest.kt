package heating.notify

import heating.rooms.Mode
import heating.testing.TestHeating
import heating.testing.anonymous
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.server.testing.ApplicationTestBuilder
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebPushDeliveryTest {

    private val appleEndpoint = "https://web.push.apple.com/QGuQyavXutnMHy-john"

    /** "Utility Room" with "Utility TRV" at 18.0, off, so nothing calls for heat yet. */
    private fun utility(t: TestHeating): String {
        t.discover("Utility TRV")
        val room = t.app.rooms.create("Utility Room")
        t.app.rooms.assignValve(room.id, "Utility TRV")
        t.app.zones.setMode(room.id, Mode.OFF, null)
        t.valveState("Utility TRV", """{"local_temperature":18.0}""")
        return room.id
    }

    private suspend fun ApplicationTestBuilder.json(method: String, path: String, token: String, body: String) =
        anonymous.put(path) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.also { assertTrue(it.status.isSuccess(), "$method $path: ${it.status}") }

    /**
     * Admin "jane" with an Android phone, and member "john" who can see [johnRooms], with her
     * iPhone subscribed as [browser] at [endpoints]; returns their bearer tokens.
     */
    private suspend fun ApplicationTestBuilder.household(
        t: TestHeating,
        browser: TestBrowser,
        johnRooms: List<String>,
        endpoints: List<String> = listOf(appleEndpoint),
    ): Pair<String, String> {
        val jane = signIn()
        val john = signIn("john")
        val johnId = t.app.users.list().single { it.username == "john" }.id
        json("PUT", "/api/admin/users/$johnId/rooms", jane, """{"rooms":[${johnRooms.joinToString { "\"$it\"" }}]}""")
        assertEquals(HttpStatusCode.NoContent, anonymous.put("/api/me/devices/jane-phone") { bearerAuth(jane) }.status)
        endpoints.forEach { assertEquals(HttpStatusCode.NoContent, subscribe(john, browser.subscription(it)).status) }
        return jane to john
    }

    private suspend fun ApplicationTestBuilder.turnOn(token: String, zoneId: String, t: TestHeating) {
        json("PUT", "/api/zones/$zoneId/mode", token, """{"mode":"manual","target":22.0}""")
        t.clock.advance(Duration.ofSeconds(10))
        t.app.tick()
    }

    // BDD: Browsers get the same notifications as Android phones
    @Test
    fun `browsers get the same notifications as android phones`() {
        val t = webPushOn()
        val roomId = utility(t)
        val browser = TestBrowser()
        t.api {
            val (jane, _) = household(t, browser, listOf(roomId))
            turnOn(jane, roomId, t)
        }
        // Jane made the change, so his phone gets nothing; john has no Android phone.
        assertEquals(emptyList(), t.pushed)
        val sent = t.webPushed.single()
        assertEquals(appleEndpoint, sent.endpoint)
        assertEquals(
            """{"title":"Jane turned the heating on","body":"Utility Room, heating to 22°","roomId":"$roomId"}""",
            browser.decrypt(sent.body),
        )
        val claims = verifyVapid(sent.headers.getValue("Authorization"), t.app.webPush.keys!!.publicKey)
        assertEquals("https://web.push.apple.com", claims["aud"])
        assertEquals(CONTACT, claims["sub"])
        assertEquals("aes128gcm", sent.headers["Content-Encoding"])

        // Her switch for "Someone turns the heating on" applies to her browser too.
        val off = webPushOn()
        val offRoom = utility(off)
        off.api {
            val (jane, john) = household(off, TestBrowser(), listOf(offRoom))
            json("PUT", "/api/me/notifications", john, """{"heatingOn":false,"heatingFinished":true}""")
            turnOn(jane, offRoom, off)
        }
        assertEquals(emptyList(), off.webPushed)

        // And so does room access: she hears nothing about a room she cannot see.
        val hidden = webPushOn()
        val hiddenRoom = utility(hidden)
        val lounge = hidden.app.rooms.create("Lounge").id
        hidden.api {
            val (jane, _) = household(hidden, TestBrowser(), listOf(lounge))
            turnOn(jane, hiddenRoom, hidden)
        }
        assertEquals(emptyList(), hidden.webPushed)

        // Without WEBPUSH_CONTACT nothing goes to browsers, and the phones work as before.
        val noWebPush = TestHeating()
        val plainRoom = utility(noWebPush)
        noWebPush.api {
            val jane = signIn()
            val john = signIn("john")
            val johnId = noWebPush.app.users.list().single { it.username == "john" }.id
            json("PUT", "/api/admin/users/$johnId/rooms", jane, """{"rooms":["$plainRoom"]}""")
            assertEquals(HttpStatusCode.NoContent, anonymous.put("/api/me/devices/john-phone") { bearerAuth(john) }.status)
            turnOn(jane, plainRoom, noWebPush)
        }
        assertEquals(listOf("john-phone"), noWebPush.pushed.map { it.token })
        assertEquals(emptyList(), noWebPush.webPushed)
    }

    // BDD: Subscriptions the push service has dropped are forgotten
    @Test
    fun `subscriptions the push service has dropped are forgotten`() {
        val t = webPushOn()
        val roomId = utility(t)
        val gone = "https://web.push.apple.com/gone"
        val expired = "https://fcm.googleapis.com/fcm/send/expired"
        val live = "https://updates.push.services.mozilla.com/wpush/v2/live"
        t.webPushStatus[gone] = 410
        t.webPushStatus[expired] = 404
        val johnId = { t.app.users.list().single { it.username == "john" }.id }
        t.api {
            val (jane, john) = household(t, TestBrowser(), listOf(roomId), listOf(gone, expired, live))
            assertEquals(HttpStatusCode.NoContent, anonymous.put("/api/me/devices/john-phone") { bearerAuth(john) }.status)
            turnOn(jane, roomId, t)
            assertEquals(setOf(gone, expired, live), t.webPushed.map { it.endpoint }.toSet())
            assertEquals(listOf(live), t.app.webPush.subscriptionsFor(johnId()).map { it.endpoint })

            // The next notification goes only to the live one.
            t.webPushed.clear()
            t.app.zones.setMode(roomId, Mode.OFF, null)
            t.app.tick()
            turnOn(jane, roomId, t)
            assertEquals(listOf(live), t.webPushed.map { it.endpoint })

            // A push service that cannot be reached does not stop the heating or the phones,
            // and is not taken for a dropped subscription.
            t.webPushed.clear()
            t.pushed.clear()
            t.webPushFails = true
            t.app.zones.setMode(roomId, Mode.OFF, null)
            t.app.tick()
            turnOn(jane, roomId, t)
            assertEquals(listOf("john-phone"), t.pushed.map { it.token })
            assertEquals(listOf(live), t.app.webPush.subscriptionsFor(johnId()).map { it.endpoint })
        }
        assertTrue(t.app.demand.state().state in setOf("heating", "resting"), "the room still calls for heat")
    }
}
