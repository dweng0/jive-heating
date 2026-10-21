package heating.api

import heating.ApiException
import heating.HeatingApp
import heating.actionLog
import heating.Actor
import heating.energy.EnergySettings
import heating.energy.Meter
import heating.energy.validated
import kotlinx.serialization.Serializable
import java.time.format.DateTimeFormatter
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Energy prices and the house electricity meter. Registered inside [adminOnly]. */
fun Route.energyRoutes(heating: HeatingApp) {
    get("/api/settings/energy") { call.respond(heating.energySettings.get()) }

    get("/api/meters") {
        val unassignedOnly = call.request.queryParameters["unassigned"] == "true"
        val house = heating.energySettings.get().electricityMeter
        call.respond(
            heating.meters.all()
                .filter { !unassignedOnly || (it.name != house && it.name !in heating.ignoredDevices && it.name !in heating.monitoredMeters) }
                .map { heating.meterView(it, house) },
        )
    }

    put("/api/meters/{meter}/monitored") {
        val name = call.parameters["meter"]!!
        val meter = heating.meters[name]
        if (meter == null) {
            if (heating.meters.isDevice(name)) throw ApiException.unprocessable("This device does not report energy")
            throw ApiException.notFound("meter not found")
        }
        if (heating.energySettings.get().electricityMeter == name) throw ApiException.conflict("the main meter cannot also be monitored")
        call.acting {
            heating.monitoredMeters.monitor(name)
            actionLog.info("meter {} monitored{}", name, Actor.by())
        }
        call.respond(heating.meterView(meter))
    }

    delete("/api/meters/{meter}/monitored") {
        val name = call.parameters["meter"]!!
        val was = call.acting {
            heating.monitoredMeters.stop(name).also { if (it) actionLog.info("meter {} no longer monitored{}", name, Actor.by()) }
        }
        if (!was) throw ApiException.notFound("meter is not monitored")
        call.respond(HttpStatusCode.NoContent)
    }

    put("/api/meters/{meter}/house") {
        val name = call.parameters["meter"]!!
        if (heating.meters[name] == null) throw ApiException.notFound("meter not found")
        val updated = call.acting {
            heating.energySettings.chooseMeter(name)
                .also { actionLog.info("house electricity meter -> {}{}", name, Actor.by()) }
        }
        call.respond(updated)
    }

    delete("/api/meters/{meter}/house") {
        val name = call.parameters["meter"]!!
        val updated = call.acting {
            heating.energySettings.update {
                if (it.electricityMeter != name) throw ApiException.notFound("meter is not the house meter")
                it.copy(electricityMeter = null)
            }.also { actionLog.info("house electricity meter {} cleared{}", name, Actor.by()) }
        }
        call.respond(updated)
    }

    put("/api/settings/energy") {
        val body = call.receive<JsonObject>()
        val updated = call.acting {
            heating.energySettings.update { current -> current.patched(body).validated { heating.meters[it] != null } }
                .also { actionLog.info("energy settings {}{}", it.logged(), Actor.by()) }
        }
        call.respond(updated)
    }
}

/** openapi: Meter */
@Serializable
data class MeterView(
    val name: String,
    val online: Boolean,
    /** Running total in kWh. */
    val energy: Double?,
    /** Power now in W. */
    val power: Double?,
    /** No reading for a while: its use is unknown, not zero. */
    val stale: Boolean,
    val lastReading: String?,
    /** Whether this is the house electricity meter. */
    val inUse: Boolean,
    /** Whether the admin monitors this device's use (part of the house meter's, never added to it). */
    val monitored: Boolean = false,
    /** Id of the room this monitored device is in; null when it is in no room. */
    val room: String? = null,
)

fun HeatingApp.meterView(meter: Meter, house: String? = energySettings.get().electricityMeter) = MeterView(
    name = meter.name,
    online = meter.online,
    energy = meter.energy,
    power = meter.power,
    stale = meters.isStale(meter),
    lastReading = meter.lastReading?.atZone(config.timezone)?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    inUse = meter.name == house,
    monitored = meter.name in monitoredMeters,
    room = monitoredMeters.roomOf(meter.name),
)

/** [this] with the fields present in [body] replaced; fields left out are unchanged. */
private fun EnergySettings.patched(body: JsonObject): EnergySettings {
    fun price(key: String, current: Double): Double {
        val element = body[key] ?: return current
        return (element as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            ?: throw ApiException.badRequest("$key must be a number")
    }
    return copy(
        gasPencePerKwh = price("gasPencePerKwh", gasPencePerKwh),
        gasStandingPencePerDay = price("gasStandingPencePerDay", gasStandingPencePerDay),
        boilerAverageKw = price("boilerAverageKw", boilerAverageKw),
        electricityPencePerKwh = price("electricityPencePerKwh", electricityPencePerKwh),
        electricityStandingPencePerDay = price("electricityStandingPencePerDay", electricityStandingPencePerDay),
        currency = when (val code = body["currency"]) {
            null -> currency
            else -> (code as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw ApiException.badRequest("currency must be a currency code")
        },
        electricityMeter = when (val meter = body["electricityMeter"]) {
            null -> electricityMeter
            JsonNull -> null
            else -> (meter as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw ApiException.badRequest("electricityMeter must be a meter name or null")
        },
    )
}

private fun EnergySettings.logged() =
    "gas ${gasPencePerKwh}p/kWh + ${gasStandingPencePerDay}p/day, boiler $boilerAverageKw kW, " +
        "electricity ${electricityPencePerKwh}p/kWh + ${electricityStandingPencePerDay}p/day, meter ${electricityMeter ?: "none"}, $currency"
