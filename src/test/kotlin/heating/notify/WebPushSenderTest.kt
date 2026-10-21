package heating.notify

import heating.heatingJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun unb64(text: String): ByteArray = Base64.getUrlDecoder().decode(text.trimEnd('='))

/** A browser's side of a push subscription: its key pair and auth secret. */
class TestBrowser {
    val keys: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val auth: ByteArray = Random.nextBytes(16)
    val publicKey: ByteArray get() = P256.uncompressed(keys.public as ECPublicKey)

    /** The subscription JSON this browser would send for [endpoint]. */
    fun subscription(endpoint: String) =
        """{"endpoint":"$endpoint","expirationTime":null,"keys":{"p256dh":"${base64Url(publicKey)}","auth":"${base64Url(auth)}"}}"""

    /** The plaintext of a push message body, decrypted the way a browser does (RFC 8291 section 3, RFC 8188). */
    fun decrypt(body: ByteArray): String = String(decryptWebPush(body, keys.private as ECPrivateKey, publicKey, auth))
}

/**
 * The receiving side of RFC 8291, written out step by step from the RFC, independently of the
 * sender's code: read the aes128gcm header, agree the ECDH secret with the sender's key from the
 * key id, derive the key and nonce, decrypt the one record and strip its padding.
 */
fun decryptWebPush(body: ByteArray, uaPrivate: ECPrivateKey, uaPublic: ByteArray, authSecret: ByteArray): ByteArray {
    val buffer = ByteBuffer.wrap(body)
    val salt = ByteArray(16).also { buffer.get(it) }
    val recordSize = buffer.int
    val keyId = ByteArray(buffer.get().toInt() and 0xff).also { buffer.get(it) }
    val record = ByteArray(buffer.remaining()).also { buffer.get(it) }
    assertEquals(4096, recordSize)
    assertEquals(65, keyId.size)
    assertTrue(record.size <= recordSize, "one record")

    fun hmac(key: ByteArray, data: ByteArray) = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
    val asPublic = P256.publicKey(keyId)!!
    val ecdhSecret = KeyAgreement.getInstance("ECDH").apply { init(uaPrivate); doPhase(asPublic, true) }.generateSecret()
    // PRK_key = HMAC-SHA-256(auth_secret, ecdh_secret); IKM = HMAC-SHA-256(PRK_key, key_info || 0x01)
    val prkKey = hmac(authSecret, ecdhSecret)
    val ikm = hmac(prkKey, "WebPush: info".toByteArray() + 0 + uaPublic + keyId + 1)
    // PRK = HMAC-SHA-256(salt, IKM); CEK and NONCE are its expansions.
    val prk = hmac(salt, ikm)
    val cek = hmac(prk, "Content-Encoding: aes128gcm".toByteArray() + 0 + 1).copyOf(16)
    val nonce = hmac(prk, "Content-Encoding: nonce".toByteArray() + 0 + 1).copyOf(12)
    val padded = Cipher.getInstance("AES/GCM/NoPadding")
        .apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce)) }
        .doFinal(record)
    val end = padded.indexOfLast { it != 0.toByte() }
    assertEquals(2.toByte(), padded[end], "the last record ends with padding delimiter 2")
    return padded.copyOf(end)
}

private operator fun ByteArray.plus(b: Int): ByteArray = this + byteArrayOf(b.toByte())

/** Checks a VAPID `Authorization` header against the service's public key; returns the JWT's claims. */
fun verifyVapid(authorization: String, vapidPublic: ECPublicKey): Map<String, String> {
    val match = Regex("""^vapid t=([\w-]+\.[\w-]+\.[\w-]+), k=([\w-]+)$""").matchEntire(authorization)
    requireNotNull(match) { "not a vapid header: $authorization" }
    val (jwt, k) = match.destructured
    assertContentEquals(P256.uncompressed(vapidPublic), unb64(k), "k is the service's public key")
    val (header, claims, signature) = jwt.split('.')
    val headerJson = heatingJson.parseToJsonElement(String(unb64(header))).jsonObject
    assertEquals("ES256", headerJson["alg"]!!.jsonPrimitive.content)
    assertEquals("JWT", headerJson["typ"]!!.jsonPrimitive.content)
    val raw = unb64(signature)
    assertEquals(64, raw.size, "JWS ES256 signatures are raw R || S")
    val verifier = Signature.getInstance("SHA256withECDSA").apply { initVerify(vapidPublic); update("$header.$claims".toByteArray()) }
    assertTrue(verifier.verify(der(raw)), "the JWT is signed with the service's VAPID key")
    return heatingJson.parseToJsonElement(String(unb64(claims))).jsonObject.mapValues { it.value.jsonPrimitive.content }
}

