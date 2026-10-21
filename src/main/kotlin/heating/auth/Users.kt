package heating.auth

import heating.heatingJson
import heating.store.Store
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.security.SecureRandom
import java.util.Base64

/**
 * Someone who can sign in. [sessionVersion] is bumped to end all of their sessions at once.
 * [rooms] are the room ids a member can see; null for users from before room access, who see every room.
 */
@Serializable
data class User(
    val id: String,
    val username: String,
    val passwordHash: String,
    val admin: Boolean,
    val createdAt: String,
    val sessionVersion: Int = 0,
    val rooms: List<String>? = null,
)

/** Persists all users as one JSON document, plus the secret session tokens are signed with. */
class UserRepository(private val store: Store) {
    fun load(): List<User> =
        store.get(KEY)?.let { heatingJson.decodeFromString(ListSerializer(User.serializer()), it) } ?: emptyList()

    fun save(users: List<User>) =
        store.put(KEY, heatingJson.encodeToString(ListSerializer(User.serializer()), users))

    /** The session signing secret, made on first use and kept so sessions survive a restart. */
    fun sessionSecret(): ByteArray {
        store.get(SECRET_KEY)?.let { return Base64.getDecoder().decode(it) }
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        store.put(SECRET_KEY, Base64.getEncoder().encodeToString(secret))
        return secret
    }

    private companion object {
        const val KEY = "users"
        const val SECRET_KEY = "session-secret"
    }
}
