package heating.api

import heating.HeatingApp
import heating.rooms.Mode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class SetMode(val mode: Mode, val target: Double? = null)

/** Zone ids: a grouped room's id, or "roomId:valve" for one valve of an independent room. */
fun Route.zoneRoutes(heating: HeatingApp) = route("/api/zones/{zoneId}") {
    put("/mode") {
        val body = call.receive<SetMode>()
        val room = call.acting { heating.zones.setMode(call.parameters["zoneId"]!!, body.mode, body.target) }
        call.respond(heating.roomView(room))
    }
}
