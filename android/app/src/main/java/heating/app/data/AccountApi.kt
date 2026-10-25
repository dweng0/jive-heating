package heating.app.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable

@Serializable
data class AccountUser(
    val id: String? = null,
    val username: String,
    val admin: Boolean,
    val createdAt: String? = null,
    /** Ids of the rooms this person can see; an admin has every room. */
    val rooms: List<String> = emptyList(),
)

@Serializable
data class Session(val token: String, val user: AccountUser)

@Serializable
data class AuthStatus(val setupRequired: Boolean)

/** Which phone notifications this person wants. */
@Serializable
data class NotificationPrefs(val heatingOn: Boolean = true, val heatingFinished: Boolean = true, val boilerOverride: Boolean = true)

@Serializable
private data class SetupRequest(val serviceToken: String, val username: String, val password: String)

@Serializable
private data class LoginRequest(val username: String, val password: String)

@Serializable
private data class ChangePasswordRequest(val current: String, val new: String)

@Serializable
private data class CreateUserRequest(val username: String, val password: String, val admin: Boolean)

@Serializable
private data class UserRoomsRequest(val rooms: List<String>)

@Serializable
private data class PatchUserRequest(val password: String? = null, val admin: Boolean? = null)

/**
 * Sign-in, first-run setup, the caller's own account, and the admin's user list (see openapi.json).
 * [token] is null before sign-in; the public calls (status, setup, login) do not need it.
 */
class AccountApi(
    baseUrl: String,
    token: String? = null,
    engine: HttpClientEngine = OkHttp.create(),
    /** Called when the server rejects this session; never for a wrong password on sign-in or password change. */
    private val onUnauthorized: () -> Unit = {},
) {
    private val client = HttpClient(engine) {
        install(ContentNegotiation) { json(apiJson) }
        expectSuccess = false
        defaultRequest {
            url(baseUrl.trimEnd('/') + "/")
            token?.let { bearerAuth(it) }
        }
        HttpResponseValidator {
            validateResponse { response ->
                val status = response.status.value
                val path = response.call.request.url.encodedPath
                if (status == 401 && token != null && !path.endsWith("api/auth/password")) onUnauthorized()
                if (status >= 400) {
                    val text = response.bodyAsText()
                    val message = if (status == 429) {
                        lockedMessage(response.headers[HttpHeaders.RetryAfter]?.toLongOrNull())
                    } else {
                        runCatching { apiJson.decodeFromString<ApiError>(text).error }
                            .getOrElse { text.ifBlank { response.status.description } }
                    }
                    throw HeatingApiException(status, message)
                }
            }
        }
    }

    suspend fun status(): AuthStatus = client.get("api/auth/status").body()

    suspend fun setup(serviceToken: String, username: String, password: String): Session =
        client.post("api/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody(SetupRequest(serviceToken, username, password))
        }.body()

    suspend fun login(username: String, password: String): Session =
        client.post("api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username, password))
        }.body()

    suspend fun me(): AccountUser = client.get("api/auth/me").body()

    /** Returns a fresh session: the server ends every other session of this user. */
    suspend fun changePassword(current: String, new: String): Session =
        client.post("api/auth/password") {
            contentType(ContentType.Application.Json)
            setBody(ChangePasswordRequest(current, new))
        }.body()

    /** Send this person's notifications to this phone's Firebase token. */
    suspend fun registerDevice(token: String) {
        client.put("api/me/devices/${token.encodeURLPathPart()}")
    }

    suspend fun unregisterDevice(token: String) {
        client.delete("api/me/devices/${token.encodeURLPathPart()}")
    }

    suspend fun notifications(): NotificationPrefs = client.get("api/me/notifications").body()

    suspend fun setNotifications(prefs: NotificationPrefs): NotificationPrefs =
        client.put("api/me/notifications") {
            contentType(ContentType.Application.Json)
            setBody(prefs)
        }.body()

    suspend fun logoutAll() {
        client.post("api/auth/logout-all")
    }

    suspend fun users(): List<AccountUser> = client.get("api/admin/users").body()

    suspend fun createUser(username: String, password: String, admin: Boolean): AccountUser =
        client.post("api/admin/users") {
            contentType(ContentType.Application.Json)
            setBody(CreateUserRequest(username, password, admin))
        }.body()

    suspend fun patchUser(id: String, password: String? = null, admin: Boolean? = null): AccountUser =
        client.patch("api/admin/users/${id.encodeURLPathPart()}") {
            contentType(ContentType.Application.Json)
            setBody(PatchUserRequest(password, admin))
        }.body()

    /** Replace the rooms member [id] can see with [rooms] (the full list). Admins see every room. */
    suspend fun setUserRooms(id: String, rooms: List<String>): AccountUser =
        client.put("api/admin/users/${id.encodeURLPathPart()}/rooms") {
            contentType(ContentType.Application.Json)
            setBody(UserRoomsRequest(rooms))
        }.body()

    suspend fun deleteUser(id: String) {
        client.delete("api/admin/users/${id.encodeURLPathPart()}")
    }

    companion object {
        const val MIN_PASSWORD = 10

        fun lockedMessage(seconds: Long?): String = when {
            seconds == null -> "Too many attempts. Try again later."
            seconds < 90 -> "Too many attempts. Try again in a minute."
            else -> "Too many attempts. Try again in ${(seconds + 59) / 60} minutes."
        }
    }
}
