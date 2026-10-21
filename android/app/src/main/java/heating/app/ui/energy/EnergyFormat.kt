package heating.app.ui.energy

import heating.app.data.EnergyDashboard
import heating.app.data.EnergyPricesRequest
import heating.app.data.EnergySettings
import heating.app.data.Meter
import java.util.Locale
import kotlin.math.roundToInt

/** The prices an admin edits, in order. [per] is null for the boiler rate, which is not money. */
enum class PriceField(val label: String, private val per: String?, val value: (EnergySettings) -> Double) {
    GAS("Gas", "kWh", { it.gasPencePerKwh }),
    GAS_STANDING("Gas standing charge", "day", { it.gasStandingPencePerDay }),
    ELECTRICITY("Electricity", "kWh", { it.electricityPencePerKwh }),
    ELECTRICITY_STANDING("Electricity standing charge", "day", { it.electricityStandingPencePerDay }),
    BOILER("Boiler average", null, { it.boilerAverageKw });

    /** "p per kWh" in pounds, "c per kWh" in euros; "kW" for the boiler. */
    fun unit(currency: String): String = per?.let { "${minorUnit(currency)} per $it" } ?: "kW"
}

/** The currencies an admin can choose, code to label; any other already set is offered by its code. */
private val CURRENCIES = listOf("GBP" to "£ Pound (GBP)", "EUR" to "€ Euro (EUR)", "USD" to "$ US dollar (USD)")

/** The list, plus [current] when it is another currency. */
fun currencyChoices(current: String): List<Pair<String, String>> =
    if (CURRENCIES.any { it.first == current }) CURRENCIES else CURRENCIES + (current to current)

/** "€ Euro (EUR)", or the bare code for a currency not in the list. */
fun currencyLabel(code: String): String = CURRENCIES.firstOrNull { it.first == code }?.second ?: code

/** "Currency: £ Pound (GBP)", under the prices. */
fun currencyLine(settings: EnergySettings): String = "Currency: ${currencyLabel(settings.currency)}"

const val NOT_CONVERTED = "Prices are not converted. Check them for the new currency."

/** Prices are entered in the currency's minor unit: "p" for pence, "c" for cents. */
fun minorUnit(currency: String): String = if (currency == "GBP") "p" else "c"

/** "£", "€", or the code itself when Java does not know the currency. */
private fun symbol(currency: String): String =
    runCatching { java.util.Currency.getInstance(currency).getSymbol(Locale.UK) }.getOrDefault(currency)

private fun oneDecimal(v: Double) = String.format(Locale.UK, "%.1f", v)

/** "27.0p per kWh", "27.0c per kWh", "12.0 kW". */
fun formatPrice(field: PriceField, settings: EnergySettings): String {
    val v = oneDecimal(field.value(settings))
    val unit = field.unit(settings.currency)
    return if (field == PriceField.BOILER) "$v $unit" else "$v$unit"
}

/** What the admin has typed for each price. */
typealias PriceDraft = Map<PriceField, String>

fun priceDraft(settings: EnergySettings): PriceDraft = PriceField.entries.associateWith { field -> field.value(settings).toString() }

/**
 * The prices to send, or null while one is not a number, is negative, or the boiler rate is not over 0 and at most 100 kW.
 * [currency] is sent only when it differs from [current]: prices are never converted.
 */
fun PriceDraft.toRequest(currency: String? = null, current: String? = null): EnergyPricesRequest? {
    val v = PriceField.entries.associateWith { this[it]?.trim()?.toDoubleOrNull() ?: return null }
    if (v.values.any { !it.isFinite() || it < 0 }) return null
    val boiler = v.getValue(PriceField.BOILER)
    if (boiler <= 0 || boiler > 100) return null
    return EnergyPricesRequest(
        gasPencePerKwh = v.getValue(PriceField.GAS),
        gasStandingPencePerDay = v.getValue(PriceField.GAS_STANDING),
        boilerAverageKw = boiler,
        electricityPencePerKwh = v.getValue(PriceField.ELECTRICITY),
        electricityStandingPencePerDay = v.getValue(PriceField.ELECTRICITY_STANDING),
        currency = currency?.takeIf { it != current },
    )
}

/** Minor units as money in [currency]: "£6.92", "£0.96", "€1.25"; "–" when unknown. */
fun formatCost(pence: Double?, currency: String): String =
    pence?.let { symbol(currency) + String.format(Locale.UK, "%.2f", it / 100) } ?: "–"

fun formatKwh(kwh: Double?): String = kwh?.let { "${oneDecimal(it)} kWh" } ?: "–"

/** "412 W", "1.2 kW". */
fun formatPower(watts: Double?): String = when {
    watts == null -> "–"
    watts >= 1000 -> "${oneDecimal(watts / 1000)} kW"
    else -> "${watts.roundToInt()} W"
}

const val NO_METER = "Choose an electricity meter to see electricity use"

/** One figure on the dashboard: "Gas", "£3.96", "36.0 kWh · 3.0 h boiler". */
data class EnergyLine(val label: String, val cost: String, val detail: String)

