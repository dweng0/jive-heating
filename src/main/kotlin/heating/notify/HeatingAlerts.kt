package heating.notify

import heating.auth.UserService
import heating.rooms.Zone
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.Executor
import org.slf4j.LoggerFactory

/**
 * Phone (Firebase) and browser (Web Push) notifications: "John turned the heating on" when a person's (or a knob's) change makes zones
 * start calling for heat, and "Heating finished" when every zone started that way has reached its target.
 * Starts are gathered for [GATHER] so one action that starts several rooms sends one notification;
 * register [tick] on `heating.periodic`. Sending runs on [executor] and never affects the heating.
 * Each person hears only about the rooms they can see: a member's notification names (or counts) just
 * those, and they get none when they can see none of them.
 */
class HeatingAlerts(
    private val users: UserService,
    private val settings: NotificationSettings,
    private val sender: PushSender,
    private val actions: ActionTracker,
    private val executor: Executor,
    private val now: () -> Instant,
    /** "Utility Room", or "Room/Valve" for a zone of an independent room. */
    private val zoneName: (Zone) -> String,
    private val target: (Zone) -> Double?,
    private val temperature: (Zone) -> Double?,
    /** Browsers (Web Push) get each person's notifications too, when it is on. */
    private val webPush: WebPush? = null,
) {
    /** A zone in a notification: its room (for who may hear of it), its name, and a target or temperature. */
    private class Mention(val roomId: String, val name: String, val degrees: Double?)

    private class Started(val who: Who, val first: Instant, val zones: MutableList<Mention>)

    /** Starts not yet sent, per person (or knob). */
    private val gathering = mutableMapOf<Who, Started>()

    /** Zones started by a user action that are still heating up. */
    private val heatingUp = mutableSetOf<String>()

    /** Of those, the ones that got there, with their temperature. */
    private val reached = mutableListOf<Mention>()

    val tick = heating.Periodic { flush(force = false) }

    /** [started] zones began calling for heat and [stopped] ones stopped, at one evaluation. */
    @Synchronized
    fun callingChanged(started: List<Zone>, stopped: List<Zone>) {
        started.forEach { zone ->
            val who = actions.take(zone.id) ?: return@forEach
            heatingUp += zone.id
            gathering.getOrPut(who) { Started(who, now(), mutableListOf()) }.zones += Mention(zone.roomId, zoneName(zone), target(zone))
        }
        stopped.forEach { zone ->
            if (!heatingUp.remove(zone.id)) return@forEach
            val t = target(zone)
            val current = temperature(zone)
            if (t != null && current != null && current >= t) reached += Mention(zone.roomId, zoneName(zone), current)
        }
        if (heatingUp.isEmpty() && reached.isNotEmpty()) {
            flush(force = true)
            val all = reached.toList()
            reached.clear()
            send(exclude = null, all, { it.heatingFinished }) { seen ->
                val body = if (seen.size <= 3) seen.joinToString { "${it.name} is at ${degrees(it.degrees!!)}" }
                else "${seen.size} rooms are up to temperature"
                "Heating finished" to body
            }
        }
    }

    /**
     * An admin turned the boiler off ([on] false) or back on. Everyone but [by] who wants it hears, and
     * a person who can see no room hears nothing, like the other notifications.
     */
    fun boilerOverride(on: Boolean, by: String?, rooms: List<Pair<String, String>>) {
        val mentions = rooms.map { (id, name) -> Mention(id, name, null) }
        send(exclude = by, mentions, { it.boilerOverride }) {
            if (on) "Boiler back on" to "Heating is running again"
            else "Boiler turned off by admin" to "Heating is paused until an admin turns it back on"
        }
    }

    @Synchronized
    private fun flush(force: Boolean) {
        val due = gathering.values.filter { force || Duration.between(it.first, now()) >= GATHER }
        due.forEach { started ->
            gathering.remove(started.who)
            val person = (started.who as? Who.Person)?.username
            if (person != null) {
                // Someone in the app: everyone else hears about it.
                val title = "${person.replaceFirstChar { it.titlecase(Locale.UK) }} turned the heating on"
                send(exclude = person, started.zones, { it.heatingOn }) { seen ->
                    val where = seen.singleOrNull()?.name ?: "${seen.size} rooms"
                    val to = targetOf(seen)?.let { ", heating to ${degrees(it)}" } ?: ""
                    title to where + to
                }
            } else {
                // A knob (often the kids): no name, so everyone hears about it.
                send(exclude = null, started.zones, { it.heatingOn }) { seen ->
                    val what = seen.singleOrNull()?.let { "${it.name} thermostat was" } ?: "${seen.size} thermostats were"
                    val to = targetOf(seen)?.let { " to ${degrees(it)}" } ?: ""
                    "Thermostat turned up" to "$what turned up$to"
                }
            }
        }
    }

    /** The one target all of [zones] share, if they do. */
    private fun targetOf(zones: List<Mention>): Double? = zones.map { it.degrees }.distinct().singleOrNull()

    /**
     * Sends to everyone but [exclude] who [wants] it and can see any of [zones]; [message] makes the
     * title and body from the zones that person can see.
     */
    private fun send(
        exclude: String?,
        zones: List<Mention>,
        wants: (NotificationPrefs) -> Boolean,
        message: (List<Mention>) -> Pair<String, String>,
    ) {
        val phones = mutableListOf<Pair<String, WebPushMessage>>()
        val browsers = mutableListOf<Pair<WebPushSubscription, WebPushMessage>>()
        users.list()
            .filter { it.username != exclude && wants(settings.prefs(it.id)) }
            .forEach { user ->
                val seen = zones.filter { user.admin || user.rooms == null || it.roomId in user.rooms }
                if (seen.isEmpty()) return@forEach
                val (title, body) = message(seen)
                // A tap opens the room when the notification is about one room, else the Rooms screen.
                val text = WebPushMessage(title, body, seen.map { it.roomId }.distinct().singleOrNull())
                if (sender !== PushSender.None) settings.devicesOf(user.id).forEach { phones += it to text }
                webPush?.subscriptionsFor(user.id)?.forEach { browsers += it to text }
            }
        if (phones.isEmpty() && browsers.isEmpty()) return
        executor.execute {
            phones.forEach { (token, text) ->
                val result = runCatching { sender.send(token, text.title, text.body) }.getOrDefault(PushResult.FAILED)
                when (result) {
                    PushResult.UNREGISTERED -> settings.forget(token)
                    PushResult.FAILED -> log.warn("notification \"{}\" not sent to a phone", text.title)
                    PushResult.SENT -> Unit
                }
            }
            browsers.forEach { (subscription, text) -> runCatching { webPush?.deliver(subscription, text) } }
        }
    }

    companion object {
        val GATHER: Duration = Duration.ofSeconds(10)
        private val log = LoggerFactory.getLogger(HeatingAlerts::class.java)

        fun degrees(t: Double): String = if (t % 1.0 == 0.0) "${t.toInt()}°" else "${String.format(Locale.ROOT, "%.1f", t)}°"
    }
}
