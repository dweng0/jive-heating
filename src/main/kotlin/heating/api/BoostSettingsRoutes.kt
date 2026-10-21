package heating.api

import heating.ApiException
import heating.Actor
import heating.HeatingApp
import heating.actionLog
import heating.boost.BoostSettings
import heating.boost.validated
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** The household's boost temperature. Every signed-in user can read it so their app starts boosts there. */
fun Route.boostSettingsRoutes(heating: HeatingApp) {
    get("/api/settings/boost") { call.respond(heating.boostSettings.get()) }
}

/** Changing the household's boost temperature. Registered inside [adminOnly]. */
fun Route.boostSettingsAdminRoutes(heating: HeatingApp) {
    put("/api/settings/boost") {
        val target = (call.receive<JsonObject>()["defaultTarget"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            ?: throw ApiException.badRequest("defaultTarget must be a number")
        val chosen = BoostSettings(defaultTarget = target).validated()
        val updated = call.acting {
            heating.boostSettings.update { chosen }
                .also { actionLog.info("default boost temperature {}{}", it.defaultTarget, Actor.by()) }
        }
        call.respond(updated)
    }
}