fun energyLines(d: EnergyDashboard): List<EnergyLine> = listOf(
    EnergyLine("Gas", formatCost(d.gasCost, d.currency), "${formatKwh(d.gasKwh)} · ${oneDecimal(d.boilerHours)} h boiler"),
    EnergyLine("Electricity", formatCost(d.electricityCost, d.currency), d.electricityKwh?.let(::formatKwh) ?: "No meter chosen"),
    EnergyLine("Standing charges", formatCost(d.standingCost, d.currency), "Charged per day"),
    EnergyLine("Total", formatCost(d.totalCost, d.currency), if (d.incomplete) "Some time missing" else ""),
)

/** One monitored device "of which" under electricity; [warning] is set when its cost is only so far. */
data class DeviceLine(val name: String, val cost: String, val detail: String, val warning: String?, val tapToRead: Boolean = false)

/** What the warning triangle on a plug's figures says when some readings were missing. */
fun incompleteWarning(missingMinutes: Int): String = "Incomplete: $missingMinutes min with no reading, so this is the cost so far"

/** A plug offline now says so, and its triangle is tapped to read since when; so is one whose total is stuck. */
fun deviceLines(d: EnergyDashboard): List<DeviceLine> = d.devices.map {
    val cost = formatCost(it.cost, d.currency)
    if (it.offlineSince != null) DeviceLine(it.meter, cost, "Offline · ${formatKwh(it.kwh)}", offlineNow(it.offlineSince), tapToRead = true)
    else if (it.totalStuckSince != null) DeviceLine(it.meter, cost, formatKwh(it.kwh), TOTAL_STUCK, tapToRead = true)
    else DeviceLine(it.meter, cost, formatKwh(it.kwh), if (it.incomplete) incompleteWarning(it.missingMinutes) else null)
}

/**
 * Each room's use "of which" under electricity, then plugs in no room grouped as "Not in a room", then "Everything
 * else" last. Empty with no plugs in rooms, so the card is hidden.
 */
fun roomLines(d: EnergyDashboard): List<DeviceLine> {
    if (d.rooms.isEmpty()) return emptyList()
    val rooms = d.rooms.map {
        DeviceLine(it.name, formatCost(it.cost, d.currency), formatKwh(it.kwh), if (it.incomplete) incompleteWarning(it.missingMinutes) else null)
    }
    val loose = d.devices.filter { it.room == null }
    val none = if (loose.isEmpty()) null else {
        // Plugs are usually stale together, so the longest is the closest to the time any had no reading.
        val warning = if (loose.any { it.incomplete }) incompleteWarning(loose.maxOf { it.missingMinutes }) else null
        val detail = "${formatKwh(loose.sumOf { it.kwh })} · ${loose.joinToString { it.meter }}"
        DeviceLine("Not in a room", formatCost(loose.sumOf { it.cost }, d.currency), detail, warning)
    }
    val rest = d.everythingElse?.let {
        DeviceLine("Everything else", formatCost(it.cost, d.currency), formatKwh(it.kwh), if (it.incomplete) incompleteWarning(it.missingMinutes) else null)
    }
    return rooms + listOfNotNull(none, rest)
}

/** The note shown instead of electricity while no house meter is chosen. */
fun noMeterNote(d: EnergyDashboard): String? = if (d.electricityMeter == null) NO_METER else null

/** One day's bar, each part as a fraction of the tallest day. */
data class DayBar(val date: String, val gas: Float, val electricity: Float, val standing: Float, val incomplete: Boolean) {
    val total: Float get() = gas + electricity + standing
}

/** A bar per day for week and month; none for today. */
fun dayBars(d: EnergyDashboard): List<DayBar> {
    if (d.period == "today") return emptyList()
    val max = d.days.maxOfOrNull { it.totalCost }?.takeIf { it > 0 } ?: 1.0
    return d.days.map {
        DayBar(it.date, (it.gasCost / max).toFloat(), ((it.electricityCost ?: 0.0) / max).toFloat(), (it.standingCost / max).toFloat(), it.incomplete)
    }
}

const val MAIN_METER_BUTTON = "Main meter (whole house)"

const val MONITOR_BUTTON = "Monitor this device"

const val MONITOR_HINT = "See what one appliance uses"

/** The room picker that follows "Monitor this device"; "Not now" leaves the plug in no room. */
fun roomPickerTitle(meter: String) = "Which room is $meter in?"
const val ROOM_PICKER_HINT = "Its use then shows as part of that room's. Leave a shared plug in no room."
const val NOT_NOW = "Not now"
const val MAIN_METER_HINT = "Measures all electricity coming in"
const val MAIN_METER = "Main meter"

/** What a meter is called in a list: the house meter is the "Main meter", any other is just an electricity meter. */
fun meterKind(meter: Meter): String = if (meter.inUse) MAIN_METER else "Electricity meter"

const val NO_ROOM = "No room"

/** The button beside a monitored plug's room menu: it stops monitoring, leaves the Rooms screen and goes to the ignored devices. */
const val DISCONNECT = "Disconnect"

/** The monitored plugs the Rooms screen lists: those in no room. One in a room lives in that room's settings. */
fun plugsWithNoRoom(monitored: List<Meter>): List<Meter> = monitored.filter { it.room == null }

/** The name of the room a monitored plug is in, or "No room"; [roomNames] maps room id to name. */
fun plugRoomName(room: String?, roomNames: Map<String, String>): String = room?.let(roomNames::get) ?: NO_ROOM
