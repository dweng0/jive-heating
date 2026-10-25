package heating.boiler

import heating.Actor
import heating.ApiException
import heating.Change
import heating.ChangeNotifier
import heating.actionLog
import heating.activity.ActivityLog
import heating.activity.OVERRIDE
import heating.activity.displayName
import heating.boost.BoostControl
import heating.demand.DemandControl
import heating.heatingJson
import heating.notify.HeatingAlerts
import heating.rooms.RoomService
import heating.rooms.ZoneControl
import heating.store.Store
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Who turned the boiler off and when (ISO offset time). */
@Serializable
data class OverrideState(val by: String, val since: String)

/**
 * Whether an admin has forced the boiler off, kept in the store so it survives a restart. While it is on
 * [ZoneControl.effectiveTarget] is null for every zone, so no zone calls for heat and every valve is sent
 * off; the zones' own settings are not touched.
 */
class BoilerOverride(private val store: Store) {
    private var state: OverrideState? = store.get(KEY)?.let { heatingJson.decodeFromString(OverrideState.serializer(), it) }

    @Synchronized
    fun current(): OverrideState? = state

    val on: Boolean get() = current() != null

    @Synchronized
    fun set(value: OverrideState) {
        state = value
        store.put(KEY, heatingJson.encodeToString(OverrideState.serializer(), value))
    }

    /** Clears it and returns what was set, or null if it was not on. */
    @Synchronized
    fun clear(): OverrideState? {
        val previous = state ?: return null
        state = null
        store.put(KEY, "null")
        return previous
    }

    companion object {
        private const val KEY = "boilerOverride"
    }
}

/** Turns the boiler override on and off, and tells the feed, the log and the phones. */
class BoilerControl(
    private val override: BoilerOverride,
    private val rooms: RoomService,
    private val zones: ZoneControl,
    private val boosts: BoostControl,
    private val demand: DemandControl,
    private val changes: ChangeNotifier,
    private val now: () -> ZonedDateTime,
    private val activity: ActivityLog? = null,
    private val alerts: HeatingAlerts? = null,
) {
    private fun roomNames() = rooms.list().map { it.id to it.name }

    /** Force the boiler off: boosts cancelled, every valve sent off, heat demand false at once. */
    fun turnOff() {
        val by = Actor.name ?: "admin"
        if (override.on) throw ApiException.conflict("the boiler is already turned off")
        override.set(OverrideState(by, now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)))
        actionLog.info("boiler turned off by {}", by)
        boosts.clearAll()
        zones.applyAll()
        demand.forceOff()
        activity?.record(OVERRIDE, displayName(by), null, JsonPrimitive("off"))
        alerts?.boilerOverride(on = false, by = Actor.name, rooms = roomNames())
        changes.emit(Change.StatusChanged)
    }

    /** Let the zones heat again: every valve gets its zone's own target. Demand follows on the next tick. */
    fun turnOn() {
        val by = Actor.name ?: "admin"
        override.clear() ?: throw ApiException.conflict("the boiler is not turned off")
        actionLog.info("boiler turned back on by {}", by)
        zones.applyAll()
        activity?.record(OVERRIDE, displayName(by), null, JsonPrimitive("on"))
        alerts?.boilerOverride(on = true, by = Actor.name, rooms = roomNames())
        changes.emit(Change.StatusChanged)
    }
}
