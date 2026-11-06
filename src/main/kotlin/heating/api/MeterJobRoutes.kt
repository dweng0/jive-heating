package heating.api

import heating.Actor
import heating.ApiException
import heating.HeatingApp
import heating.actionLog
import heating.untrusted
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable

/** openapi: MeterLabel */
@Serializable
data class MeterLabel(val label: String)

/** openapi: MeterJob. [job] is "house", "area" (with [area]), "monitored" (with an optional [room] or [area]) or "none". */
@Serializable
data class MeterJob(val job: String, val room: String? = null, val area: String? = null)

/** A meter's label, and its job set in one step, for the energy management page. Registered inside [adminOnly]. */
fun Route.meterJobRoutes(heating: HeatingApp) {
    put("/api/meters/{meter}/label") {
        val name = call.parameters["meter"]!!
        val meter = heating.meters[name] ?: throw ApiException.notFound("meter not found")
        val label = call.receive<MeterLabel>().label
        call.acting {
            heating.meterLabels.set(name, label)
            actionLog.info("meter {} labelled {}{}", untrusted(name), untrusted(heating.meterLabels.of(name)), Actor.by())
        }
        call.respond(heating.meterView(meter))
    }

    put("/api/meters/{meter}/job") {
        val name = call.parameters["meter"]!!
        val meter = heating.meters[name] ?: throw ApiException.notFound("meter not found")
        val job = call.receive<MeterJob>()
        // Everything is checked before anything changes.
        when (job.job) {
            "house" -> if (!meter.reportsEnergy) throw ApiException.unprocessable("This meter does not report energy, so it cannot be the main meter")
            "area" -> heating.energyAreas.get(job.area ?: throw ApiException.badRequest("area is required"))
            "monitored" -> {
                if (!meter.reportsEnergy) throw ApiException.unprocessable("This device does not report energy")
                if (job.room != null && job.area != null) throw ApiException.badRequest("a device is in one room or one area at most")
                job.room?.let { room -> if (heating.rooms.list().none { it.id == room }) throw ApiException.notFound("room not found") }
                job.area?.let { heating.energyAreas.get(it) }
            }
            "none" -> Unit
            else -> throw ApiException.badRequest("job must be house, area, monitored or none")
        }
        call.acting {
            // Leave the old job; what it recorded stays where it was recorded.
            if (heating.energySettings.get().electricityMeter == name && job.job != "house") {
                heating.energySettings.update { it.copy(electricityMeter = null) }
            }
            heating.energyAreas.measuredBy(name)?.takeIf { job.job != "area" || it != job.area }?.let { heating.energyAreas.stopMeasuring(it) }
            if (job.job != "monitored") heating.monitoredMeters.stop(name)
            when (job.job) {
                "house" -> heating.energySettings.chooseMeter(name)
                "area" -> heating.energyAreas.measure(job.area!!, name)
                "monitored" -> {
                    heating.monitoredMeters.monitor(name)
                    when {
                        job.room != null -> heating.monitoredMeters.putInRoom(name, job.room)
                        job.area != null -> heating.monitoredMeters.putInArea(name, job.area)
                        else -> {
                            heating.monitoredMeters.roomOf(name)?.let { heating.monitoredMeters.takeOutOfRoom(name, it) }
                            heating.monitoredMeters.areaOf(name)?.let { heating.monitoredMeters.takeOutOfArea(name, it) }
                        }
                    }
                }
            }
            actionLog.info("meter {} job {}{}{}", untrusted(name), job.job, listOfNotNull(job.room, job.area).joinToString("") { " " + untrusted(it) }, Actor.by())
        }
        call.respond(heating.meterView(heating.meters[name] ?: meter))
    }
}
