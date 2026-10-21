package heating.holiday

import heating.heatingJson
import heating.store.Store
import kotlinx.serialization.Serializable
import java.time.ZonedDateTime

/** The one household holiday. [from] and [until] are ISO-8601 date-times with offset. */
@Serializable
data class Holiday(val from: String, val until: String, val target: Double = 12.0) {
    /** Active from [from] (inclusive) until [until] (exclusive). */
    fun isActive(now: ZonedDateTime): Boolean {
        val instant = now.toInstant()
        return !instant.isBefore(ZonedDateTime.parse(from).toInstant()) &&
            instant.isBefore(ZonedDateTime.parse(until).toInstant())
    }

    fun isOver(now: ZonedDateTime): Boolean = !now.toInstant().isBefore(ZonedDateTime.parse(until).toInstant())
}

/** Persists the holiday under its own store key and keeps it in memory. */
class HolidayRepository(private val store: Store) {
    @Volatile private var loaded = false
    @Volatile private var holiday: Holiday? = null

    fun current(): Holiday? {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                holiday = store.get(KEY)?.takeIf { it.isNotBlank() }?.let { heatingJson.decodeFromString(Holiday.serializer(), it) }
                loaded = true
            }
        }
        return holiday
    }

    fun save(value: Holiday?) = synchronized(this) {
        store.put(KEY, value?.let { heatingJson.encodeToString(Holiday.serializer(), it) } ?: "")
        holiday = value
        loaded = true
    }

    private companion object {
        const val KEY = "holiday"
    }
}
