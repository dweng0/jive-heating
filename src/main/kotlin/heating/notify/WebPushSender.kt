package heating.notify

import heating.heatingJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What the service worker gets: it shows [title] and [body], and a tap opens [roomId] (or the Rooms screen). */
@Serializable
data class WebPushMessage(val title: String, val body: String, val roomId: String? = null)

/** An HTTP POST of a binary body to a push service, answering the response status. May throw. */
fun interface WebPushHttp {
    fun post(url: String, headers: Map<String, String>, body: ByteArray): Int

    companion object {
        /** The JDK's HTTP client, with short timeouts so a slow push service never piles up sends. */
        fun java(): WebPushHttp {
            val client by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }
            return WebPushHttp { url, headers, body ->
                val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build()
                client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
            }
        }
    }
}

/**
 * Message encryption for Web Push (RFC 8291, the "aes128gcm" content coding of RFC 8188), with the
 * JDK's own crypto: a fresh sender key pair and salt per message, one record, padding delimiter 2.
 */
object WebPushCrypto {
    const val RECORD_SIZE = 4096
    private const val TAG = 16

    /** The largest plaintext that fits one record (with its padding delimiter and tag). */
    const val MAX_PLAINTEXT = RECORD_SIZE - TAG - 1

    /**
     * The request body for [plaintext] to a browser with public key [uaPublic] (65 bytes, uncompressed)
     * and [authSecret] (16 bytes), from sender key pair [sender] with a 16-byte [salt].
     */
    fun encrypt(plaintext: ByteArray, uaPublic: ByteArray, authSecret: ByteArray, sender: KeyPair, salt: ByteArray): ByteArray {
        require(plaintext.size <= MAX_PLAINTEXT) { "message too long for one record" }
        require(salt.size == 16) { "salt must be 16 bytes" }
        val receiver = P256.publicKey(uaPublic) ?: throw IllegalArgumentException("not a P-256 public key")
        val asPublic = P256.uncompressed(sender.public as ECPublicKey)
        val (cek, nonce) = keys(ecdh(sender.private as ECPrivateKey, receiver), authSecret, uaPublic, asPublic, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(TAG * 8, nonce))
        val record = cipher.doFinal(plaintext + byteArrayOf(2))

        // Header: salt (16) || record size (4, big-endian) || key id length (1) || key id (the sender's public key).
        return ByteBuffer.allocate(16 + 4 + 1 + asPublic.size + record.size)
            .put(salt).putInt(RECORD_SIZE).put(asPublic.size.toByte()).put(asPublic).put(record).array()
    }

    /**
     * The content encryption key (16 bytes) and nonce (12 bytes) both sides derive from the ECDH
     * secret, the browser's auth secret, both public keys and the salt.
     */
    fun keys(ecdhSecret: ByteArray, authSecret: ByteArray, uaPublic: ByteArray, asPublic: ByteArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val keyInfo = "WebPush: info".toByteArray() + byteArrayOf(0) + uaPublic + asPublic
        val ikm = hkdf(authSecret, ecdhSecret, keyInfo, 32)
        val cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm".toByteArray() + byteArrayOf(0), 16)
        val nonce = hkdf(salt, ikm, "Content-Encoding: nonce".toByteArray() + byteArrayOf(0), 12)
        return cek to nonce
    }

    fun ecdh(private: ECPrivateKey, public: ECPublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").apply { init(private); doPhase(public, true) }.generateSecret()

    /** HKDF-SHA-256 (RFC 5869) for outputs of at most one hash block. */
    private fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        return hmac(prk, info + byteArrayOf(1)).copyOf(length)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
}

/**
 * Sends notifications to browsers' push services (RFC 8030), encrypted for each subscription
 * (RFC 8291) and signed with the service's VAPID key (RFC 8292). [send] never throws.
 */
class WebPushSender(
    private val keys: VapidKeys,
    private val contact: String,
    private val http: WebPushHttp,
    private val now: () -> Instant = Instant::now,
    private val senderKeys: () -> KeyPair = ::newKeyPair,
    private val salt: () -> ByteArray = { ByteArray(16).also(random::nextBytes) },
) {
    fun send(subscription: WebPushSubscription, message: WebPushMessage): PushResult = try {
        val decoder = Base64.getUrlDecoder()
        val body = WebPushCrypto.encrypt(
            heatingJson.encodeToString(WebPushMessage.serializer(), message).toByteArray(),
            decoder.decode(subscription.keys.p256dh.trimEnd('=')),
            decoder.decode(subscription.keys.auth.trimEnd('=')),
            senderKeys(),
            salt(),
        )
        val headers = mapOf(
            "Authorization" to "vapid t=${jwt(origin(subscription.endpoint))}, k=${keys.publicKeyBase64Url}",
            "Content-Encoding" to "aes128gcm",
            "Content-Type" to "application/octet-stream",
            "TTL" to TTL.seconds.toString(),
            "Urgency" to "normal",
        )
        when (val status = http.post(subscription.endpoint, headers, body)) {
            in 200..299 -> PushResult.SENT
            404, 410 -> PushResult.UNREGISTERED
            else -> {
                log.warn("push service {} refused a message: {}", hostOf(subscription.endpoint), status)
                PushResult.FAILED
            }
        }
    } catch (e: Exception) {
        log.debug("push service {} unreachable: {}", hostOf(subscription.endpoint), e.toString())
        PushResult.FAILED
    }

    /** The VAPID JWT for push service [audience] (its origin), signed ES256, valid for [JWT_LIFETIME]. */
    internal fun jwt(audience: String): String {
        val header = b64("""{"typ":"JWT","alg":"ES256"}""".toByteArray())
        val claims = b64(
            buildJsonObject {
                put("aud", audience)
                put("exp", JsonPrimitive(now().plus(JWT_LIFETIME).epochSecond))
                put("sub", contact)
            }.toString().toByteArray(),
        )
        // The P1363 form is the raw R || S (64 bytes) JWS wants, rather than the JDK's usual DER.
        val signer = Signature.getInstance("SHA256withECDSAinP1363Format")
        signer.initSign(keys.privateKey)
        signer.update("$header.$claims".toByteArray())
        return "$header.$claims.${b64(signer.sign())}"
    }

    companion object {
        /** How long a push service keeps a message for a browser that is off. */
        val TTL: Duration = Duration.ofHours(4)

        /** RFC 8292 allows at most 24 hours. */
        val JWT_LIFETIME: Duration = Duration.ofHours(12)

        private val log = LoggerFactory.getLogger(WebPushSender::class.java)
        private val random = SecureRandom()

        private fun b64(bytes: ByteArray) = base64Url.encodeToString(bytes)

        fun origin(endpoint: String): String = URI(endpoint).let { "${it.scheme}://${it.rawAuthority}" }

        private fun hostOf(endpoint: String) = runCatching { URI(endpoint).host }.getOrNull() ?: "?"

        private fun newKeyPair(): KeyPair =
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    }
}
