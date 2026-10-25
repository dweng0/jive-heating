package heating.notify

import heating.heatingJson
import heating.store.Store
import kotlinx.serialization.Serializable

/** Which notifications a person wants. All are on for a new user. */
@Serializable
data class NotificationPrefs(val heatingOn: Boolean = true, val heatingFinished: Boolean = true, val boilerOverride: Boolean = true)

@Serializable
private data class NotificationsDocument(
    val prefs: Map<String, NotificationPrefs> = emptyMap(),
    /** Firebase registration token to the user it belongs to. */
    val devices: Map<String, String> = emptyMap(),
)

/** Each user's notification switches and registered phones, kept as one document. */
class NotificationSettings(private val store: Store) {
    private var doc: NotificationsDocument =
        store.get(KEY)?.let { heatingJson.decodeFromString(NotificationsDocument.serializer(), it) } ?: NotificationsDocument()

    @Synchronized
    fun prefs(userId: String): NotificationPrefs = doc.prefs[userId] ?: NotificationPrefs()

    @Synchronized
    fun setPrefs(userId: String, prefs: NotificationPrefs) = save(doc.copy(prefs = doc.prefs + (userId to prefs)))

    /** A phone signed in as [userId]; a token moves to whoever registered it last. */
    @Synchronized
    fun register(userId: String, token: String) = save(doc.copy(devices = doc.devices + (token to userId)))

    /** Stop sending to [token]; only its owner may remove it. */
    @Synchronized
    fun unregister(userId: String, token: String) {
        if (doc.devices[token] == userId) save(doc.copy(devices = doc.devices - token))
    }

    /** The push service no longer knows [token]. */
    @Synchronized
    fun forget(token: String) {
        if (token in doc.devices) save(doc.copy(devices = doc.devices - token))
    }

    @Synchronized
    fun devicesOf(userId: String): List<String> = doc.devices.filterValues { it == userId }.keys.toList()

    private fun save(updated: NotificationsDocument) {
        doc = updated
        store.put(KEY, heatingJson.encodeToString(NotificationsDocument.serializer(), updated))
    }

    companion object {
        private const val KEY = "notifications"
    }
}
