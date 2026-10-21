package heating.energy

import heating.ApiException
import heating.heatingJson
import heating.store.Store
import kotlinx.serialization.Serializable

/**
 * Gas and electricity prices (in the [currency]'s minor unit, such as pence, including VAT), the boiler's average burn rate used to estimate
 * gas from boiler hours, and which meter measures the house's electricity. Costs are never stored:
 * the dashboard multiplies recorded kWh by these prices when it is read.
 *
 * openapi: EnergySettings
 */
@Serializable
data class EnergySettings(
    val gasPencePerKwh: Double = 11.0,
    val gasStandingPencePerDay: Double = 30.0,
    val boilerAverageKw: Double = 12.0,
    val electricityPencePerKwh: Double = 27.0,
    val electricityStandingPencePerDay: Double = 50.0,
    val electricityMeter: String? = null,
    /** ISO 4217 code every cost is in, such as "GBP" or "EUR". */
    val currency: String = "GBP",
    /** Things the admin should know, such as "Main Clamp was removed from Zigbee2MQTT". Choosing a meter clears them. */
    val notices: List<String> = emptyList(),
)

/** Throws a 400 unless every price is zero or more, the boiler rate is over 0 and at most 100 kW, the currency is a known ISO 4217 code, and the meter [isMeter]. */
fun EnergySettings.validated(isMeter: (String) -> Boolean): EnergySettings {
    listOf(
        "gasPencePerKwh" to gasPencePerKwh,
        "gasStandingPencePerDay" to gasStandingPencePerDay,
        "electricityPencePerKwh" to electricityPencePerKwh,
        "electricityStandingPencePerDay" to electricityStandingPencePerDay,
    ).forEach { (name, price) ->
        if (!price.isFinite() || price < 0) throw ApiException.badRequest("$name must be zero or more")
    }
    if (!boilerAverageKw.isFinite() || boilerAverageKw <= 0 || boilerAverageKw > 100) {
        throw ApiException.badRequest("boilerAverageKw must be over 0 and at most 100")
    }
    if (runCatching { java.util.Currency.getInstance(currency) }.isFailure) throw ApiException.badRequest("unknown currency $currency")
    if (electricityMeter != null && !isMeter(electricityMeter)) throw ApiException.badRequest("meter not found")
    return this
}

/** The household's [EnergySettings], kept as one document in the store. */
class EnergySettingsRepository(private val store: Store) {
    private var current: EnergySettings =
        store.get(KEY)?.let { heatingJson.decodeFromString(EnergySettings.serializer(), it) } ?: EnergySettings()

    @Synchronized
    fun get(): EnergySettings = current

    /** Make [meter] the house meter. */
    fun chooseMeter(meter: String) = update { it.copy(electricityMeter = meter, notices = emptyList()) }

    /** [meter] left Zigbee2MQTT: if it was the house meter, stop using it and say why. */
    fun meterRemoved(meter: String) = update {
        if (it.electricityMeter != meter) it
        else it.copy(electricityMeter = null, notices = it.notices + "$meter was removed from Zigbee2MQTT")
    }

    @Synchronized
    fun update(change: (EnergySettings) -> EnergySettings): EnergySettings {
        val updated = change(current)
        if (updated != current) {
            store.put(KEY, heatingJson.encodeToString(EnergySettings.serializer(), updated))
            current = updated
        }
        return updated
    }

    companion object {
        private const val KEY = "energy"
    }
}
