package heating.api

import heating.HeatingApp
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class HolidayRequest(val from: String? = null, val until: String, val target: Double? = null)

fun Route.holidayRoutes(heating: HeatingApp) = route("/api/holiday") {
    put {
        val body = call.receive<HolidayRequest>()
        call.acting { heating.holiday.start(body.from, body.until, body.target) }
        call.respond(heating.statusView())
    }
    delete {
        call.acting { heating.holiday.end() }
        call.respond(heating.statusView())
    }
}
