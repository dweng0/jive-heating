package heating.api

import heating.Actor
import heating.HeatingApp
import heating.actionLog
import heating.untrusted
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/** The name an energy area is created with or renamed to. */
@Serializable
data class EnergyAreaName(val name: String)

/** Energy areas: unheated things measured by their own meters. Registered inside [adminOnly]. */
fun Route.energyAreaRoutes(heating: HeatingApp) {
    get("/api/energy/areas") { call.respond(heating.energyAreas.list()) }

    post("/api/energy/areas") {
        val name = call.receive<EnergyAreaName>().name
        val area = call.acting {
            heating.energyAreas.create(name).also { actionLog.info("energy area {} created{}", it.id, Actor.by()) }
        }
        call.respond(HttpStatusCode.Created, area)
    }

    patch("/api/energy/areas/{id}") {
        val id = call.parameters["id"]!!
        val name = call.receive<EnergyAreaName>().name
        val area = call.acting {
            heating.energyAreas.rename(id, name).also { actionLog.info("energy area {} renamed {}{}", it.id, untrusted(it.name), Actor.by()) }
        }
        call.respond(area)
    }

    delete("/api/energy/areas/{id}") {
        val id = call.parameters["id"]!!
        call.acting {
            heating.energyAreas.delete(id)
            actionLog.info("energy area {} deleted{}", untrusted(id), Actor.by())
        }
        call.respond(HttpStatusCode.NoContent)
    }
}
