package heating.api

import heating.HeatingApp
import heating.energy.DashboardPeriod
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** The whole-house energy dashboard. Registered inside [adminOnly]. */
fun Route.dashboardRoutes(heating: HeatingApp) {
    get("/api/dashboard") {
        call.respond(heating.energyDashboards.of(DashboardPeriod.of(call.request.queryParameters["period"])))
    }
}
