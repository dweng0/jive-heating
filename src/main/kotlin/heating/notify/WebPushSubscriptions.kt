package heating.notify

import heating.heatingJson
import heating.store.Store
import kotlinx.serialization.Serializable

/** The keys a browser gives with its subscription (RFC 8291): its P-256 public key and auth secret, base64url. */
@Serializable
data class WebPushKeys(val p256dh: String, val auth: String)

/** One browser's push subscription, as `PushSubscription.toJSON()` gives it, owned by [userId]. */
@Serializable
data class WebPushSubscription(val endpoint: String, val keys: WebPushKeys, val userId: String)

@Serializable
private data class SubscriptionsDocument(
    /** Push endpoint to its subscription; an endpoint identifies one browser. */
    val subscriptions: Map<String, WebPushSubscription> = emptyMap(),
)

/** Every browser subscribed to Web Push, kept as one document in the store. */
class WebPushSubscriptions(private val store: Store) {
    private var doc: SubscriptionsDocument =
        store.get(KEY)?.let { heatingJson.decodeFromString(SubscriptionsDocument.serializer(), it) } ?: SubscriptionsDocument()

    /** A browser signed in as the subscription's user; an endpoint moves to whoever subscribed it last. */
    @Synchronized
    fun subscribe(subscription: WebPushSubscription) =
        save(doc.copy(subscriptions = doc.subscriptions + (subscription.endpoint to subscription)))

    /** Stop sending to [endpoint]; only its owner may remove it. */
    @Synchronized
    fun unsubscribe(userId: String, endpoint: String) {
        if (doc.subscriptions[endpoint]?.userId == userId) save(doc.copy(subscriptions = doc.subscriptions - endpoint))
    }

    /** The push service no longer knows [endpoint]. */
    @Synchronized
    fun forget(endpoint: String) {
        if (endpoint in doc.subscriptions) save(doc.copy(subscriptions = doc.subscriptions - endpoint))
    }

    @Synchronized
    fun subscriptionsFor(userId: String): List<WebPushSubscription> = doc.subscriptions.values.filter { it.userId == userId }

    private fun save(updated: SubscriptionsDocument) {
        doc = updated
        store.put(KEY, heatingJson.encodeToString(SubscriptionsDocument.serializer(), updated))
    }

    companion object {
        private const val KEY = "webpush-subscriptions"
    }
}
