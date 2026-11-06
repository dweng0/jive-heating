package heating.auth

import heating.heatingJson
import heating.store.Store
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * One sign-in on one device. [lastSeen] is the last request made with it, written at most once a minute. [signedOutAt]
 * is set when it was signed out, by its user or an admin. Times are ISO instants. No token or address is kept.
 */
@Serializable
data class SessionRecord(
    val id: String,
    val userId: String,
    val device: String,
    val signedInAt: String,
    val lastSeen: String,
    val expires: String,
    val signedOutAt: String? = null,
)

/** Every session, as one JSON document. [writes] counts saves, so tests can see last-seen writes are rare. */
class SessionRepository(private val store: Store) {
    var writes = 0
        private set

    fun load(): List<SessionRecord> =
        store.get(KEY)?.let { heatingJson.decodeFromString(ListSerializer(SessionRecord.serializer()), it) } ?: emptyList()

    fun save(sessions: List<SessionRecord>) {
        writes++
        store.put(KEY, heatingJson.encodeToString(ListSerializer(SessionRecord.serializer()), sessions))
    }

    private companion object {
        const val KEY = "sessions"
    }
}

/** A short name for the device behind a User-Agent, such as "Chrome on Android" or "Android app". */
fun deviceOf(userAgent: String?): String {
    val ua = userAgent?.trim().orEmpty()
    if (ua.isEmpty()) return "Unknown device"
    if (ua.startsWith("Ktor client", ignoreCase = true) || ua.startsWith("okhttp", ignoreCase = true) || "HeatingApp" in ua) return "Android app"
    val browser = when {
        "Edg/" in ua -> "Edge"
        "OPR/" in ua -> "Opera"
        "Firefox/" in ua || "FxiOS" in ua -> "Firefox"
        "SamsungBrowser" in ua -> "Samsung Internet"
        "Chrome/" in ua || "CriOS" in ua -> "Chrome"
        "Safari/" in ua -> "Safari"
        else -> null
    }
    val os = when {
        "Android" in ua -> "Android"
        "iPhone" in ua -> "iPhone"
        "iPad" in ua -> "iPad"
        "Windows" in ua -> "Windows"
        "CrOS" in ua -> "ChromeOS"
        "Mac OS X" in ua || "Macintosh" in ua -> "Mac"
        "Linux" in ua -> "Linux"
        else -> null
    }
    return when {
        browser != null && os != null -> "$browser on $os"
        browser != null -> browser
        os != null -> os
        else -> "Unknown device"
    }
}
