package heating.api

import heating.HeatingApp
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import java.time.format.DateTimeFormatter

/** openapi: Holiday */
@Serializable
data class HolidayView(val from: String, val until: String, val target: Double, val active: Boolean)

/** openapi: BoilerState */
@Serializable
data class BoilerStateView(val state: String, val until: String?)

/** openapi: Status */
@Serializable
data class StatusView(
    val heatDemand: Boolean,
    val boiler: BoilerStateView,
    val holiday: HolidayView?,
    val serverTime: String,
    val timezone: String,
)

fun HeatingApp.statusView() = StatusView(
    heatDemand = demand.demand,
    boiler = demand.state().let { s ->
        BoilerStateView(s.state, s.until?.atZone(config.timezone)?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
    },
    holiday = holiday.current()?.let {
        HolidayView(it.from, it.until, it.target, it.isActive(now()))
    },
    serverTime = now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    timezone = config.timezone.id,
)

fun Route.statusRoutes(heating: HeatingApp) {
    get("/api/status") { call.respond(heating.statusView()) }
}
