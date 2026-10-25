package heating.sensors

import heating.Actor
import heating.ApiException
import heating.actionLog
import heating.logged
import heating.Periodic
import heating.rooms.Room
import heating.rooms.RoomService
import heating.valves.ValveCommander
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Room sensors: which room a sensor measures, and keeping that room's valves on the sensor
 * (`temperature_sensor_select` external plus `external_temperature_input`) while it is online,
 * or back on their own sensor while it is not.
 */
class SensorControl(
    private val rooms: RoomService,
    private val sensors: SensorRegistry,
    private val commander: ValveCommander,
    private val now: () -> Instant,
) {
    private enum class Source { EXTERNAL, INTERNAL }

    /** What each room's valves were last told to measure with. Absent: not known (e.g. after a restart). */
    private val source = ConcurrentHashMap<String, Source>()

    /** When each room's valves were last sent a sensor reading. */
    private val lastSent = ConcurrentHashMap<String, Instant>()

    /** The reading each room's valves were last sent. */
    private val lastPushed = ConcurrentHashMap<String, Double>()

    /** Re-sends the last reading every [RESEND_INTERVAL], so valves never act on a stale input. */
    val resend = Periodic { _ ->
        val time = now()
        rooms.list().forEach { room ->
            val reading = temperature(room) ?: return@forEach
            if (source[room.id] != Source.EXTERNAL) return@forEach
            val last = lastSent[room.id] ?: return@forEach
            if (Duration.between(last, time) >= RESEND_INTERVAL) {
                room.valves.forEach { commander.externalTemperature(it, reading) }
                lastSent[room.id] = time
                lastPushed[room.id] = reading
            }
        }
    }

    fun start() {
        sensors.onStateChanged(::sensorChanged)
        sensors.onRemoved(::sensorRemoved)
        rooms.onValveAdded { room, valve ->
            val reading = temperature(room)
            if (reading != null && source[room.id] == Source.EXTERNAL) commander.useExternalSensor(valve, reading)
        }
        rooms.onValveLeft { room, valve -> if (room.sensor != null) commander.useInternalSensor(valve) }
    }

    /** The reading of [room]'s sensor while it is online, to one decimal place; null to use the valves. */
    fun temperature(room: Room): Double? =
        room.sensor?.let { sensors[it] }?.takeIf { it.online }?.temperature?.let(::oneDecimal)

    /** [temperature] for the room with id [roomId]. */
    fun temperatureOf(roomId: String): Double? = rooms.list().firstOrNull { it.id == roomId }?.let(::temperature)

    fun roomOf(sensor: String): Room? = rooms.list().firstOrNull { it.sensor == sensor }

    /** Make [sensor] room [roomId]'s sensor, replacing any previous one. */
    fun assign(roomId: String, sensor: String): Room = synchronized(this) {
        if (sensors[sensor] == null) throw ApiException.notFound("sensor not found")
        val room = rooms.update(roomId) { room ->
            val owner = rooms.list().firstOrNull { it.sensor == sensor && it.id != roomId }
            if (owner != null) throw ApiException.conflict("sensor is already in room ${owner.name}")
            if (room.sensor == sensor) return@update room
            room.copy(sensor = sensor, notices = room.notices - offlineNotice(room.sensor))
        }
        actionLog.info("room sensor {} -> {}{}", room.name, sensor, Actor.by())
        follow(room)
        room
    }

    /** Take [sensor] out of room [roomId]; its valves go back to their own sensor. */
    fun remove(roomId: String, sensor: String): Room = synchronized(this) {
        val room = rooms.update(roomId) { room ->
            if (room.sensor != sensor) throw ApiException.notFound("sensor is not in this room")
            room.copy(sensor = null, notices = room.notices - offlineNotice(sensor))
        }
        actionLog.info("room sensor {} removed {}{}, back on valves", room.name, sensor, Actor.by())
        useInternal(room)
        room
    }

    private fun sensorChanged(sensor: Sensor) = synchronized(this) {
        var room = roomOf(sensor.name) ?: return
        val notice = offlineNotice(sensor.name)
        if (!sensor.online && notice !in room.notices) {
            room = rooms.update(room.id) { it.copy(notices = it.notices + notice) }
        } else if (sensor.online && notice in room.notices) {
            room = rooms.update(room.id) { it.copy(notices = it.notices - notice) }
        }
        follow(room)
    }

    private fun sensorRemoved(sensor: String) = synchronized(this) {
        val room = roomOf(sensor) ?: return
        val updated = rooms.update(room.id) {
            it.copy(
                sensor = null,
                notices = it.notices - offlineNotice(sensor) + "$sensor was removed from Zigbee2MQTT",
            )
        }
        actionLog.info("sensor {} removed from Zigbee2MQTT, {} back on valves", sensor, room.name)
        useInternal(updated)
    }

    /**
     * Point [room]'s valves at its sensor while it has a reading, else back at their own sensor.
     * Valves already on the sensor get just the reading.
     */
    private fun follow(room: Room) {
        val temperature = temperature(room)
        when {
            temperature == null -> if (source[room.id] != Source.INTERNAL) {
                val why = if (room.sensor?.let { sensors[it] }?.online == true) "has no reading" else "offline"
                actionLog.info("sensor {} {}, {} back on valves", room.sensor, why, room.name)
                useInternal(room)
            }
            source[room.id] == Source.EXTERNAL -> {
                // Every write wakes a sleeping valve's queue: pass a reading on only when it moved, else wait for the resend.
                val pushed = lastPushed[room.id]
                if (pushed == null || abs(temperature - pushed) >= MIN_CHANGE) {
                    room.valves.forEach { commander.externalTemperature(it, temperature) }
                    lastSent[room.id] = now()
                    lastPushed[room.id] = temperature
                }
            }
            else -> {
                actionLog.info("sensor {} online, {} on sensor at {}", room.sensor, room.name, temperature.logged())
                room.valves.forEach { commander.useExternalSensor(it, temperature) }
                source[room.id] = Source.EXTERNAL
                lastSent[room.id] = now()
                lastPushed[room.id] = temperature
            }
        }
    }

    private fun useInternal(room: Room) {
        room.valves.forEach(commander::useInternalSensor)
        source[room.id] = Source.INTERNAL
        lastSent.remove(room.id)
        lastPushed.remove(room.id)
    }

    private fun offlineNotice(sensor: String?) = "$sensor is offline"

    private fun oneDecimal(value: Double) = BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toDouble()

    companion object {
        val RESEND_INTERVAL: Duration = Duration.ofMinutes(10)

        /** A reading closer than this to the last one sent waits for the next resend. */
        const val MIN_CHANGE = 0.5
    }
}
