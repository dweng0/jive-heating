package heating.devices

import heating.heatingJson
import heating.store.Store
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Zigbee2MQTT devices an admin chose to leave alone, such as a plug that needs no setting up. They
 * are kept out of the unassigned valve, sensor and meter lists, and the choice survives a restart.
 */
class IgnoredDevices(private val store: Store) {
    private val names: MutableSet<String> =
        (store.get(KEY)?.let { heatingJson.decodeFromString(ListSerializer(String.serializer()), it) } ?: emptyList()).toMutableSet()

    @Synchronized
    fun all(): List<String> = names.sorted()

    /** Forget ignored devices that [exists] no longer finds, such as ones removed from Zigbee2MQTT. */
    @Synchronized
    fun prune(exists: (String) -> Boolean) {
        if (names.removeAll { !exists(it) }) save()
    }

    @Synchronized
    operator fun contains(name: String): Boolean = name in names

    @Synchronized
    fun ignore(name: String) {
        if (names.add(name)) save()
    }

    /** Returns whether [name] was ignored. */
    @Synchronized
    fun unignore(name: String): Boolean = names.remove(name).also { if (it) save() }

    private fun save() = store.put(KEY, heatingJson.encodeToString(ListSerializer(String.serializer()), names.sorted()))

    companion object {
        private const val KEY = "ignored-devices"
    }
}
