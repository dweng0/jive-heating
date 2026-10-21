package heating.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Devices paired in Zigbee2MQTT that nothing uses yet. */
data class WaitingDevices(
    val valves: List<String> = emptyList(),
    val sensors: List<String> = emptyList(),
    /** Electricity meters that are not the house meter. */
    val meters: List<Meter> = emptyList(),
) {
    val isEmpty: Boolean get() = valves.isEmpty() && sensors.isEmpty() && meters.isEmpty()
}

data class EnergyState(
    val settings: EnergySettings? = null,
    val waiting: WaitingDevices = WaitingDevices(),
    /** Monitored plugs, each with the room it is in (if any). */
    val monitored: List<Meter> = emptyList(),
    /** Devices the admin chose to leave alone; they can be shown again. */
    val ignored: List<String> = emptyList(),
    /** today, week or month. */
    val period: String = "today",
    val dashboard: EnergyDashboard? = null,
    /** One-off message for a snackbar after an action failed. */
    val message: String? = null,
    /** The plug just monitored, while the admin picks its room (or "Not now"). */
    val placing: String? = null,
)

/** Energy prices for the admin. Members never ask: every energy route is admin only. */
class EnergyStore(private val api: HeatingApi, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(EnergyState())
    val state: StateFlow<EnergyState> = _state.asStateFlow()

    fun start(admin: Boolean) {
        if (!admin) return
        scope.launch { loadSettings() }
        scope.launch { loadWaiting() }
    }

    /** New valves, sensors and meters; each list is optional, so an older service without meters still shows the rest. */
    suspend fun loadWaiting() {
        val valves = runCatching { api.valves(unassignedOnly = true).filter { it.room == null }.map { it.name } }.getOrElse { emptyList() }
        val sensors = runCatching { api.sensors(unassignedOnly = true).filter { it.room == null }.map { it.name } }.getOrElse { emptyList() }
        val meters = runCatching { api.meters(unassignedOnly = true) }.getOrElse { emptyList() }
        val ignored = runCatching { api.ignoredDevices() }.getOrNull()
        val monitored = runCatching { api.meters(unassignedOnly = false).filter { it.monitored } }.getOrNull()
        _state.update {
            it.copy(waiting = WaitingDevices(valves, sensors, meters), ignored = ignored ?: it.ignored, monitored = monitored ?: it.monitored)
        }
    }

    /** Make [meter] the house electricity meter. */
    fun useForHouse(meter: String) = scope.launch {
        runCatching { api.useMeterForHouse(meter) }
            .onSuccess { s ->
                _state.update { it.copy(settings = s, waiting = it.waiting.copy(meters = it.waiting.meters.filterNot { m -> m.name == meter })) }
            }
            .onFailure(::showError)
    }

    /** Monitor [meter]: it stops being listed as waiting to be set up, and its room is asked for next. */
    fun monitor(meter: String) = scope.launch {
        runCatching { api.monitorMeter(meter) }
            .onSuccess { m ->
                _state.update {
                    it.copy(
                        waiting = it.waiting.copy(meters = it.waiting.meters.filterNot { w -> w.name == meter }),
                        monitored = (it.monitored.filterNot { d -> d.name == meter } + m).sortedBy { d -> d.name },
                        placing = meter,
                    )
                }
            }
            .onFailure(::showError)
    }

    /** Put the plug just monitored in [room]. If that fails it stays monitored, in no room, and the error is shown. */
    fun placeIn(room: String) {
        val meter = _state.value.placing ?: return
        _state.update { it.copy(placing = null) }
        chooseRoom(meter, room)
    }

    /** Leave the plug just monitored in no room. */
    fun notNow() = _state.update { it.copy(placing = null) }

    /** Put monitored [meter] in [room], or in no room when [room] is null; either way it stays monitored. */
    fun chooseRoom(meter: String, room: String?) = scope.launch {
        val current = _state.value.monitored.firstOrNull { it.name == meter }?.room
        runCatching {
            when {
                room != null -> api.putMeterInRoom(room, meter)
                current != null -> api.takeMeterOutOfRoom(current, meter)
            }
        }
            .onSuccess { _state.update { s -> s.copy(monitored = s.monitored.map { if (it.name == meter) it.copy(room = room) else it }) } }
            .onFailure(::showError)
    }

    /**
     * Stop monitoring [meter] and leave it alone, so it is neither on the Rooms screen nor waiting to be set up; it can
     * be brought back from the ignored devices. Its use recorded so far stays.
     */
    fun stopMonitoring(meter: String) = scope.launch {
        runCatching {
            api.stopMonitoringMeter(meter)
            api.ignoreDevice(meter)
        }
            .onSuccess {
                _state.update { s ->
                    s.copy(
                        monitored = s.monitored.filterNot { it.name == meter },
                        ignored = (s.ignored + meter).sorted(),
                        waiting = s.waiting.copy(meters = s.waiting.meters.filterNot { it.name == meter }),
                    )
                }
            }
            .onFailure {
                showError(it)
                loadWaiting()
            }
    }

    /** Leave [device] alone: it stops being listed as waiting to be set up. */
    fun ignore(device: String) = scope.launch {
        runCatching { api.ignoreDevice(device) }
            .onSuccess {
                _state.update { s ->
                    s.copy(
                        ignored = (s.ignored + device).sorted(),
                        waiting = WaitingDevices(
                            valves = s.waiting.valves - device,
                            sensors = s.waiting.sensors - device,
                            meters = s.waiting.meters.filterNot { it.name == device },
                        ),
                    )
                }
            }
            .onFailure(::showError)
    }

    /** Put an ignored [device] back on the waiting list. */
    fun showAgain(device: String) = scope.launch {
        runCatching { api.showDeviceAgain(device) }
            .onSuccess {
                _state.update { it.copy(ignored = it.ignored - device) }
                loadWaiting()
            }
            .onFailure(::showError)
    }

    /** The energy dashboard for [period] (today, week or month). */
    fun loadDashboard(period: String) = scope.launch {
        _state.update { it.copy(period = period) }
        runCatching { api.energyDashboard(period) }
            .onSuccess { d -> _state.update { if (it.period == d.period) it.copy(dashboard = d) else it } }
            .onFailure(::showError)
    }

    suspend fun loadSettings() {
        runCatching { api.energySettings() }
            .onSuccess { s -> _state.update { it.copy(settings = s) } }
            .onFailure(::showError)
    }

    fun savePrices(prices: EnergyPricesRequest) = scope.launch {
        runCatching { api.saveEnergyPrices(prices) }
            .onSuccess { s -> _state.update { it.copy(settings = s) } }
            .onFailure(::showError)
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun showError(e: Throwable) = _state.update { it.copy(message = friendlyError(e)) }
}
