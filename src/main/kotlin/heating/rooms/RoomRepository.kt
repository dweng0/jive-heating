package heating.rooms

import heating.heatingJson
import heating.store.Store
import kotlinx.serialization.builtins.ListSerializer

/** Persists all rooms as one JSON document. */
class RoomRepository(private val store: Store) {
    fun load(): List<Room> =
        store.get(KEY)?.let { heatingJson.decodeFromString(ListSerializer(Room.serializer()), it) } ?: emptyList()

    fun save(rooms: List<Room>) =
        store.put(KEY, heatingJson.encodeToString(ListSerializer(Room.serializer()), rooms))

    private companion object {
        const val KEY = "rooms"
    }
}
