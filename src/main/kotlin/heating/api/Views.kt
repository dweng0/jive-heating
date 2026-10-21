package heating.api

import heating.HeatingApp
import heating.groups.Group
import heating.rooms.BlockTimes
import heating.rooms.Control
import heating.rooms.EffectiveTarget
import heating.rooms.Mode
import heating.rooms.Room
import heating.rooms.ScheduleControl
import heating.rooms.Zone
import heating.sensors.Sensor
import heating.valves.Valve
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode

// Views mirror the schemas in src/main/resources/openapi.json — the contract with the Android app.

@Serializable
data class BoostView(val target: Double, val endsAt: String)

@Serializable
data class OverrideView(val target: Double, val until: String)

@Serializable
data class NextChangeView(val at: String, val target: Double)

/** openapi: Zone */
@Serializable
data class ZoneView(
    val id: String,
    val valves: List<String>,
    val mode: Mode,
    val manualTarget: Double,
    val target: Double?,
    val current: Double?,
    val heating: Boolean,
    val callingForHeat: Boolean,
    val nextChange: NextChangeView? = null,
    val boost: BoostView? = null,
    val override: OverrideView? = null,
)

/** openapi: Room */
@Serializable
data class RoomView(
    val id: String,
    val name: String,
    val control: Control,
    val mode: Mode,
    val current: Double?,
    val target: Double?,
    val heating: Boolean,
    val valves: List<String>,
    val zones: List<ZoneView>,
    val notices: List<String>,
    val smartTemperatureControl: Boolean,
    val sensor: String? = null,
    /** "sensor" while an online sensor measures the room, else "valves". */
    val temperatureSource: String = "valves",
)

/** openapi: Group */
@Serializable
data class GroupView(
    val id: String,
    val name: String,
    val rooms: List<String>,
    val current: Double?,
    val target: Double?,
    /** schedule, manual, off, or mixed when its rooms disagree */
    val mode: String,
    /** Every room in the group heating; [heatingRooms] names the ones that are */
    val heating: Boolean,
    val heatingRooms: List<String> = emptyList(),
)

/** openapi: Valve */
@Serializable
data class ValveView(
    val name: String,
    val room: String?,
    val online: Boolean,
    val currentTemperature: Double?,
    val reportedSetpoint: Double?,
    val runningState: String?,
    val battery: Int?,
    val lowBattery: Boolean,
    val openWindow: Boolean,
    val childLock: Boolean,
)

fun HeatingApp.zoneView(zone: Zone): ZoneView {
    val zoneValves = zone.valves.mapNotNull { valves[it] }
    return ZoneView(
        id = zone.id,
        valves = zone.valves,
        mode = zone.settings.mode,
        manualTarget = zone.settings.manualTarget,
        target = zones.effectiveTarget(zone.settings),
        current = roomSensors.temperatureOf(zone.roomId) ?: averageTemperature(zoneValves),
        heating = zoneValves.any { it.online && it.runningState == "heat" },
        callingForHeat = demand.zoneCalling(zone),
        boost = EffectiveTarget.activeBoost(zone.settings, now())?.let { BoostView(it.target, it.endsAt) },
        nextChange = if (zone.settings.mode == Mode.SCHEDULE) {
            BlockTimes.next(zone.settings.schedule, now()).let { (at, block) ->
                NextChangeView(at.format(ScheduleControl.ISO), block.target)
            }
        } else null,
        override = if (zone.settings.mode == Mode.SCHEDULE) {
            EffectiveTarget.activeOverride(zone.settings, now())?.let { OverrideView(it.target, it.until) }
        } else null,
    )
}

fun HeatingApp.roomView(room: Room): RoomView {
    val roomValves = room.valves.mapNotNull { valves[it] }
    val zones = room.zones().map { zoneView(it) }
    val sensorTemperature = roomSensors.temperature(room)
    return RoomView(
        id = room.id,
        name = room.name,
        control = room.control,
        mode = zones.firstOrNull()?.mode ?: room.settings.mode,
        current = sensorTemperature ?: averageTemperature(roomValves),
        target = if (room.control == Control.GROUPED) this.zones.effectiveTarget(room.settings) else null,
        heating = roomValves.any { it.online && it.runningState == "heat" },
        valves = room.valves,
        zones = zones,
        notices = room.notices,
        smartTemperatureControl = room.smartTemperatureControl,
        sensor = room.sensor,
        temperatureSource = if (sensorTemperature != null) "sensor" else "valves",
    )
}

fun HeatingApp.groupView(group: Group): GroupView {
    val members = rooms.list().filter { it.id in group.rooms }.map { roomView(it) }
    // Rooms without valves have nothing to agree on; only fall back to them when that is all there is.
    val deciding = members.filter { it.valves.isNotEmpty() }.ifEmpty { members }
    val modes = deciding.map { it.mode }.distinct()
    val targets = deciding.map { it.target }.distinct()
    val temps = members.mapNotNull { it.current }
    return GroupView(
        id = group.id,
        name = group.name,
        rooms = group.rooms,
        current = if (temps.isEmpty()) null else BigDecimal(temps.sum() / temps.size).setScale(1, RoundingMode.HALF_UP).toDouble(),
        target = targets.singleOrNull(),
        mode = when (modes.size) {
            0 -> Mode.SCHEDULE.serialName()
            1 -> modes.single().serialName()
            else -> "mixed"
        },
        heating = members.isNotEmpty() && members.all { it.heating },
        heatingRooms = group.rooms.filter { id -> members.any { it.id == id && it.heating } },
    )
}

private fun Mode.serialName() = name.lowercase()
/** openapi: Sensor */
@Serializable
data class SensorView(
    val name: String,
    val room: String?,
    val online: Boolean,
    val temperature: Double?,
    val battery: Int?,
    val lowBattery: Boolean,
)

fun HeatingApp.sensorView(sensor: Sensor) = SensorView(
    name = sensor.name,
    room = rooms.list().firstOrNull { it.sensor == sensor.name }?.name,
    online = sensor.online,
    temperature = sensor.temperature,
    battery = sensor.battery,
    lowBattery = (sensor.battery ?: 100) <= 20,
)

fun HeatingApp.valveView(valve: Valve) = ValveView(
    name = valve.name,
    room = rooms.roomOf(valve.name)?.name,
    online = valve.online,
    currentTemperature = valve.currentTemperature,
    reportedSetpoint = valve.reportedSetpoint,
    runningState = valve.runningState,
    battery = valve.battery,
    lowBattery = (valve.battery ?: 100) <= 20,
    openWindow = valve.openWindow,
    childLock = valve.childLock,
)

/** Average of online valves' temperatures, to one decimal place (half up). */
fun averageTemperature(valves: List<Valve>): Double? {
    val temps = valves.filter { it.online }.mapNotNull { it.currentTemperature }
    if (temps.isEmpty()) return null
    return BigDecimal(temps.sum() / temps.size).setScale(1, RoundingMode.HALF_UP).toDouble()
}
