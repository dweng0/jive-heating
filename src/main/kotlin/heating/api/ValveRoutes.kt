package heating.api

import heating.HeatingApp
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.valveRoutes(heating: HeatingApp) = route("/api/valves") {
    get {
        val unassignedOnly = call.request.queryParameters["unassigned"] == "true"
        val valves = heating.valves.all()
            .filter { !unassignedOnly || (heating.rooms.roomOf(it.name) == null && it.name !in heating.ignoredDevices) }
            .map { heating.valveView(it) }
        call.respond(valves)
    }
}
