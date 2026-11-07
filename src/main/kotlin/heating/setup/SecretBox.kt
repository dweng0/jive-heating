package heating.setup

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Encrypts small secrets (the broker password) for the database with a key from HEATING_SECRET_KEY (AES-256-GCM). */
class SecretBox(secretKey: String) {
    private val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secretKey.toByteArray(Charsets.UTF_8)), "AES")
    private val random = SecureRandom()

    fun encrypt(plain: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return PREFIX + Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    /** The secret, or null when [stored] was not encrypted with this key. */
    fun decrypt(stored: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }.getOrNull()

    companion object {
        const val PREFIX = "enc:v1:"
    }
}
