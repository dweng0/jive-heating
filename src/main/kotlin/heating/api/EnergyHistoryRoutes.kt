package heating.api

import heating.HeatingApp
import heating.energy.HistoryChartRange
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** The house's use over a day, week, month or year for the history chart. Registered inside [energyReaders]. */
fun Route.energyHistoryRoutes(heating: HeatingApp) {
    get("/api/energy/history") {
        val range = HistoryChartRange.of(call.request.queryParameters["range"])
        call.respond(heating.houseHistory.of(range, usageTo(call.request.queryParameters["to"])))
    }
}
