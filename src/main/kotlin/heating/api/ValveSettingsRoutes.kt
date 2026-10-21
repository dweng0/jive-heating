package heating.api

import heating.ApiException
import heating.HeatingApp
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.patch
import kotlinx.serialization.Serializable

/** openapi: PATCH /api/valves/{valve} */
@Serializable
data class PatchValve(val openWindow: Boolean? = null, val childLock: Boolean? = null)

fun Route.valveSettingsRoutes(heating: HeatingApp) {
    patch("/api/valves/{valve}") {
        val name = call.parameters["valve"]!!
        if (heating.valves[name] == null) throw ApiException.notFound("valve not found")
        val patch = call.receive<PatchValve>()
        patch.openWindow?.let { heating.commander.setOpenWindow(name, it) }
        patch.childLock?.let { heating.commander.setChildLock(name, it) }
        call.respond(heating.valveView(heating.valves[name]!!))
    }
}