/** Raw R || S as the DER SEQUENCE the JDK's plain SHA256withECDSA verifies. */
private fun der(raw: ByteArray): ByteArray {
    fun integer(bytes: ByteArray): ByteArray {
        val value = BigInteger(1, bytes).toByteArray()
        return byteArrayOf(2, value.size.toByte()) + value
    }
    val sequence = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
    return byteArrayOf(0x30, sequence.size.toByte()) + sequence
}

class WebPushSenderTest {

    private fun privateKey(raw: String): ECPrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, unb64(raw)), P256.params)) as ECPrivateKey

    @Test
    fun `encryption matches the RFC 8291 appendix A example`() {
        val asPublic = unb64("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8")
        val asPrivate = privateKey("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw")
        val uaPublic = unb64("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4")
        val uaPrivate = privateKey("q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94")
        val auth = unb64("BTBZMqHH6r4Tts7J_aSIgg")
        val salt = unb64("DGv6ra1nlYgDCS1FRnbzlw")
        val plaintext = "When I grow up, I want to be a watermelon"

        val body = WebPushCrypto.encrypt(plaintext.toByteArray(), uaPublic, auth, KeyPair(P256.publicKey(asPublic), asPrivate), salt)

        assertEquals(
            "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN",
            base64Url(body),
        )
        assertEquals(plaintext, String(decryptWebPush(body, uaPrivate, uaPublic, auth)))
    }

    @Test
    fun `a browser decrypts what the sender sends it and the VAPID signature checks out`() {
        val store = heating.store.SqliteStore(java.nio.file.Files.createTempFile("webpush", ".db").toString())
        val keys = VapidKeys.loadOrCreate(store)
        val browser = TestBrowser()
        val sent = mutableListOf<Triple<String, Map<String, String>, ByteArray>>()
        val now = Instant.parse("2026-10-18T12:00:00Z")
        val sender = WebPushSender(keys, CONTACT, { url, headers, body -> sent += Triple(url, headers, body); 201 }, { now })
        val subscription = WebPushSubscription(
            "https://web.push.apple.com/QGuQyavXutnMHy",
            WebPushKeys(base64Url(browser.publicKey), base64Url(browser.auth)),
            "john-id",
        )

        assertEquals(PushResult.SENT, sender.send(subscription, WebPushMessage("Jane turned the heating on", "Utility Room, heating to 22°", "utility")))
        // Each message has its own sender key and salt.
        sender.send(subscription, WebPushMessage("Heating finished", "Utility Room is at 22°"))

        val (url, headers, body) = sent[0]
        assertEquals(subscription.endpoint, url)
        assertEquals(
            """{"title":"Jane turned the heating on","body":"Utility Room, heating to 22°","roomId":"utility"}""",
            browser.decrypt(body),
        )
        assertEquals("""{"title":"Heating finished","body":"Utility Room is at 22°","roomId":null}""", browser.decrypt(sent[1].third))
        assertTrue(!body.copyOf(16).contentEquals(sent[1].third.copyOf(16)), "a fresh salt per message")
        assertEquals("aes128gcm", headers["Content-Encoding"])
        assertEquals("normal", headers["Urgency"])
        assertTrue(headers.getValue("TTL").toInt() in 1..86_400)
        val claims = verifyVapid(headers.getValue("Authorization"), keys.publicKey)
        assertEquals("https://web.push.apple.com", claims["aud"])
        assertEquals(CONTACT, claims["sub"])
        val exp = claims.getValue("exp").toLong()
        assertTrue(exp > now.epochSecond && exp <= now.epochSecond + 24 * 3600, "exp within 24 hours")
    }

    @Test
    fun `the push service's answer decides the result and nothing throws`() {
        val store = heating.store.SqliteStore(java.nio.file.Files.createTempFile("webpush", ".db").toString())
        val keys = VapidKeys.loadOrCreate(store)
        val browser = TestBrowser()
        val subscription = WebPushSubscription(
            "https://fcm.googleapis.com/fcm/send/abc",
            WebPushKeys(base64Url(browser.publicKey), base64Url(browser.auth)),
            "u",
        )
        val message = WebPushMessage("t", "b")
        fun answering(status: Int) = WebPushSender(keys, CONTACT, { _, _, _ -> status }).send(subscription, message)
        assertEquals(PushResult.SENT, answering(201))
        assertEquals(PushResult.UNREGISTERED, answering(404))
        assertEquals(PushResult.UNREGISTERED, answering(410))
        assertEquals(PushResult.FAILED, answering(429))
        assertEquals(PushResult.FAILED, answering(500))
        assertEquals(PushResult.FAILED, WebPushSender(keys, CONTACT, { _, _, _ -> throw java.io.IOException("down") }).send(subscription, message))
    }
}
