package heating.demand

import heating.Change
import heating.ChangeNotifier
import heating.Periodic
import heating.actionLog
import heating.zoneName
import heating.mqtt.MqttBus
import heating.rooms.RoomService
import heating.rooms.Zone
import heating.rooms.ZoneControl
import heating.valves.ValveCommander
import heating.valves.ValveRegistry
import java.time.Duration
import java.time.ZonedDateTime

/**
 * Decides whether the boiler should fire and publishes it retained to `heating/demand`.
 * Evaluated on every tick and whenever a valve or room changes; polls valve running state
 * every five minutes.
 */
class DemandControl(
    private val bus: MqttBus,
    private val rooms: RoomService,
    private val valves: ValveRegistry,
    private val zones: ZoneControl,
    private val commander: ValveCommander,
    private val changes: ChangeNotifier,
    private val now: () -> ZonedDateTime,
    sensorTemperature: (roomId: String) -> Double? = { null },
    minRun: Duration = Duration.ofMinutes(4),
    minRest: Duration = Duration.ofMinutes(5),
    private val activity: heating.activity.ActivityLog? = null,
) {
    private val zoneDemand = ZoneDemand(valves, zones::effectiveTarget, sensorTemperature, commander::settling)
    private val boiler = BoilerDemand(minRun, minRest)
    /** Whether any zone called for heat at the last evaluation. */
    private var wanted = false
    private var lastState: BoilerState? = null
    private var lastPoll: ZonedDateTime? = null

    val demand: Boolean get() = synchronized(this) { boiler.on }

    /** What the boiler is doing now: heating, cycling (min run), resting (min rest) or idle. */
    fun state(): BoilerState = synchronized(this) {
        when {
            boiler.on && wanted -> BoilerState("heating", null)
            boiler.on -> BoilerState("cycling", boiler.holdEnds())
            wanted -> BoilerState("resting", boiler.holdEnds())
            else -> BoilerState("idle", null)
        }
    }

    val tick = Periodic { time ->
        poll(time)
        evaluate()
    }

    fun start() {
        lastPoll = now()
        valves.onStateChanged { evaluate() }
        changes.subscribe { if (it is Change.RoomChanged || it is Change.SensorChanged) evaluate() }
        actionLog.info("demand {} (startup)", if (demand) "on" else "off")
        publish()
    }

    /** Stop the boiler now, without waiting for the minimum run time, and publish it. */
    fun forceOff() {
        val wasOn = synchronized(this) { boiler.on.also { boiler.forceOff(now().toInstant()) } }
        evaluate()
        if (wasOn) {
            holdLogged = false
            publish()
            changes.emit(Change.StatusChanged)
        }
    }

    fun zoneCalling(zone: Zone): Boolean = zoneDemand.isCalling(zone)

    fun zoneTemperature(zone: Zone): Double? = zoneDemand.temperature(zone)

    /** Told which zones started and stopped calling for heat, after each evaluation that changed any. */
    fun onCallingChanged(listener: (started: List<Zone>, stopped: List<Zone>) -> Unit) {
        callingListeners += listener
    }

    private val callingListeners = java.util.concurrent.CopyOnWriteArrayList<(List<Zone>, List<Zone>) -> Unit>()

    /** Zones calling at the last evaluation, by id. */
    private var callingZones: Map<String, Zone> = emptyMap()

    private fun poll(time: ZonedDateTime) {
        val due = synchronized(this) {
            val last = lastPoll
            (last == null || Duration.between(last, time) >= POLL_INTERVAL).also { if (it) lastPoll = time }
        }
        if (due) valves.all().filter { it.online || valves.isSilent(it.name) }.forEach { commander.read(it.name, "running_state") }
    }

    private fun evaluate() {
        val time = now()
        var started = emptyList<Zone>()
        var stopped = emptyList<Zone>()
        var boilerChange: BoilerState? = null
        var callingNow = emptyList<Zone>()
        val flipped = synchronized(this) {
            val now = mutableMapOf<String, Zone>()
            val calling = rooms.list().flatMap { room ->
                room.zones().mapNotNull { zone ->
                    zoneDemand.why(zone)?.let { now[zone.id] = zone; "${room.zoneName(zone.id)} [$it]" }
                }
            }
            val all = rooms.list().flatMap { it.zones() }.associateBy { it.id }
            started = now.keys.filter { it !in callingZones }.map { now.getValue(it) }
            stopped = callingZones.keys.filter { it !in now }.map { all[it] ?: callingZones.getValue(it) }
            callingZones = now
            val wanted = calling.isNotEmpty()
            this.wanted = wanted
            val flipped = boiler.update(wanted, time.toInstant())
            when {
                flipped -> {
                    holdLogged = false
                    if (boiler.on) actionLog.info("demand on (calling: {})", calling.joinToString())
                    else actionLog.info("demand off (no zones calling)")
                }
                wanted != boiler.on -> if (!holdLogged) {
                    holdLogged = true
                    val hold = if (boiler.on) "min-run" else "min-rest"
                    val left = boiler.holdLeft(time.toInstant())?.let { " ${it.toMinutes()}m${it.toSecondsPart()}s left" } ?: ""
                    val why = if (wanted) " (calling: ${calling.joinToString()})" else ""
                    actionLog.info("demand wants {}{}, holding {} for {}{}", if (wanted) "on" else "off", why, if (boiler.on) "on" else "off", hold, left)
                }
                else -> holdLogged = false
            }
            val state = state()
            val stateChanged = state != lastState
            val previous = lastState?.state
            if (state.state != previous && !(previous == null && state.state == "idle")) boilerChange = state
            callingNow = callingZones.values.toList()
            lastState = state
            flipped to stateChanged
        }
        boilerChange?.let { recordBoiler(it, callingNow, stopped) }
        if (flipped.first) publish()
        if (flipped.first || flipped.second) changes.emit(Change.StatusChanged)
        if (started.isNotEmpty() || stopped.isNotEmpty()) callingListeners.forEach { it(started, stopped) }
    }

    /** One feed event for the boiler going into [state], naming the rooms behind it. */
    private fun recordBoiler(state: BoilerState, calling: List<Zone>, stopped: List<Zone>) {
        val reached = stopped.filter { it.settings.mode != heating.rooms.Mode.OFF }
        val (reason, zonesNamed) = when (state.state) {
            "heating" -> "calling" to calling
            "resting" -> "min-rest" to calling
            "cycling" -> if (reached.isNotEmpty()) "reached-temp" to reached else "min-run" to emptyList()
            else -> "no-demand" to emptyList()
        }
        val all = rooms.list()
        val ids = zonesNamed.map { it.roomId }.distinct().filter { id -> all.any { it.id == id } }
        activity?.record(
            heating.activity.BOILER, null, null, kotlinx.serialization.json.JsonPrimitive(state.state), reason,
            rooms = ids.map { id -> all.first { it.id == id }.name }, roomIds = ids,
        )
    }

    /** A hold (min-run or min-rest) was logged; cleared when it ends, so it is logged once. */
    private var holdLogged = false

    private fun publish() = bus.publish(TOPIC, """{"demand":$demand}""", retained = true)

    companion object {
        const val TOPIC = "heating/demand"
        private val POLL_INTERVAL = Duration.ofMinutes(5)
    }
}

/** See openapi BoilerState. [until] is when cycling or resting ends. */
data class BoilerState(val state: String, val until: java.time.Instant?)
