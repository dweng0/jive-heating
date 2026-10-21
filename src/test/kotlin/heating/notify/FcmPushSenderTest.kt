package heating.notify

import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FcmPushSenderTest {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val pem = "-----BEGIN PRIVATE KEY-----\n" +
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keys.private.encoded) + "\n-----END PRIVATE KEY-----\n"
    private val account = ServiceAccount("home-heating", "push@home-heating.iam.gserviceaccount.com", pem)

    private class FakeHttp(val reply: (String) -> Pair<Int, String>) : HttpPost {
        val calls = mutableListOf<Triple<String, Map<String, String>, String>>()
        override fun post(url: String, headers: Map<String, String>, body: String): Pair<Int, String> {
            calls += Triple(url, headers, body)
            return reply(url)
        }
    }

    private fun tokenReply(url: String) = 200 to """{"access_token":"ya29.abc","expires_in":3599}"""

    @Test
    fun `sends to the project with a token from the service account and reuses it`() {
        val http = FakeHttp { url -> if ("oauth2" in url) tokenReply(url) else 200 to "{}" }
        val sender = FcmPushSender(account, http) { Instant.parse("2026-10-18T10:00:00Z") }
        assertEquals(PushResult.SENT, sender.send("phone-1", "Heating finished", "Utility Room is at 22°"))
        assertEquals(PushResult.SENT, sender.send("phone-2", "Heating finished", "Utility Room is at 22°"))

        assertEquals(1, http.calls.count { "oauth2" in it.first })
        val send = http.calls.last()
        assertEquals("https://fcm.googleapis.com/v1/projects/home-heating/messages:send", send.first)
        assertEquals("Bearer ya29.abc", send.second["Authorization"])
        assertTrue("\"token\":\"phone-2\"" in send.third)
        assertTrue("\"channel_id\":\"heating\"" in send.third)
    }

    @Test
    fun `the JWT is signed with the service account's key`() {
        val sender = FcmPushSender(account, FakeHttp { 200 to "{}" }) { Instant.parse("2026-10-18T10:00:00Z") }
        val (header, claims, signature) = sender.jwt().split(".")
        val verifier = Signature.getInstance("SHA256withRSA").apply { initVerify(keys.public); update("$header.$claims".toByteArray()) }
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(signature)))
        assertTrue("firebase.messaging" in String(Base64.getUrlDecoder().decode(claims)))
    }

    @Test
    fun `an unregistered phone and an outage are told apart`() {
        val gone = FcmPushSender(account, FakeHttp { url -> if ("oauth2" in url) tokenReply(url) else 404 to """{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}""" })
        assertEquals(PushResult.UNREGISTERED, gone.send("old-phone", "t", "b"))
        val down = FcmPushSender(account, FakeHttp { throw java.io.IOException("no route") })
        assertEquals(PushResult.FAILED, down.send("phone", "t", "b"))
    }
}
