package heating.api

import heating.HeatingApp
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/** openapi: BoilerOverride. Who forced the boiler off and when. */
@Serializable
data class BoilerOverrideView(val by: String, val since: String)

/** openapi: BoilerOverrideStatus. [override] is null while the boiler follows the rooms. */
@Serializable
data class BoilerOverrideStatus(val override: BoilerOverrideView?)

fun HeatingApp.boilerOverrideStatus() =
    BoilerOverrideStatus(boilerOverride.current()?.let { BoilerOverrideView(it.by, it.since) })

/** Anyone signed in may read whether the boiler is forced off, so every app can say why nothing heats. */
fun Route.boilerReadRoutes(heating: HeatingApp) {
    get("/api/boiler") { call.respond(heating.boilerOverrideStatus()) }
}

/** Only admins turn it off or back on. */
fun Route.boilerAdminRoutes(heating: HeatingApp) {
    post("/api/boiler/off") {
        call.acting { heating.boiler.turnOff() }
        call.respond(heating.boilerOverrideStatus())
    }
    post("/api/boiler/on") {
        call.acting { heating.boiler.turnOn() }
        call.respond(heating.boilerOverrideStatus())
    }
}
