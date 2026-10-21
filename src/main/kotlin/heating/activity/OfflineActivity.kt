package heating.activity

import heating.energy.MeterRegistry
import heating.energy.MonitoredMeters
import heating.rooms.Room
import heating.rooms.RoomService
import heating.sensors.SensorRegistry
import heating.valves.ValveRegistry
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * Records a valve, sensor or monitored plug losing or regaining contact, once per change. Valves and sensors
 * in no room are skipped; a plug in no room is recorded with no room. Plug events name the plug as their
 * device (valve and sensor events name only the room) and are admin only.
 */
class OfflineActivity(
    private val activity: ActivityLog,
    private val rooms: RoomService,
    private val valves: ValveRegistry,
    private val sensors: SensorRegistry,
    private val meters: MeterRegistry,
    private val monitored: MonitoredMeters,
) {
    private val online = ConcurrentHashMap<String, Boolean>()

    fun start() {
        valves.onStateChanged { valve -> changed(valve.name, valve.online, rooms.roomOf(valve.name)) }
        sensors.onStateChanged { sensor -> changed(sensor.name, sensor.online, rooms.list().firstOrNull { it.sensor == sensor.name }) }
        meters.onOnlineChanged { meter -> plugChanged(meter.name, meter.online) }
    }

    private fun changed(device: String, isOnline: Boolean, room: Room?) {
        if (!flipped(device, isOnline) || room == null) return
        activity.record("offline", null, room.name, state(isOnline), roomIds = listOf(room.id))
    }

    private fun plugChanged(plug: String, isOnline: Boolean) {
        if (plug !in monitored || !flipped(plug, isOnline)) return
        val room = monitored.roomOf(plug)?.let { id -> rooms.list().firstOrNull { it.id == id } }
        activity.record("offline", null, room?.name, state(isOnline), roomIds = listOfNotNull(room?.id), device = plug, adminOnly = true)
    }

    /** Whether [device] is now [isOnline] after being the other way (a device not heard of yet counts as online). */
    private fun flipped(device: String, isOnline: Boolean): Boolean = (online.put(device, isOnline) ?: true) != isOnline

    private fun state(isOnline: Boolean) = JsonPrimitive(if (isOnline) "online" else "offline")
}
