package heating.api

import heating.ApiException
import heating.HeatingApp
import heating.auth.Caller
import heating.heatingJson
import heating.notify.WebPushKeys
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A browser's `PushSubscription.toJSON()`; `expirationTime` and anything else is ignored. */
@Serializable
data class WebPushSubscribeRequest(val endpoint: String, val keys: WebPushKeys)

@Serializable
data class WebPushUnsubscribeRequest(val endpoint: String)

/** Web Push for this browser: the service's VAPID public key, and this browser's subscription. */
fun Route.webPushRoutes(heating: HeatingApp) = route("/api/me/webpush") {
    get {
        val keys = heating.webPush.keys
        call.respond(
            buildJsonObject {
                put("webPush", keys != null)
                if (keys != null) put("publicKey", keys.publicKeyBase64Url)
            },
        )
    }
    put {
        val userId = call.webPushUser()
        val body = call.receive<WebPushSubscribeRequest>()
        heating.webPush.subscribe(userId, body.endpoint, body.keys)
        call.respond(HttpStatusCode.NoContent)
    }
    delete {
        val userId = call.webPushUser()
        heating.webPush.unsubscribe(userId, call.receive<WebPushUnsubscribeRequest>().endpoint)
        call.respond(HttpStatusCode.NoContent)
    }
}

/**
 * Signing out on a browser: the web app sends its push endpoint with the sign-out, and that
 * subscription is removed when the request still carries the person's session. Sign-out itself
 * never fails because of this.
 */
suspend fun HeatingApp.forgetWebPushOnSignOut(call: ApplicationCall) {
    val endpoint = runCatching {
        heatingJson.parseToJsonElement(call.receiveText()).jsonObject["webPushEndpoint"]?.jsonPrimitive?.content
    }.getOrNull() ?: return
    val token = (WebSession.authHeader(call) as? HttpAuthHeader.Single)?.blob ?: return
    val userId = users.authenticate(token)?.userId ?: return
    webPush.unsubscribe(userId, endpoint)
}

/** The service token is no person, so it has no browsers. */
private fun RoutingCall.webPushUser(): String =
    principal<Caller>()?.userId ?: throw ApiException.badRequest("web push belongs to a signed-in user")
