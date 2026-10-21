package heating.api

import heating.ApiException
import heating.Actor
import heating.HeatingApp
import heating.actionLog
import heating.auth.Caller
import heating.rooms.Control
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.time.temporal.ChronoUnit

@Serializable
data class CreateRoom(val name: String)

/** Partial room update. Each feature adds the optional fields it needs. */
@Serializable
data class PatchRoom(
    val name: String? = null,
    val control: Control? = null,
    val sourceValve: String? = null,
    val smartTemperatureControl: Boolean? = null,
)

/** Rooms. Members read and control the rooms they can see; creating, renaming, deleting and fitting out are for admins. */
fun Route.roomRoutes(heating: HeatingApp) = route("/api/rooms") {
    get {
        val caller = call.principal<Caller>()
        call.respond(heating.rooms.list().filter { caller?.canSee(it.id) ?: true }.map { heating.roomView(it) })
    }

    adminOnly {
        post {
            val room = heating.rooms.create(call.receive<CreateRoom>().name)
            call.respond(HttpStatusCode.Created, heating.roomView(room))
        }
    }

    route("/{id}") {
        get {
            call.respond(heating.roomView(heating.rooms.get(call.parameters["id"]!!)))
        }

        patch {
            val id = call.parameters["id"]!!
            val patch = call.receive<PatchRoom>()
            if (patch.name != null) call.requireAdmin()
            patch.name?.let { heating.rooms.rename(id, it) }
            patch.control?.let { control ->
                heating.rooms.switchControl(id, control, patch.sourceValve)
                heating.rooms.get(id).zones().forEach(heating.zones::apply)
            }
            patch.smartTemperatureControl?.let { heating.rooms.setSmartTemperatureControl(id, it) }
            call.respond(heating.roomView(heating.rooms.get(id)))
        }

        adminOnly {
            delete {
                heating.rooms.delete(call.parameters["id"]!!)
                call.respond(HttpStatusCode.NoContent)
            }

            put("/valves/{valve}") {
                val room = heating.rooms.assignValve(call.parameters["id"]!!, call.parameters["valve"]!!)
                call.respond(heating.roomView(room))
            }

            delete("/valves/{valve}") {
                val room = heating.rooms.removeValve(call.parameters["id"]!!, call.parameters["valve"]!!)
                call.respond(heating.roomView(room))
            }

            get("/plugs") {
                call.respond(heating.roomPlugs.of(heating.rooms.get(call.parameters["id"]!!)))
            }

            get("/plugs/history") {
                val range = HistoryRange.of(call.request.queryParameters["range"])
                val room = heating.rooms.get(call.parameters["id"]!!)
                // An hour a bar over a day; a day a bar over a week or a month.
                call.respond(heating.roomEnergyHistory.of(room.id, range.length, if (range == HistoryRange.DAY) ChronoUnit.HOURS else ChronoUnit.DAYS))
            }

            put("/meters/{meter}") {
                val room = heating.rooms.get(call.parameters["id"]!!)
                val name = call.parameters["meter"]!!
                val meter = heating.meters[name] ?: throw ApiException.notFound("meter not found")
                call.acting {
                    if (!heating.monitoredMeters.putInRoom(name, room.id)) throw ApiException.conflict("only a monitored device can be put in a room")
                    actionLog.info("meter {} put in room {}{}", name, room.id, Actor.by())
                }
                call.respond(heating.meterView(meter))
            }

            delete("/meters/{meter}") {
                val room = heating.rooms.get(call.parameters["id"]!!)
                val name = call.parameters["meter"]!!
                val was = call.acting {
                    heating.monitoredMeters.takeOutOfRoom(name, room.id)
                        .also { if (it) actionLog.info("meter {} taken out of room {}{}", name, room.id, Actor.by()) }
                }
                if (!was) throw ApiException.notFound("meter is not in this room")
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
