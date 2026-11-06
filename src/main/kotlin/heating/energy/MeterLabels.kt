package heating.energy

import heating.ApiException
import heating.heatingJson
import heating.store.Store
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Names the admin gives meters, such as "Mains" for "Mains and lights A", shown wherever the meter is. A meter with no
 * label is shown by its Zigbee2MQTT name. A meter that leaves Zigbee2MQTT loses its label.
 */
class MeterLabels(private val store: Store) {
    private val labels: MutableMap<String, String> =
        (store.get(KEY)?.let { heatingJson.decodeFromString(SERIALIZER, it) } ?: emptyMap()).toMutableMap()

    /** [meter]'s label, or its name when it has none. */
    @Synchronized
    fun of(meter: String): String = labels[meter] ?: meter

    /** Labels [meter] [label]; blank clears it. 400 for a label over [MAX] characters. */
    @Synchronized
    fun set(meter: String, label: String) {
        val trimmed = label.trim()
        if (trimmed.length > MAX) throw ApiException.badRequest("a label is at most $MAX characters")
        val changed = if (trimmed.isEmpty() || trimmed == meter) labels.remove(meter) != null else labels.put(meter, trimmed) != trimmed
        if (changed) save()
    }

    @Synchronized
    fun meterRemoved(meter: String) {
        if (labels.remove(meter) != null) save()
    }

    private fun save() = store.put(KEY, heatingJson.encodeToString(SERIALIZER, labels))

    companion object {
        const val MAX = 40
        private const val KEY = "meter-labels"
        private val SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}
