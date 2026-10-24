package heating.api

import heating.ApiException
import heating.HeatingApp
import heating.heatingJson
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import heating.auth.TooManyAttempts
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.serialization.Serializable

@Serializable
data class ApiError(val error: String)

const val AUTH = "api-token"

fun Application.heatingModule(heating: HeatingApp) {
    install(ContentNegotiation) { json(heatingJson) }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, ApiError(e.message)) }
        exception<TooManyAttempts> { call, e ->
            call.response.header(HttpHeaders.RetryAfter, e.retryAfterSeconds.toString())
            call.respond(HttpStatusCode.TooManyRequests, ApiError(e.message!!))
        }
        exception<BadRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "bad request"))
        }
    }
    install(WebSockets)
    install(Authentication) {
        bearer(AUTH) {
            authHeader(WebSession::authHeader)
            authenticate { credential ->
                heating.users.authenticate(credential.token)
            }
        }
    }

    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }
        publicAuthRoutes(heating)

        authenticate(AUTH) {
            get("/openapi.json") {
                val doc = Application::class.java.classLoader.getResource("openapi.json")!!.readText()
                call.respondText(doc, ContentType.Application.Json)
            }
            roomScoped {
                roomRoutes(heating)
                zoneRoutes(heating)
                scheduleRoutes(heating)
                boostRoutes(heating)
                sensorRoutes(heating)
            }
            adminOnly {
                valveRoutes(heating)
                valveSettingsRoutes(heating)
                statusRoutes(heating)
                holidayRoutes(heating)
                houseRoutes(heating)
                groupRoutes(heating)
                energyRoutes(heating)
                boostSettingsAdminRoutes(heating)
                deviceRoutes(heating)
                dashboardRoutes(heating)
                livePowerRoutes(heating)
            }
            boostSettingsRoutes(heating)
            liveRoutes(heating)
            authRoutes(heating)
            appRoutes(heating)
            historyRoutes(heating)
            activityRoutes(heating)
            notificationRoutes(heating)
            webPushRoutes(heating)
        }
        heating.config.webDir?.let { webRoutes(it) }
    }
}
