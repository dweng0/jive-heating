package heating.notify

import heating.ApiException
import heating.store.Store
import io.ktor.http.HttpStatusCode
import java.net.URI
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Web Push to browsers (an iPhone's home-screen app, desktop browsers). On only when the service
 * has a [contact] address for the push services; then [keys] is the service's VAPID key pair.
 * Notifications go out through [http] (the JDK's client in production).
 */
class WebPush(store: Store, val contact: String?, http: WebPushHttp = WebPushHttp.java(), now: () -> Instant = Instant::now) {
    val keys: VapidKeys? = contact?.let { VapidKeys.loadOrCreate(store) }
    private val subscriptions = WebPushSubscriptions(store)
    private val sender: WebPushSender? = keys?.let { WebPushSender(it, contact!!, http, now) }

    /** Endpoints whose last send failed, so an unreachable push service is logged once rather than on every message. */
    private val failing = ConcurrentHashMap.newKeySet<String>()

    val enabled: Boolean get() = keys != null

    /** [userId]'s browser at [endpoint] wants notifications. */
    fun subscribe(userId: String, endpoint: String, keys: WebPushKeys) {
        if (!enabled) throw ApiException(HttpStatusCode.Conflict, "web push is not set up on this service")
        if (!isPushService(endpoint)) throw ApiException.badRequest("endpoint is not a known push service")
        val p256dh = decodeBase64Url(keys.p256dh)
        if (p256dh == null || P256.publicKey(p256dh) == null) throw ApiException.badRequest("keys.p256dh is not a P-256 public key")
        if (decodeBase64Url(keys.auth)?.size != 16) throw ApiException.badRequest("keys.auth is not a 16-byte secret")
        subscriptions.subscribe(WebPushSubscription(endpoint, keys, userId))
    }

    /** [userId]'s browser at [endpoint] wants no more notifications (or signed out). */
    fun unsubscribe(userId: String, endpoint: String) = subscriptions.unsubscribe(userId, endpoint)

    /** The push service says [endpoint] is gone. */
    fun forget(endpoint: String) = subscriptions.forget(endpoint)

    /** [userId]'s browsers to notify; none while web push is off. */
    fun subscriptionsFor(userId: String): List<WebPushSubscription> =
        if (enabled) subscriptions.subscriptionsFor(userId) else emptyList()

    /**
     * Sends [message] to one browser; one the push service no longer knows (404, 410) is forgotten.
     * Never throws, but waits for the push service: call it off the heating's threads.
     */
    fun deliver(subscription: WebPushSubscription, message: WebPushMessage): PushResult {
        val result = sender?.send(subscription, message) ?: PushResult.FAILED
        when (result) {
            PushResult.UNREGISTERED -> {
                forget(subscription.endpoint)
                failing.remove(subscription.endpoint)
            }
            PushResult.FAILED ->
                if (failing.add(subscription.endpoint)) log.warn("notification \"{}\" not sent to a browser; push service unreachable or refusing", message.title)
            PushResult.SENT -> failing.remove(subscription.endpoint)
        }
        return result
    }

    companion object {
        private val log = LoggerFactory.getLogger(WebPush::class.java)

        /** The browsers' push services. The service only ever sends to these, never to an address a client made up. */
        private val PUSH_HOSTS = setOf("web.push.apple.com", "fcm.googleapis.com", "updates.push.services.mozilla.com")
        private const val WINDOWS_PUSH_DOMAIN = ".notify.windows.com"
        private const val MAX_ENDPOINT = 2048

        /** An https URL on a push service's host, on the default port, with no user info. */
        fun isPushService(endpoint: String): Boolean {
            if (endpoint.length > MAX_ENDPOINT) return false
            val uri = runCatching { URI(endpoint) }.getOrNull() ?: return false
            if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port != -1) return false
            val host = uri.host?.lowercase() ?: return false
            return host in PUSH_HOSTS || (host.endsWith(WINDOWS_PUSH_DOMAIN) && host.length > WINDOWS_PUSH_DOMAIN.length)
        }

        /** Base64url as browsers send it (padding optional), or null if [text] is not that. */
        private fun decodeBase64Url(text: String): ByteArray? =
            runCatching { Base64.getUrlDecoder().decode(text.trimEnd('=')) }.getOrNull()
    }
}
