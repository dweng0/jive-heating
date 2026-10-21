package heating.api

import heating.ApiException
import heating.HeatingApp
import heating.auth.Caller
import heating.notify.NotificationPrefs
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** The signed-in person's phones (Firebase tokens) and which notifications they want. */
fun Route.notificationRoutes(heating: HeatingApp) = route("/api/me") {
    put("/devices/{token}") {
        heating.notificationSettings.register(call.userId(), call.parameters["token"]!!)
        call.respond(HttpStatusCode.NoContent)
    }
    delete("/devices/{token}") {
        heating.notificationSettings.unregister(call.userId(), call.parameters["token"]!!)
        call.respond(HttpStatusCode.NoContent)
    }
    get("/notifications") { call.respond(heating.notificationSettings.prefs(call.userId())) }
    put("/notifications") {
        val prefs = call.receive<NotificationPrefs>()
        heating.notificationSettings.setPrefs(call.userId(), prefs)
        call.respond(prefs)
    }
}

/** The service token is no person, so it has no phones or notification settings. */
private fun RoutingCall.userId(): String =
    principal<Caller>()?.userId ?: throw ApiException.badRequest("notifications belong to a signed-in user")
