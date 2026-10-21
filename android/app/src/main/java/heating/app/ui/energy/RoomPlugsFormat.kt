package heating.app.ui.energy

import heating.app.data.Meter
import heating.app.data.OfflinePeriod
import heating.app.data.RoomPlug
import heating.app.data.RoomPlugs
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** What a screen reader calls the warning triangle; its message is the description. */
const val COST_INCOMPLETE = "Cost incomplete"

private const val COULD_NOT_COUNT = "Some use could not be counted."

private val HOURS = DateTimeFormatter.ofPattern("HH:mm")

/** "Today £1.25": the room's cost today, above its plugs. */
fun roomTotal(plugs: RoomPlugs): String = "Today ${formatCost(plugs.cost, plugs.currency)}"

/** "85 W now · £0.11 today", or "Offline · £0.11 today" while offline now, never its last power. */
fun plugLine(plug: RoomPlug, currency: String): String =
    "${if (plug.offlineSince != null) "Offline" else "${formatPower(plug.power)} now"} · ${formatCost(plug.cost, currency)} today"

/** "Offline since 14:20. Cost will update when it's back.", the triangle's message on a plug offline now. */
fun offlineNow(since: String): String = "Offline since ${hours(since)}. Cost will update when it's back."

/** The triangle's message on a plug using power while its energy total is not going up. */
const val TOTAL_STUCK = "Using power but its energy total isn't going up. Cost may be too low."

/** The message for a plug offline now or whose total is stuck; offline now wins, as then there is no power to go by. */
fun plugNowWarning(offlineSince: String?, totalStuckSince: String?): String? = when {
    offlineSince != null -> offlineNow(offlineSince)
    totalStuckSince != null -> TOTAL_STUCK
    else -> null
}

/** "14:20", in the service's time zone, which is the household's. */
private fun hours(iso: String): String = OffsetDateTime.parse(iso).format(HOURS)

/** "14:20 to 15:00", several joined with "and"; in the service's time zone, which is the household's. */
private fun periods(offline: List<OfflinePeriod>): String =
    offline.joinToString(" and ") { "${hours(it.from)} to ${hours(it.to)}" }

/** "Was offline 14:20 to 15:00. Some use could not be counted." or the offline-now or stuck-total message; null when complete. */
fun plugWarning(plug: RoomPlug): String? = plugNowWarning(plug.offlineSince, plug.totalStuckSince) ?: when {
    plug.incomplete -> "Was offline ${periods(plug.offline)}. $COULD_NOT_COUNT"
    else -> null
}

/** The room's triangle names each plug affected; null when the room's cost is complete. */
fun roomWarning(plugs: RoomPlugs): String? =
    if (!plugs.incomplete) null
    else plugs.plugs.filter { it.incomplete }.joinToString("; ") {
        when {
            it.offlineSince != null -> "${it.meter} offline since ${hours(it.offlineSince)}"
            it.totalStuckSince != null -> "${it.meter} using power but its energy total isn't going up"
            else -> "${it.meter} was offline ${periods(it.offline)}"
        }
    } + ". $COULD_NOT_COUNT"

/** "Add plug" offers monitored plugs not in [room] already; one in another room moves. */
fun plugsToAdd(meters: List<Meter>, room: String): List<Meter> = meters.filter { it.monitored && it.room != room }.sortedBy { it.name }
