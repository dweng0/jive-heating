package heating.api

import heating.HeatingApp
import heating.energy.HistoryChartRange
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** The house's use for the history chart, and its totals for the odometer and trip counter. Registered inside [energyReaders]. */
fun Route.energyHistoryRoutes(heating: HeatingApp) {
    get("/api/energy/history") {
        val range = HistoryChartRange.of(call.request.queryParameters["range"])
        call.respond(heating.houseHistory.of(range, usageTo(call.request.queryParameters["to"])))
    }

    get("/api/energy/totals") { call.respond(heating.energyTotals.now()) }
}
