package heating.energy

import heating.heatingJson
import heating.store.Store
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Meters other than the house meter whose use the admin wants to see, such as a smart plug on the
 * fridge. Their use is part of the house meter's, so it is shown as "of which", never added to it.
 * A monitored meter is in at most one room; one that is not a room's own (a shared kettle) is in none.
 */
class MonitoredMeters(private val store: Store) {
    private val names: MutableSet<String> =
        (store.get(KEY)?.let { heatingJson.decodeFromString(ListSerializer(String.serializer()), it) } ?: emptyList()).toMutableSet()

    /** Meter name to room id. */
    private val rooms: MutableMap<String, String> =
        (store.get(ROOMS_KEY)?.let { heatingJson.decodeFromString(ROOMS, it) } ?: emptyMap()).toMutableMap()

    @Synchronized
    fun all(): List<String> = names.sorted()

    @Synchronized
    operator fun contains(name: String): Boolean = name in names

    @Synchronized
    fun monitor(name: String) {
        if (names.add(name)) save()
    }

    /** Returns whether [name] was monitored. */
    @Synchronized
    fun stop(name: String): Boolean = names.remove(name).also {
        if (it) save()
        if (rooms.remove(name) != null) saveRooms()
    }

    @Synchronized
    fun roomOf(name: String): String? = rooms[name]

    /** Puts monitored [name] in [room], taking it out of any other. Returns false if [name] is not monitored. */
    @Synchronized
    fun putInRoom(name: String, room: String): Boolean {
        if (name !in names) return false
        if (rooms.put(name, room) != room) saveRooms()
        return true
    }

    /** Returns whether [name] was in [room]. */
    @Synchronized
    fun takeOutOfRoom(name: String, room: String): Boolean = rooms.remove(name, room).also { if (it) saveRooms() }

    /** Takes every meter out of [room], which no longer exists. Their recorded use is kept. */
    @Synchronized
    fun roomDeleted(room: String) {
        if (rooms.values.removeAll { it == room }) saveRooms()
    }

    /**
     * [name] left Zigbee2MQTT (unpaired, or renamed there): it is no longer monitored or in a room. Its recorded use is
     * kept. A renamed plug comes back under its new name, waiting to be set up.
     */
    @Synchronized
    fun meterRemoved(name: String) {
        stop(name)
    }

    /** Stops monitoring every meter for which [known] is false, such as one that left Zigbee2MQTT while the service was down. */
    @Synchronized
    fun prune(known: (String) -> Boolean) {
        names.filterNot(known).forEach(::stop)
    }

    private fun save() = store.put(KEY, heatingJson.encodeToString(ListSerializer(String.serializer()), names.sorted()))

    private fun saveRooms() = store.put(ROOMS_KEY, heatingJson.encodeToString(ROOMS, rooms.toSortedMap()))

    companion object {
        private const val KEY = "monitored-meters"
        private const val ROOMS_KEY = "monitored-meter-rooms"
        private val ROOMS = MapSerializer(String.serializer(), String.serializer())
    }
}
