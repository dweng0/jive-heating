package heating.monitoring

import heating.Periodic
import heating.valves.ValveRegistry
import java.net.URLEncoder
import java.time.Duration
import java.time.ZonedDateTime

/**
 * Reports valve health to an Uptime Kuma push monitor: `status=down` naming the offline valves
 * and offline room sensors,
 * `status=up` otherwise. Silence from this service also trips the monitor, which covers the
 * service itself dying.
 */
class KumaReporter(
    private val pushUrl: String,
    private val valves: ValveRegistry,
    private val send: (String) -> Unit,
    /** Sensors that measure a room and are offline; sensors in no room do not matter to heating. */
    private val offlineRoomSensors: () -> List<String> = { emptyList() },
) {
    private var lastPush: ZonedDateTime? = null

    val heartbeat = Periodic { time ->
        val last = lastPush
        if (last == null || Duration.between(last, time) >= INTERVAL) {
            lastPush = time
            runCatching { send(url()) }
        }
    }

    private fun url(): String {
        val all = valves.all()
        val offline = all.filter { !it.online }.map { it.name } + offlineRoomSensors()
        val status = if (offline.isEmpty()) "up" else "down"
        val message = if (offline.isEmpty()) "${all.size} valves online" else "offline: ${offline.joinToString(", ")}"
        val separator = if ('?' in pushUrl) "&" else "?"
        return "$pushUrl${separator}status=$status&msg=${URLEncoder.encode(message, Charsets.UTF_8)}"
    }

    companion object {
        private val INTERVAL = Duration.ofMinutes(1)
    }
}
