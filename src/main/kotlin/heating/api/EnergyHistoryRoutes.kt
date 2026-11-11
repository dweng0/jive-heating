package heating.api

import heating.ApiException
import heating.HeatingApp
import heating.energy.DashboardPeriod
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
    get("/api/energy/flow") { call.respond(heating.energyFlow.of(DashboardPeriod.of(call.request.queryParameters["period"]))) }
    get("/api/energy/gas") {
        val date = call.request.queryParameters["date"]?.let {
            runCatching { java.time.LocalDate.parse(it) }.getOrNull() ?: throw ApiException.badRequest("date must be YYYY-MM-DD")
        }
        call.respond(heating.gasDays.of(date))
    }
}
