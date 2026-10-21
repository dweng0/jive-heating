package heating.api

import heating.HeatingApp
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class GroupRequest(val name: String? = null, val rooms: List<String>? = null)

fun Route.groupRoutes(heating: HeatingApp) = route("/api/groups") {
    get { call.respond(heating.groups.list().map { heating.groupView(it) }) }
    post {
        val body = call.receive<GroupRequest>()
        val group = heating.groups.create(body.name ?: "", body.rooms ?: emptyList())
        call.respond(HttpStatusCode.Created, heating.groupView(group))
    }
    route("/{id}") {
        patch {
            val body = call.receive<GroupRequest>()
            call.respond(heating.groupView(heating.groups.update(call.parameters["id"]!!, body.name, body.rooms)))
        }
        delete {
            heating.groups.delete(call.parameters["id"]!!)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/control") {
            val group = heating.groups.get(call.parameters["id"]!!)
            val body = call.receive<HouseRequest>()
            call.acting { heating.applyHouseRequest(body, group.rooms, "group ${group.name}") }
            call.respond(heating.groupView(heating.groups.get(group.id)))
        }
        delete("/boost") {
            val group = heating.groups.get(call.parameters["id"]!!)
            call.acting { heating.house.cancelBoosts(group.rooms, "group ${group.name}") }
            call.respond(heating.groupView(group))
        }
    }
}
