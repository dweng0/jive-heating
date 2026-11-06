package heating.auth

import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * What a valid session token says: whose it is, until when, which session version it was minted under, and which
 * session (sign-in) it is; [sessionId] is null for a token from before sessions were kept.
 */
data class Session(val userId: String, val expires: Instant, val version: Int, val sessionId: String? = null)

/**
 * Signed session tokens, `<payload>.<hmac>` in base64url, payload `<userId>:<expiry epoch s>:<version>:<sessionId>`
 * (older tokens have no session id). Bumping a user's session version ends every token minted before.
 */
class SessionTokens(private val secret: ByteArray, private val now: () -> Instant) {

    fun issue(userId: String, version: Int, sessionId: String? = null): String {
        val expires = now().plus(LIFETIME).epochSecond
        val payload = encoder.encodeToString(listOfNotNull(userId, expires, version, sessionId).joinToString(":").toByteArray())
        return "$payload.${sign(payload)}"
    }

    /** The session behind [token], or null if it is malformed, forged or expired. */
    fun read(token: String): Session? {
        val dot = token.indexOf('.')
        if (dot <= 0) return null
        val payload = token.substring(0, dot)
        val signature = token.substring(dot + 1)
        if (!MessageDigest.isEqual(sign(payload).toByteArray(), signature.toByteArray())) return null
        val parts = runCatching { String(decoder.decode(payload)) }.getOrNull()?.split(':') ?: return null
        if (parts.size != 3 && parts.size != 4) return null
        val expires = parts[1].toLongOrNull()?.let(Instant::ofEpochSecond) ?: return null
        val version = parts[2].toIntOrNull() ?: return null
        if (!now().isBefore(expires)) return null
        return Session(parts[0], expires, version, parts.getOrNull(3))
    }

    private fun sign(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        return encoder.encodeToString(mac.doFinal(payload.toByteArray()))
    }

    companion object {
        val LIFETIME: Duration = Duration.ofDays(180)
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()
    }
}
