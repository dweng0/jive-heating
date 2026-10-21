package heating.api

import heating.HeatingApp
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route

fun Route.sensorRoutes(heating: HeatingApp) {
    adminOnly {
        get("/api/sensors") {
            val unassignedOnly = call.request.queryParameters["unassigned"] == "true"
            val sensors = heating.sensors.all()
                .filter { !unassignedOnly || (heating.roomSensors.roomOf(it.name) == null && it.name !in heating.ignoredDevices) }
                .map { heating.sensorView(it) }
            call.respond(sensors)
        }
    }

    route("/api/rooms/{id}/sensor/{sensor}") {
        adminOnly {
            put {
                val room = call.acting { heating.roomSensors.assign(call.parameters["id"]!!, call.parameters["sensor"]!!) }
                call.respond(heating.roomView(room))
            }

            delete {
                val room = call.acting { heating.roomSensors.remove(call.parameters["id"]!!, call.parameters["sensor"]!!) }
                call.respond(heating.roomView(room))
            }
        }
    }
}
