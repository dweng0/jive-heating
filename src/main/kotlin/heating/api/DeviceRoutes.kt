package heating.api

import heating.ApiException
import heating.Actor
import heating.HeatingApp
import heating.actionLog
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** Leaving paired Zigbee2MQTT devices alone. Registered inside [adminOnly]. */
fun Route.deviceRoutes(heating: HeatingApp) {
    get("/api/devices/ignored") { call.respond(heating.ignoredDevices.all()) }

    route("/api/devices/{device}/ignored") {
        ignoreRoutes(heating)
    }
}

private fun Route.ignoreRoutes(heating: HeatingApp) {
    put {
        val name = call.parameters["device"]!!
        if (!heating.isDevice(name)) throw ApiException.notFound("device not found")
        call.acting {
            heating.ignoredDevices.ignore(name)
            actionLog.info("device {} ignored{}", name, Actor.by())
        }
        call.respond(HttpStatusCode.NoContent)
    }

    delete {
        val name = call.parameters["device"]!!
        val was = call.acting {
            heating.ignoredDevices.unignore(name).also { if (it) actionLog.info("device {} no longer ignored{}", name, Actor.by()) }
        }
        if (!was) throw ApiException.notFound("device is not ignored")
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun HeatingApp.isDevice(name: String) = valves[name] != null || sensors[name] != null || meters[name] != null
