package heating.groups

import heating.ApiException
import heating.Change
import heating.ChangeNotifier
import heating.heatingJson
import heating.rooms.RoomService
import heating.store.Store
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

/** A named set of rooms. It holds no settings: changing a group changes each of its [rooms]. */
@Serializable
data class Group(val id: String, val name: String, val rooms: List<String> = emptyList())

/** Owns groups: create, rename, change rooms, delete. Persists them as one JSON document. */
class GroupService(
    private val store: Store,
    private val rooms: RoomService,
    private val changes: ChangeNotifier,
) {
    private val groups = linkedMapOf<String, Group>()

    fun start() {
        synchronized(this) { load().forEach { groups[it.id] = it } }
        changes.subscribe { if (it is Change.RoomDeleted) roomDeleted(it.roomId) }
    }

    fun list(): List<Group> = synchronized(this) { groups.values.toList() }

    fun get(id: String): Group = synchronized(this) { groups[id] } ?: throw ApiException.notFound("group not found")

    /** Groups that contain room [roomId]. */
    fun containing(roomId: String): List<Group> = list().filter { roomId in it.rooms }

    fun create(name: String, roomIds: List<String>): Group {
        val clean = cleanName(name)
        val group = synchronized(this) {
            requireUniqueName(clean, exceptId = null)
            Group(UUID.randomUUID().toString(), clean, checkedRooms(roomIds)).also {
                groups[it.id] = it
                persist()
            }
        }
        changes.emit(Change.GroupsChanged)
        return group
    }

    fun update(id: String, name: String?, roomIds: List<String>?): Group {
        val group = synchronized(this) {
            val current = groups[id] ?: throw ApiException.notFound("group not found")
            val clean = name?.let(::cleanName)?.also { requireUniqueName(it, exceptId = id) }
            current.copy(name = clean ?: current.name, rooms = roomIds?.let(::checkedRooms) ?: current.rooms).also {
                groups[id] = it
                persist()
            }
        }
        changes.emit(Change.GroupsChanged)
        return group
    }

    fun delete(id: String) {
        synchronized(this) {
            groups.remove(id) ?: throw ApiException.notFound("group not found")
            persist()
        }
        changes.emit(Change.GroupsChanged)
    }

    private fun roomDeleted(roomId: String) {
        val changed = synchronized(this) {
            val holding = groups.values.filter { roomId in it.rooms }
            holding.forEach { groups[it.id] = it.copy(rooms = it.rooms - roomId) }
            if (holding.isNotEmpty()) persist()
            holding.isNotEmpty()
        }
        if (changed) changes.emit(Change.GroupsChanged)
    }

    private fun cleanName(name: String): String =
        name.trim().ifEmpty { throw ApiException.badRequest("name is required") }

    private fun checkedRooms(roomIds: List<String>): List<String> {
        val known = rooms.list().map { it.id }.toSet()
        roomIds.firstOrNull { it !in known }?.let { throw ApiException.badRequest("unknown room $it") }
        return roomIds.distinct()
    }

    private fun requireUniqueName(name: String, exceptId: String?) {
        if (groups.values.any { it.id != exceptId && it.name.equals(name, ignoreCase = true) }) {
            throw ApiException.conflict("a group called $name already exists")
        }
    }

    private fun load(): List<Group> =
        store.get(KEY)?.let { heatingJson.decodeFromString(ListSerializer(Group.serializer()), it) } ?: emptyList()

    private fun persist() = store.put(KEY, heatingJson.encodeToString(ListSerializer(Group.serializer()), groups.values.toList()))

    private companion object {
        const val KEY = "groups"
    }
}
