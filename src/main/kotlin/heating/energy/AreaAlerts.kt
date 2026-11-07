package heating.energy

import heating.ApiException
import heating.Periodic
import heating.heatingJson
import heating.store.Store
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/** Who an area's notification goes to. */
@Serializable
enum class AlertAudience {
    /** The admin who set it up. */
    @kotlinx.serialization.SerialName("me") ME,

    /** Everyone with energy access: every admin, and members an admin gave it. */
    @kotlinx.serialization.SerialName("everyone") EVERYONE,
}

/** An area's notification: after [hours] of using power, tell [notify]; [byUserId] set it up. */
@Serializable
data class AreaAlert(val hours: Double, val notify: AlertAudience, val byUserId: String)

/** PUT /api/energy/areas/{id}/alert. openapi: AreaAlertRequest */
@Serializable
data class AreaAlertRequest(val hours: Double, val notify: AlertAudience)

/** GET /api/energy/areas/{id}/alert: all null with none. [by] is the username of who set it up, [mine] whether that is the caller. openapi: AreaAlertView */
@Serializable
data class AreaAlertView(val hours: Double? = null, val notify: AlertAudience? = null, val by: String? = null, val mine: Boolean = false)

/**
 * Each energy area's notification, kept as one document in the store, and the watch on the areas' live power. An area
 * is using power while its reading is over [ON_WATTS]; once it has been for the notification's hours without a break,
 * the notification is sent once, and not again until the area has dropped to [ON_WATTS] or under and come back on. A
 * missing reading is not a break.
 */
class AreaAlerts(
    private val store: Store,
    /** Each area's live power now. */
    private val areaPower: () -> List<AreaPower>,
    /** Sends a notification to each of the user ids. */
    private val notify: (userIds: List<String>, title: String, body: String) -> Unit,
    /** Everyone [AlertAudience.EVERYONE] reaches. */
    private val everyone: () -> List<String>,
) {
    private var alerts: Map<String, AreaAlert> = store.get(KEY)?.let { heatingJson.decodeFromString(SERIALIZER, it) } ?: emptyMap()

    /** Since when each area has been using power without a break. */
    private val onSince = mutableMapOf<String, Instant>()

    /** Areas whose notification has gone out for the stretch they are in. */
    private val sent = mutableSetOf<String>()

    @Synchronized
    fun get(area: String): AreaAlert? = alerts[area]

    @Synchronized
    fun set(area: String, request: AreaAlertRequest, byUserId: String): AreaAlert {
        val hours = request.hours
        if (!hours.isFinite() || hours < 0.5 || hours > 24 || (hours * 2) % 1.0 != 0.0) {
            throw ApiException.badRequest("hours must be from 0.5 to 24 in steps of 0.5")
        }
        val alert = AreaAlert(hours, request.notify, byUserId)
        save(alerts + (area to alert))
        sent -= area
        return alert
    }

    @Synchronized
    fun clear(area: String) = save(alerts - area)

    val tick = Periodic { now -> check(now.toInstant()) }

    @Synchronized
    fun check(now: Instant) {
        areaPower().forEach { area ->
            val watts = area.watts ?: return@forEach
            if (watts <= ON_WATTS) {
                onSince -= area.area
                sent -= area.area
                return@forEach
            }
            val since = onSince.getOrPut(area.area) { now }
            val alert = alerts[area.area] ?: return@forEach
            if (area.area in sent || Duration.between(since, now) < Duration.ofMinutes((alert.hours * 60).toLong())) return@forEach
            sent += area.area
            val to = if (alert.notify == AlertAudience.ME) listOf(alert.byUserId) else everyone()
            notify(to, "${area.name} still on", "${area.name} has been using power for ${hoursText(alert.hours)}, now ${watts.roundToInt()} W")
        }
    }

    private fun save(updated: Map<String, AreaAlert>) {
        store.put(KEY, heatingJson.encodeToString(SERIALIZER, updated))
        alerts = updated
    }

    companion object {
        private const val KEY = "area-alerts"
        private val SERIALIZER = MapSerializer(String.serializer(), AreaAlert.serializer())

        /** Over this many watts an area is using power, not just standing by. */
        const val ON_WATTS = 5.0

        /** "1 h", "1 h 30 min", "30 min". */
        fun hoursText(hours: Double): String {
            val minutes = (hours * 60).roundToInt()
            val h = minutes / 60
            val m = minutes % 60
            return listOfNotNull(if (h > 0) "$h h" else null, if (m > 0) "$m min" else null).joinToString(" ")
        }
    }
}
