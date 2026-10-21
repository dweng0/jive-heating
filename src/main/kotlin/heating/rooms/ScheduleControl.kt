package heating.rooms

import heating.notify.ActionTracker
import heating.notify.Who

import heating.Actor
import heating.ApiException
import heating.actionLog
import heating.logged
import heating.zoneName
import heating.Periodic
import heating.valves.Valve
import heating.valves.ValveCommander
import heating.valves.ValveRegistry
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Week schedule editing, pushing block changes to valves, and valve-knob overrides.
 * Register [blockChanges] on `heating.periodic` and call [start] once.
 */
class ScheduleControl(
    private val rooms: RoomService,
    private val zones: ZoneControl,
    private val valves: ValveRegistry,
    private val commander: ValveCommander,
    private val now: () -> ZonedDateTime,
    private val actions: ActionTracker? = null,
    private val activity: heating.activity.ActivityLog? = null,
) {
    /** Pushes a zone's target when a schedule block (or an override running out) changes it. */
    val blockChanges = Periodic { rooms.list().flatMap { it.zones() }.forEach(zones::applyIfChanged) }

    /**
     * Re-judges valves whose report arrived while they were settling after a command, once the
     * settling time is over: a knob still turning when the service answered ends where it stopped.
     */
    val settledKnobs = Periodic {
        val due = synchronized(unsettled) { unsettled.filterNot(commander::settling).also { unsettled.removeAll(it.toSet()) } }
        due.forEach { name -> valves[name]?.let { judge(it, settled = true) } }
    }

    private val lastSeen = HashMap<String, Reported>()

    /** Valves whose latest report was skipped only because they were settling. */
    private val unsettled = mutableSetOf<String>()

    private data class Reported(val setpoint: Double?, val off: Boolean)

    fun start() {
        valves.onStateChanged(::valveReported)
    }

    fun schedule(zoneId: String): WeekSchedule = zone(zoneId).settings.schedule

    fun saveDay(zoneId: String, day: DayOfWeek, blocks: List<Block>): WeekSchedule {
        val valid = validDay(blocks)
        return change(zoneId, "${day.logged()} ${valid.blocks.joinToString { "${it.start} ${it.target.logged()}" }}") {
            it.copy(schedule = it.schedule.with(day, valid))
        }
    }

    fun copyDay(zoneId: String, from: DayOfWeek, to: List<DayOfWeek>): WeekSchedule {
        val source = zone(zoneId).settings.schedule[from]
        return change(zoneId, "copy ${from.logged()} -> ${to.joinToString { it.logged() }}") { settings -> settings.copy(schedule = to.fold(settings.schedule) { week, day -> week.with(day, source) }) }
    }

    private fun change(zoneId: String, what: String, change: (ZoneSettings) -> ZoneSettings): WeekSchedule {
        zone(zoneId)
        val room = rooms.updateZone(zoneId, change)
        actionLog.info("schedule {} {}{}", room.zoneName(zoneId), what, Actor.by())
        val updated = room.zones().first { it.id == zoneId }
        zones.apply(updated)
        return updated.settings.schedule
    }

    private fun zone(zoneId: String): Zone =
        rooms.list().flatMap { it.zones() }.firstOrNull { it.id == zoneId } ?: throw ApiException.notFound("zone not found")

    private fun validDay(blocks: List<Block>): DaySchedule {
        if (blocks.size > MAX_BLOCKS) throw ApiException.badRequest("a day may have at most $MAX_BLOCKS blocks")
        if (blocks.isEmpty() || blocks.first().start != "00:00") throw ApiException.badRequest("first block must start at 00:00")
        blocks.forEach {
            if (!START.matches(it.start)) throw ApiException.badRequest("block start must be HH:mm")
        }
        if (blocks.zipWithNext().any { (a, b) -> a.start >= b.start }) {
            throw ApiException.badRequest("blocks must be in increasing time order")
        }
        return DaySchedule(blocks.map { it.copy(target = ZoneControl.validTarget(it.target)) })
    }

    /** A valve reported a setpoint (or off) the service did not send: someone turned the knob. */
    private fun valveReported(valve: Valve) {
        val reported = Reported(valve.reportedSetpoint, valve.systemMode == "off")
        if (reported.setpoint == null && !reported.off) return
        val previous = synchronized(lastSeen) { lastSeen.put(valve.name, reported) }
        if (previous == reported) return
        synchronized(unsettled) { unsettled.remove(valve.name) }
        // First report since startup that disagrees with what we just sent is most likely the valve's
        // stale state arriving before it applied our command, not a knob turn: record it only.
        if (previous == null && commander.lastSentSetpoint(valve.name) != null) return
        judge(valve, settled = false)
    }

    /**
     * Whether [valve]'s latest report is a knob turn and, if so, apply it. An off valve counts as the
     * frost setpoint, which is what [ValveCommander.off] remembers sending. Reports while the valve
     * is still settling after a command wait for [settledKnobs] unless [settled].
     */
    private fun judge(valve: Valve, settled: Boolean) {
        val off = valve.systemMode == "off"
        val reported = if (off) ValveCommander.FROST_PROTECTION else valve.reportedSetpoint ?: return
        if (commander.matchesRecent(valve.name, reported)) return
        if (!settled && commander.settling(valve.name)) {
            synchronized(unsettled) { unsettled += valve.name }
            return
        }
        val zone = rooms.roomOf(valve.name)?.zones()?.firstOrNull { valve.name in it.valves } ?: return
        val zoneName = rooms.roomOf(valve.name)?.zoneName(zone.id) ?: zone.id
        if (zone.settings.mode != Mode.OFF) actions?.record(zone.id, Who.Knob)
        val room = rooms.roomOf(valve.name)!!
        fun knob(value: kotlinx.serialization.json.JsonPrimitive) = activity?.record("knob", null, zoneName, value, roomIds = listOf(room.id))
        if (off) {
            if (zone.settings.mode == Mode.OFF) return
            actionLog.info("knob turn {} -> off ({} off)", valve.name, zoneName)
            knob(kotlinx.serialization.json.JsonPrimitive("off"))
            zones.setMode(zone.id, Mode.OFF, null, log = false)
            return
        }
        if (reported < ZoneControl.MIN_TARGET) return
        // The valve's knob goes to 35, past the service's maximum; take that as the maximum.
        val target = reported.coerceAtMost(ZoneControl.MAX_TARGET)
        if (target == zones.effectiveTarget(zone.settings)) return
        when (zone.settings.mode) {
            Mode.MANUAL -> {
                actionLog.info("knob turn {} -> {} ({} manual target)", valve.name, target.logged(), zoneName)
                knob(kotlinx.serialization.json.JsonPrimitive(target))
                zones.setMode(zone.id, Mode.MANUAL, target, log = false)
            }
            Mode.SCHEDULE -> {
                val until = BlockTimes.next(zone.settings.schedule, now()).first
                actionLog.info("knob turn {} -> {} ({} override until {})", valve.name, target.logged(), zoneName, until.format(ISO))
                knob(kotlinx.serialization.json.JsonPrimitive(target))
                val updated = rooms.updateZone(zone.id) { it.copy(override = KnobOverride(target, until.format(ISO))) }
                zones.apply(updated.zones().first { it.id == zone.id })
            }
            // Turned up from off: the valve reports heat at the knob's position. Frost level or below stays off.
            Mode.OFF -> if (target <= ValveCommander.FROST_PROTECTION) {
                actionLog.info("knob turn {} -> {} ignored ({} is off)", valve.name, target.logged(), zoneName)
            } else {
                actionLog.info("knob turn {} -> {} ({} on, manual target)", valve.name, target.logged(), zoneName)
                actions?.record(zone.id, Who.Knob)
                knob(kotlinx.serialization.json.JsonPrimitive(target))
                zones.setMode(zone.id, Mode.MANUAL, target, log = false)
            }
        }
    }

    private fun DayOfWeek.logged() = name.lowercase()

    companion object {
        const val MAX_BLOCKS = 6
        val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        private val START = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
    }
}
