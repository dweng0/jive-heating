package heating.api

import heating.Actor
import heating.ApiException
import heating.HeatingApp
import heating.actionLog
import heating.auth.Caller
import heating.energy.AreaAlertRequest
import heating.energy.AreaAlertView
import heating.energy.UsageWindow
import io.ktor.server.auth.principal
import heating.untrusted
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable

/** The name an energy area is created with or renamed to. */
@Serializable
data class EnergyAreaName(val name: String)

/** Energy areas: unheated things measured by their own meters. Registered inside [adminOnly]. */
fun Route.energyAreaRoutes(heating: HeatingApp) {
    get("/api/energy/areas") { call.respond(heating.energyAreas.list()) }

    get("/api/energy/areas/{id}/usage") {
        val area = heating.energyAreas.get(call.parameters["id"]!!)
        val window = UsageWindow.of(call.request.queryParameters["window"])
        call.respond(heating.energyUsage.ofArea(area, window, usageTo(call.request.queryParameters["to"])))
    }

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

    put("/api/energy/areas/{id}/whole-meter/{meter}") {
        val id = call.parameters["id"]!!
        val name = call.parameters["meter"]!!
        heating.energyAreas.get(id)
        val meter = heating.meters[name]
        if (meter == null) {
            if (heating.meters.isDevice(name)) throw ApiException.unprocessable("This device does not report power")
            throw ApiException.notFound("meter not found")
        }
        if (heating.energySettings.get().electricityMeter == name) throw ApiException.conflict("the house meter cannot measure an area")
        if (name in heating.monitoredMeters) throw ApiException.conflict("a monitored plug cannot measure a whole area")
        val area = call.acting {
            heating.energyAreas.measure(id, name).also { actionLog.info("energy area {} measured by {}{}", it.id, untrusted(name), Actor.by()) }
        }
        call.respond(area)
    }

    delete("/api/energy/areas/{id}/whole-meter") {
        val id = call.parameters["id"]!!
        call.acting {
            heating.energyAreas.stopMeasuring(id).also { actionLog.info("energy area {} has no whole-area meter{}", it.id, Actor.by()) }
        }
        call.respond(HttpStatusCode.NoContent)
    }

    put("/api/energy/areas/{id}/meters/{meter}") {
        val id = call.parameters["id"]!!
        val name = call.parameters["meter"]!!
        heating.energyAreas.get(id)
        val meter = heating.meters[name] ?: throw ApiException.notFound("meter not found")
        call.acting {
            if (!heating.monitoredMeters.putInArea(name, id)) throw ApiException.conflict("only a monitored device can be put in an area")
            actionLog.info("meter {} put in energy area {}{}", untrusted(name), id, Actor.by())
        }
        call.respond(heating.meterView(meter))
    }

    delete("/api/energy/areas/{id}/meters/{meter}") {
        val id = call.parameters["id"]!!
        val name = call.parameters["meter"]!!
        val was = call.acting {
            heating.monitoredMeters.takeOutOfArea(name, id).also { if (it) actionLog.info("meter {} taken out of energy area {}{}", untrusted(name), id, Actor.by()) }
        }
        if (!was) throw ApiException.notFound("meter is not in this area")
        call.respond(HttpStatusCode.NoContent)
    }

    put("/api/energy/areas/{id}/overlaps/{other}") {
        val id = call.parameters["id"]!!
        val other = call.parameters["other"]!!
        val area = call.acting {
            heating.energyAreas.overlap(id, other).also { actionLog.info("energy area {} shares a circuit with {}{}", it.id, untrusted(other), Actor.by()) }
        }
        call.respond(area)
    }

    delete("/api/energy/areas/{id}/overlaps/{other}") {
        val id = call.parameters["id"]!!
        val other = call.parameters["other"]!!
        call.acting {
            heating.energyAreas.stopOverlapping(id, other)
            actionLog.info("energy area {} no longer shares a circuit with {}{}", untrusted(id), untrusted(other), Actor.by())
        }
        call.respond(HttpStatusCode.NoContent)
    }

    get("/api/energy/areas/{id}/alert") {
        val area = heating.energyAreas.get(call.parameters["id"]!!)
        val alert = heating.areaAlerts.get(area.id)
        val caller = call.principal<Caller>()
        call.respond(
            alert?.let { AreaAlertView(it.hours, it.notify, heating.users.list().firstOrNull { u -> u.id == it.byUserId }?.username, it.byUserId == caller?.userId) }
                ?: AreaAlertView(),
        )
    }

    put("/api/energy/areas/{id}/alert") {
        val area = heating.energyAreas.get(call.parameters["id"]!!)
        val request = runCatching { call.receive<AreaAlertRequest>() }.getOrElse { throw ApiException.badRequest("hours and notify (me or everyone) are needed") }
        val caller = call.principal<Caller>()!!
        val by = caller.userId ?: throw ApiException.badRequest("a notification belongs to a signed-in admin")
        val alert = call.acting {
            heating.areaAlerts.set(area.id, request, by).also {
                actionLog.info("area {} notifies {} after {} h{}", untrusted(area.name), request.notify.name.lowercase(), request.hours, Actor.by())
            }
        }
        call.respond(AreaAlertView(alert.hours, alert.notify, caller.username, mine = true))
    }

    delete("/api/energy/areas/{id}/alert") {
        val area = heating.energyAreas.get(call.parameters["id"]!!)
        call.acting {
            heating.areaAlerts.clear(area.id)
            actionLog.info("area {} notification off{}", untrusted(area.name), Actor.by())
        }
        call.respond(HttpStatusCode.NoContent)
    }

    delete("/api/energy/areas/{id}") {
        val id = call.parameters["id"]!!
        call.acting {
            // Its clamp goes with it (waiting to be set up again); its plugs stay monitored in no room or area.
            heating.energyAreas.delete(id)
            heating.monitoredMeters.areaDeleted(id)
            heating.areaAlerts.clear(id)
            actionLog.info("energy area {} deleted{}", untrusted(id), Actor.by())
        }
        call.respond(HttpStatusCode.NoContent)
    }
}
