package heating.rooms

import heating.ApiException
import heating.Change
import heating.ChangeNotifier
import heating.actionLog
import heating.logged
import heating.valves.Valve
import heating.valves.ValveCommander
import heating.valves.ValveRegistry
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns rooms: create, rename, delete, valve membership. Every mutation goes through
 * [update] or [mutate], which persist and announce the change.
 */
class RoomService(
    private val repository: RoomRepository,
    private val valves: ValveRegistry,
    private val commander: ValveCommander,
    private val changes: ChangeNotifier,
) {
    private val rooms = linkedMapOf<String, Room>()
    private val valveAddedListeners = CopyOnWriteArrayList<(Room, String) -> Unit>()
    private val valveLeftListeners = CopyOnWriteArrayList<(Room, String) -> Unit>()

    /** Called with the room and valve after a valve is put into a room. */
    fun onValveAdded(listener: (Room, String) -> Unit) {
        valveAddedListeners += listener
    }

    /** Called with the room (as it was) and valve after a valve is taken out of a room or its room is deleted. */
    fun onValveLeft(listener: (Room, String) -> Unit) {
        valveLeftListeners += listener
    }

    fun start() {
        synchronized(this) { repository.load().forEach { rooms[it.id] = it } }
        valves.onRemoved(::valveRemoved)
        valves.onStateChanged(::checkFrostProtection)
    }

    /** Last wrong frost protection each valve reported, so one wrong value is corrected only once. */
    private val wrongFrost = HashMap<String, Double>()

    /** A valve in a room that reports a frost protection other than ours is put back (it would heat while off). */
    private fun checkFrostProtection(valve: Valve) {
        val frost = valve.frostProtection ?: return
        val correct = frost == ValveCommander.FROST_PROTECTION
        val previous = synchronized(wrongFrost) { if (correct) wrongFrost.remove(valve.name) else wrongFrost.put(valve.name, frost) }
        if (correct || previous == frost || roomOf(valve.name) == null) return
        actionLog.info("valve {} frost protection {} -> {}", valve.name, frost.logged(), ValveCommander.FROST_PROTECTION.logged())
        commander.setFrostProtection(valve.name)
    }

    fun list(): List<Room> = synchronized(this) { rooms.values.toList() }

    fun get(id: String): Room = synchronized(this) { rooms[id] } ?: throw ApiException.notFound("room not found")

    fun roomOf(valve: String): Room? = synchronized(this) { rooms.values.firstOrNull { valve in it.valves } }

    fun create(name: String): Room {
        val clean = name.trim()
        if (clean.isEmpty()) throw ApiException.badRequest("name is required")
        val room = synchronized(this) {
            requireUniqueName(clean, exceptId = null)
            Room(id = UUID.randomUUID().toString(), name = clean).also {
                rooms[it.id] = it
                persist()
            }
        }
        changes.emit(Change.RoomChanged(room.id))
        return room
    }

    fun rename(id: String, name: String): Room {
        val clean = name.trim()
        if (clean.isEmpty()) throw ApiException.badRequest("name is required")
        return update(id) {
            requireUniqueName(clean, exceptId = id)
            it.copy(name = clean)
        }
    }

    fun delete(id: String) {
        val room = synchronized(this) {
            val room = rooms.remove(id) ?: throw ApiException.notFound("room not found")
            persist()
            room
        }
        room.valves.forEach(commander::off)
        room.valves.forEach { valve -> valveLeftListeners.forEach { it(room, valve) } }
        changes.emit(Change.RoomDeleted(id))
    }

    fun assignValve(id: String, valve: String): Room {
        if (valves[valve] == null) throw ApiException.notFound("valve not found")
        var newlyAssigned = false
        val room = update(id) { room ->
            if (valve in room.valves) return@update room
            val owner = rooms.values.firstOrNull { valve in it.valves }
            if (owner != null) throw ApiException.conflict("valve is already in room ${owner.name}")
            newlyAssigned = true
            room.copy(valves = room.valves + valve)
        }
        if (newlyAssigned) {
            commander.applyDefaults(valve)
            valveAddedListeners.forEach { it(room, valve) }
        }
        return room
    }

    fun removeValve(id: String, valve: String): Room {
        val before = get(id)
        val room = update(id) { room ->
            if (valve !in room.valves) throw ApiException.notFound("valve is not in this room")
            room.copy(valves = room.valves - valve, valveSettings = room.valveSettings - valve)
        }
        commander.off(valve)
        valveLeftListeners.forEach { it(before, valve) }
        return room
    }

    fun setSmartTemperatureControl(id: String, enabled: Boolean): Room {
        val room = update(id) { it.copy(smartTemperatureControl = enabled) }
        room.valves.forEach { commander.setSmartTemperatureControl(it, enabled) }
        return room
    }

    /**
     * Apply [change] to room [id] under the service lock, persist, and announce it.
     * [change] runs while holding the lock, so it can read other rooms safely.
     */
    fun update(id: String, change: (Room) -> Room): Room {
        val updated = synchronized(this) {
            val current = rooms[id] ?: throw ApiException.notFound("room not found")
            val next = change(current)
            if (next != current) {
                rooms[id] = next
                persist()
            }
            next
        }
        changes.emit(Change.RoomChanged(id))
        return updated
    }

    /**
     * Change one zone's settings. [zoneId] is the room id (grouped room) or "roomId:valve"
     * (independent room).
     */
    fun updateZone(zoneId: String, change: (ZoneSettings) -> ZoneSettings): Room =
        update(zoneId.substringBefore(':')) { room ->
            val zone = room.zones().firstOrNull { it.id == zoneId } ?: throw ApiException.notFound("zone not found")
            when (room.control) {
                Control.GROUPED -> room.copy(settings = change(room.settings))
                Control.INDEPENDENT -> {
                    val valve = zone.valves.single()
                    room.copy(valveSettings = room.valveSettings + (valve to change(zone.settings)))
                }
            }
        }

    /**
     * Switch a room between grouped and independent control. Grouped to independent copies the
     * room's settings to each valve; independent to grouped takes [sourceValve]'s settings.
     */
    fun switchControl(id: String, control: Control, sourceValve: String?): Room = update(id) { room ->
        if (room.control == control) return@update room
        when (control) {
            Control.INDEPENDENT -> room.copy(
                control = control,
                valveSettings = room.valves.associateWith { room.settings },
            )
            Control.GROUPED -> {
                if (sourceValve == null) throw ApiException.badRequest("sourceValve is required")
                if (sourceValve !in room.valves) throw ApiException.badRequest("sourceValve is not in this room")
                room.copy(
                    control = control,
                    settings = room.valveSettings[sourceValve] ?: room.settings,
                    valveSettings = emptyMap(),
                )
            }
        }
    }

    private fun valveRemoved(valve: String) {
        val room = roomOf(valve) ?: return
        update(room.id) {
            it.copy(
                valves = it.valves - valve,
                valveSettings = it.valveSettings - valve,
                notices = it.notices + "$valve was removed from Zigbee2MQTT",
            )
        }
    }

    private fun requireUniqueName(name: String, exceptId: String?) {
        if (rooms.values.any { it.id != exceptId && it.name.equals(name, ignoreCase = true) }) {
            throw ApiException.conflict("a room called $name already exists")
        }
    }

    private fun persist() = repository.save(rooms.values.toList())
}
