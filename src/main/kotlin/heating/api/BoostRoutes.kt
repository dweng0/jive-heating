package heating.api

import heating.HeatingApp
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class BoostRequest(val minutes: Int, val target: Double? = null)

fun Route.boostRoutes(heating: HeatingApp) = route("/api/zones/{zoneId}/boost") {
    post {
        val body = call.receive<BoostRequest>()
        val zone = call.acting { heating.boosts.boost(call.parameters["zoneId"]!!, body.minutes, body.target) }
        call.respond(heating.roomView(heating.rooms.get(zone.roomId)))
    }
    delete {
        val zone = call.acting { heating.boosts.cancel(call.parameters["zoneId"]!!) }
        call.respond(heating.roomView(heating.rooms.get(zone.roomId)))
    }
}
