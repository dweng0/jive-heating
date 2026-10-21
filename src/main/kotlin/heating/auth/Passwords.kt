package heating.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Password hashing with PBKDF2-HMAC-SHA256 from the JDK (no dependency). A hash is one
 * self-describing string, `$pbkdf2-sha256$i=<iterations>$<salt>$<key>`, so the iteration count
 * can be raised later and old hashes still verify.
 */
class Passwords(private val iterations: Int) {
    private val random = SecureRandom()

    /** A hash of a random secret nobody knows: unknown users are checked against it so the answer takes as long. */
    private val dummy: String by lazy { hash(ByteArray(24).also(random::nextBytes).toString(Charsets.ISO_8859_1)) }

    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val key = derive(password, salt, iterations, KEY_BYTES)
        return "\$pbkdf2-sha256\$i=$iterations\$${b64(salt)}\$${b64(key)}"
    }

    /** True when [password] matches [stored]. A null or malformed [stored] runs the dummy check and answers false. */
    fun verify(password: String, stored: String?): Boolean {
        val parsed = stored?.let(::parse)
        val (i, salt, key) = parsed ?: parse(dummy)!!
        val candidate = derive(password, salt, i, key.size)
        return MessageDigest.isEqual(candidate, key) && parsed != null
    }

    private fun parse(stored: String): Triple<Int, ByteArray, ByteArray>? {
        val match = FORMAT.matchEntire(stored) ?: return null
        val (i, salt, key) = match.destructured
        return runCatching { Triple(i.toInt(), unb64(salt), unb64(key)) }.getOrNull()
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int, bytes: Int): ByteArray {
        val chars = Normalizer.normalize(password, Normalizer.Form.NFKC).toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, bytes * 8)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        const val MIN_LENGTH = 10
        /** Long passphrases are welcome; the cap only bounds the work one request can cause. */
        const val MAX_LENGTH = 256
        const val PRODUCTION_ITERATIONS = 600_000
        private const val SALT_BYTES = 16
        private const val KEY_BYTES = 32
        private val FORMAT = Regex("""[$]pbkdf2-sha256[$]i=(\d{1,8})[$]([A-Za-z0-9+/=]+)[$]([A-Za-z0-9+/=]+)""")

        /** Why [password] is not acceptable, or null when it is. */
        fun problem(password: String): String? = when {
            password.length < MIN_LENGTH -> "password must be at least $MIN_LENGTH characters"
            password.length > MAX_LENGTH -> "password must be at most $MAX_LENGTH characters"
            else -> null
        }

        private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
        private fun unb64(text: String) = Base64.getDecoder().decode(text)
    }
}
