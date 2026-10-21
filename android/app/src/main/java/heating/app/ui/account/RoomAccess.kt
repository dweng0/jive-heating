package heating.app.ui.account

import heating.app.data.AccountUser
import heating.app.data.Room

/** One tick box on a member's rooms: [ticked] when they can see the room. */
data class RoomTick(val id: String, val name: String, val ticked: Boolean)

/** How many rooms [user] sees, for the People list. */
fun roomsSummary(user: AccountUser): String = when {
    user.admin -> "Sees every room"
    user.rooms.isEmpty() -> "Sees no rooms"
    else -> "Sees ${user.rooms.size} room${if (user.rooms.size == 1) "" else "s"}"
}

/** A tick box per room for a member; none for an admin, who sees every room. */
fun roomTicks(user: AccountUser, rooms: List<Room>): List<RoomTick> =
    if (user.admin) emptyList() else rooms.map { RoomTick(it.id, it.name, it.id in user.rooms) }

/** A member's full new room list after ticking [roomId] on or off, in the order of [rooms] (every room). */
fun tickRoom(rooms: List<Room>, ids: List<String>, roomId: String, ticked: Boolean): List<String> =
    rooms.map { it.id }.filter { if (it == roomId) ticked else it in ids }
