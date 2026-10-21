package heating.api

import heating.Change
import heating.HeatingApp
import heating.activity.forViewer
import heating.auth.Caller
import io.ktor.server.auth.principal
import heating.heatingJson
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.concurrent.atomic.AtomicReference

/** openapi: LiveEvent */
@Serializable
data class LiveEvent(
    val type: String,
    val room: RoomView? = null,
    val roomId: String? = null,
    val status: StatusView? = null,
    val valve: ValveView? = null,
    val groups: List<GroupView>? = null,
    val sensor: SensorView? = null,
    val activity: ActivityView? = null,
)

/** The events a [Change] turns into, in order, before filtering for who is listening. */
private fun HeatingApp.liveEvents(change: Change): List<LiveEvent> =
    when (change) {
        is Change.RoomChanged -> listOfNotNull(
            runCatching { rooms.get(change.roomId) }.getOrNull()
                ?.let { LiveEvent("room.updated", room = roomView(it)) },
            groupsEventIfIn(change.roomId),
        )
        is Change.RoomDeleted -> listOf(LiveEvent("room.deleted", roomId = change.roomId))
        is Change.ValveChanged -> {
            val valveEvent = valves[change.valve]?.let { LiveEvent("valve.updated", valve = valveView(it)) }
            val roomEvent = rooms.roomOf(change.valve)?.let { LiveEvent("room.updated", room = roomView(it)) }
            listOfNotNull(valveEvent, roomEvent, rooms.roomOf(change.valve)?.let { groupsEventIfIn(it.id) })
        }
        is Change.SensorChanged -> {
            val sensorEvent = sensors[change.sensor]?.let { LiveEvent("sensor.updated", sensor = sensorView(it)) }
            val roomEvent = rooms.list().firstOrNull { it.sensor == change.sensor }
                ?.let { LiveEvent("room.updated", room = roomView(it)) }
            listOfNotNull(sensorEvent, roomEvent)
        }
        Change.StatusChanged -> listOf(LiveEvent("status.updated", status = statusView()))
        Change.GroupsChanged -> listOf(groupsEvent())
        is Change.AccessChanged -> listOf(LiveEvent(ACCESS_UPDATED))
        is Change.ActivityAdded -> listOf(LiveEvent("activity.added", activity = change.activity.view(this)))
    }

private const val ACCESS_UPDATED = "access.updated"

/**
 * What one socket's user may see: their rooms (null: every room) and whether they see the whole house.
 * Looked up again whenever an admin changes the user's access, so an open socket follows the new set.
 */
private class Viewer(val admin: Boolean, val rooms: Set<String>?) {
    fun canSee(roomId: String) = rooms == null || roomId in rooms

    /** Members get only their own rooms' updates; valves, sensors, status and groups are the whole house. */
    fun sees(event: LiveEvent): Boolean = when (event.type) {
        "room.updated" -> canSee(event.room!!.id)
        "room.deleted" -> canSee(event.roomId!!)
        else -> admin
    }
}

private fun HeatingApp.viewerOf(caller: Caller): Viewer? {
    val id = caller.userId ?: return Viewer(admin = caller.admin, rooms = null)
    val user = users.list().find { it.id == id } ?: return null
    return Viewer(user.admin, if (user.admin) null else user.rooms.orEmpty().toSet())
}

/** The JSON messages [change] turns into for [viewer], in order. */
private fun HeatingApp.liveMessages(change: Change, viewer: Viewer): List<String> {
    if (change is Change.ActivityAdded) {
        val shown = change.activity.forViewer(viewer.rooms?.let { rooms -> { id: String -> id in rooms } }, viewer.admin) ?: return emptyList()
        return listOf(heatingJson.encodeToString(LiveEvent("activity.added", activity = shown.view(this))))
    }
    return liveEvents(change).filter(viewer::sees).map { heatingJson.encodeToString(it) }
}

private fun HeatingApp.groupsEvent() = LiveEvent("groups.updated", groups = groups.list().map { groupView(it) })

/** A room's change also changes the groups it is in. */
private fun HeatingApp.groupsEventIfIn(roomId: String) = if (groups.containing(roomId).isEmpty()) null else groupsEvent()

private const val CLIENT_QUEUE = 256

fun Route.liveRoutes(heating: HeatingApp) {
    webSocket("/ws") {
        // Bounded per-connection queue: emit() runs on MQTT/HTTP threads and must never block.
        // A client too slow to drain it is dropped.
        val queue = Channel<String>(CLIENT_QUEUE)
        val caller = call.principal<Caller>()!!
        // The caller's rooms as of sign-in may be stale; follow the user's access as it is now.
        // Changes are emitted from MQTT and HTTP threads alike.
        val viewer = AtomicReference(heating.viewerOf(caller))
        val unsubscribe = heating.changes.subscribe { change ->
            try {
                val messages = if (change is Change.AccessChanged) {
                    if (change.userId != caller.userId) return@subscribe
                    viewer.set(heating.viewerOf(caller))
                    listOf(heatingJson.encodeToString(LiveEvent(ACCESS_UPDATED)))
                } else {
                    viewer.get()?.let { heating.liveMessages(change, it) }.orEmpty()
                }
                // A deleted room is off every list already; this socket's copy drops it after telling them.
                if (change is Change.RoomDeleted) {
                    viewer.updateAndGet { v -> v?.rooms?.let { Viewer(v.admin, it - change.roomId) } ?: v }
                }
                messages.forEach { msg ->
                    if (queue.trySend(msg).isFailure) queue.close()
                }
                // A deleted user's socket closes.
                if (viewer.get() == null) queue.close()
            } catch (_: Exception) {
                // a view-building failure must never reach the emitter
            }
        }
        if (viewer.get() == null) queue.close()
        // Notice the client going away even when nothing is being sent.
        launch {
            try {
                for (ignored in incoming) { /* clients send nothing we care about */ }
            } finally {
                queue.close()
            }
        }
        try {
            for (msg in queue) outgoing.send(Frame.Text(msg))
        } finally {
            unsubscribe()
            queue.close()
        }
    }
}
