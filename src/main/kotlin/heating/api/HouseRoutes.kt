package heating.api

import heating.ApiException
import heating.HeatingApp
import heating.house.HouseControl
import heating.rooms.Mode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/** Either a mode for every room (manual needs a target), or a boost of every room for [boost] minutes. */
@Serializable
data class HouseRequest(val mode: Mode? = null, val target: Double? = null, val boost: Int? = null)

/** Apply [body] to every room, or only to [roomIds] (a group, named [label] in the log). */
fun HeatingApp.applyHouseRequest(body: HouseRequest, roomIds: Collection<String>?, label: String = HouseControl.HOUSE) = when {
    body.mode != null && body.boost != null -> throw ApiException.badRequest("send a mode or a boost, not both")
    body.mode != null -> house.setMode(body.mode, body.target, roomIds, label)
    body.boost != null -> house.boost(body.boost, body.target, roomIds, label)
    else -> throw ApiException.badRequest("send a mode or a boost")
}

fun Route.houseRoutes(heating: HeatingApp) = route("/api/house") {
    post {
        val body = call.receive<HouseRequest>()
        call.acting { heating.applyHouseRequest(body, roomIds = null) }
        call.respond(heating.rooms.list().map { heating.roomView(it) })
    }
    delete("/boost") {
        call.acting { heating.house.cancelBoosts() }
        call.respond(heating.rooms.list().map { heating.roomView(it) })
    }
}
