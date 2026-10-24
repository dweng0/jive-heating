package heating

import java.util.concurrent.CopyOnWriteArrayList

/** Something the app should hear about. The live-updates feature turns these into WebSocket events. */
sealed interface Change {
    data class RoomChanged(val roomId: String) : Change
    data class RoomDeleted(val roomId: String) : Change
    data class ValveChanged(val valve: String) : Change
    data class SensorChanged(val sensor: String) : Change
    data object StatusChanged : Change
    data object GroupsChanged : Change

    /** An event was added to the activity feed. */
    data class ActivityAdded(val activity: heating.activity.Activity) : Change

    /** The house meter or a monitored plug sent a reading. Heard only by the live power channel, never by /ws. */
    data object LivePowerChanged : Change

    /** An admin changed which rooms user [userId] can see, or whether they are an admin, or deleted them. */
    data class AccessChanged(val userId: String) : Change
}

class ChangeNotifier {
    private val listeners = CopyOnWriteArrayList<(Change) -> Unit>()

    /** Returns a function that removes the listener again. */
    fun subscribe(listener: (Change) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun emit(change: Change) = listeners.forEach { it(change) }
}
