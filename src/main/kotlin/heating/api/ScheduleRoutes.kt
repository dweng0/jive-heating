package heating.api

import heating.ApiException
import heating.HeatingApp
import heating.rooms.Block
import heating.rooms.DaySchedule
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.time.DayOfWeek

@Serializable
data class CopyDay(val from: String, val to: List<String>)

private val DAYS = DayOfWeek.entries.associateBy { it.name.lowercase() }

private fun day(name: String): DayOfWeek = DAYS[name] ?: throw ApiException.badRequest("day must be one of ${DAYS.keys.joinToString()}")

fun Route.scheduleRoutes(heating: HeatingApp) = route("/api/zones/{zoneId}/schedule") {
    get {
        call.respond(heating.schedules.schedule(call.parameters["zoneId"]!!))
    }
    // Registered before /{day} so "copy" is not taken for a day name.
    post("/copy") {
        val body = call.receive<CopyDay>()
        call.respond(call.acting { heating.schedules.copyDay(call.parameters["zoneId"]!!, day(body.from), body.to.map(::day)) })
    }
    put("/{day}") {
        val body = call.receive<DaySchedule>()
        val blocks: List<Block> = body.blocks
        call.respond(call.acting { heating.schedules.saveDay(call.parameters["zoneId"]!!, day(call.parameters["day"]!!), blocks) })
    }
}
