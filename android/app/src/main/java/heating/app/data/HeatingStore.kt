package heating.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeState(
    /** Admins see the whole house; a member sees only the rooms an admin gave them. */
    val admin: Boolean = false,
    val rooms: List<Room> = emptyList(),
    val groups: List<Group> = emptyList(),
    val status: Status? = null,
    /** Set while an admin has forced the boiler off. Every user is told. */
    val boilerOverride: BoilerOverride? = null,
    val loading: Boolean = true,
    val connection: ConnectionState = ConnectionState.CONNECTING,
    /** Why the service can't be reached, in plain words (never raw exception text). */
    val connectionProblem: String? = null,
    /** One-off message for a snackbar after an action the user took failed. */
    val message: String? = null,
    /** Dial targets the user is still adjusting, by zone id; sent after a pause. */
    val pendingTargets: Map<String, Double> = emptyMap(),
    /** The newest activity event pushed on /ws, for an open activity tray to add to its list. */
    val latestActivity: ActivityEvent? = null,
    /** The household's boost temperature: where every boost starts. */
    val boostTarget: Double = BoostSettings().defaultTarget,
) {
    val connected: Boolean get() = connection == ConnectionState.LIVE
    fun room(id: String): Room? = rooms.firstOrNull { it.id == id }
    fun group(id: String): Group? = groups.firstOrNull { it.id == id }
    fun zone(id: String): Zone? = rooms.flatMap { it.zones }.firstOrNull { it.id == id }
}

/**
 * Single source of truth for the app: loads rooms and status, keeps them fresh from
 * the /ws live feed, and runs user actions against the service.
 */
class HeatingStore(private val api: HeatingApi, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private val targetJobs = mutableMapOf<String, Job>()
    private var liveJob: Job? = null

    fun start() {
        scope.launch { refresh() }
        if (liveJob == null) liveJob = scope.launch { followLiveEvents() }
    }

    suspend fun refresh() {
        runCatching {
            val admin = api.me().admin
            val rooms = api.rooms()
            // An older service has no boost settings; boosts then start at the old default.
            runCatching { api.boostSettings() }.onSuccess { boost -> _state.update { it.copy(boostTarget = boost.defaultTarget) } }
            // An older service has no boiler override; treat that as not set.
            runCatching { api.boiler() }.onSuccess { b -> _state.update { it.copy(boilerOverride = b.override) } }
            if (!admin) {
                // The house status and groups are admin only: a member never asks for them.
                _state.update { it.copy(admin = false, rooms = rooms, groups = emptyList(), status = null, loading = false) }
                return@runCatching
            }
            val status = api.status()
            // An older service has no groups; treat that as none.
            val groups = runCatching { api.groups() }.getOrElse { emptyList() }
            _state.update { it.copy(admin = true, rooms = rooms, groups = groups, status = status, loading = false) }
        }.onFailure { e ->
            // Not reaching the service is shown in the status chip, not as a pop-up.
            if (e is HeatingApiException) _state.update { it.copy(loading = false, message = friendlyError(e)) }
            else _state.update { it.copy(loading = false, connection = ConnectionState.OFFLINE, connectionProblem = friendlyError(e)) }
        }
    }

    private suspend fun followLiveEvents() {
        var backoff = 1_000L
        var failures = 0
        while (true) {
            val ended = runCatching {
                api.liveEvents().collect { event ->
                    if (event.type == LiveEvent.CONNECTED) {
                        _state.update { it.copy(connection = ConnectionState.LIVE, connectionProblem = null) }
                        backoff = 1_000L
                        failures = 0
                    } else if (event.type == LiveEvent.ACCESS_UPDATED) {
                        // An admin changed this user's rooms or admin rights: rooms given show up and
                        // rooms taken away go, straight away.
                        refresh()
                    } else {
                        apply(event)
                    }
                }
            }
            failures++
            // A single drop is usually the phone waking or switching network: say we're checking.
            // Only call it offline once a reconnect has failed too.
            val problem = ended.exceptionOrNull()?.let(::friendlyError) ?: "The connection to the heating service closed."
            _state.update {
                if (failures >= 2) it.copy(connection = ConnectionState.OFFLINE, connectionProblem = problem)
                else it.copy(connection = ConnectionState.CONNECTING)
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
            refresh()
        }
    }

    private fun apply(event: LiveEvent) = _state.update { s ->
        when (event.type) {
            // A member only follows the rooms they were given; the service decides which those are.
            "room.updated" -> event.room?.takeIf { s.admin || s.room(it.id) != null }?.let { s.copy(rooms = s.rooms.upsert(it)) } ?: s
            "room.deleted" -> s.copy(rooms = s.rooms.filterNot { it.id == event.roomId })
            "status.updated" -> event.status?.takeIf { s.admin }?.let { s.copy(status = it) } ?: s
            LiveEvent.BOILER_OVERRIDE -> event.boilerOverride?.let { s.copy(boilerOverride = it.override) } ?: s
            "groups.updated" -> event.groups?.takeIf { s.admin }?.let { s.copy(groups = it) } ?: s
            "activity.added" -> event.activity?.let { s.copy(latestActivity = it) } ?: s
            else -> s
        }
    }

    fun setMode(zoneId: String, mode: Mode, target: Double? = null) = act { api.setMode(zoneId, mode, target) }

    fun boost(zoneId: String, minutes: Int, target: Double) = act { api.boost(zoneId, minutes, target) }

    fun cancelBoost(zoneId: String) = act { api.cancelBoost(zoneId) }

    fun patchRoom(roomId: String, patch: PatchRoomRequest) = act { api.patchRoom(roomId, patch) }

    fun startHoliday(until: String, target: Double?) = scope.launch {
        runCatching { api.startHoliday(until, target = target) }
            .onSuccess { status -> _state.update { it.copy(status = status) } }
            .onFailure(::showError)
    }

    /** Admins: force the boiler off, or let it heat again. */
    fun boilerOff() = boilerAct { api.boilerOff() }

    fun boilerOn() = boilerAct { api.boilerOn() }

    private fun boilerAct(call: suspend () -> BoilerOverrideStatus) = scope.launch {
        runCatching { call() }
            .onSuccess { result -> _state.update { it.copy(boilerOverride = result.override) } }
            .onFailure(::showError)
    }

    fun endHoliday() = scope.launch {
        runCatching { api.endHoliday() }
            .onSuccess { status -> _state.update { it.copy(status = status) } }
            .onFailure(::showError)
    }

    /** Apply [request] to every room in group [id], then reload rooms so their tiles catch up. */
    fun controlGroup(id: String, request: GroupControlRequest) = groupAct { api.controlGroup(id, request) }

    fun cancelGroupBoost(id: String) = groupAct { api.cancelGroupBoost(id) }

    fun createGroup(name: String, rooms: List<String>) = groupAct { api.createGroup(name, rooms) }

    fun updateGroup(id: String, name: String, rooms: List<String>) = groupAct { api.patchGroup(id, GroupRequest(name, rooms)) }

    fun deleteGroup(id: String) = scope.launch {
        runCatching { api.deleteGroup(id) }
            .onSuccess { _state.update { s -> s.copy(groups = s.groups.filterNot { it.id == id }) } }
            .onFailure(::showError)
    }

    private fun groupAct(call: suspend () -> Group) = scope.launch {
        runCatching { call() }
            .onSuccess { group ->
                _state.update { s ->
                    val groups = if (s.groups.any { it.id == group.id }) s.groups.map { if (it.id == group.id) group else it } else s.groups + group
                    s.copy(groups = groups)
                }
                runCatching { api.rooms() }.onSuccess { rooms -> _state.update { it.copy(rooms = rooms) } }
            }
            .onFailure(::showError)
    }

    /** Admins: make [target] where every boost starts. */
    fun saveBoostTarget(target: Double) = scope.launch {
        runCatching { api.saveBoostSettings(BoostSettings(defaultTarget = target)) }
            .onSuccess { saved -> _state.update { it.copy(boostTarget = saved.defaultTarget) } }
            .onFailure(::showError)
    }

    /** Whole house: apply [request] to every room, take the rooms the service returns, then refresh groups. */
    fun controlHouse(request: GroupControlRequest) = houseAct { api.controlHouse(request) }

    fun cancelHouseBoosts() = houseAct { api.cancelHouseBoosts() }

    private fun houseAct(call: suspend () -> List<Room>) = scope.launch {
        runCatching { call() }
            .onSuccess { rooms ->
                _state.update { it.copy(rooms = rooms) }
                runCatching { api.groups() }.onSuccess { groups -> _state.update { it.copy(groups = groups) } }
            }
            .onFailure(::showError)
    }

    /** Dial moved: show [target] now, send it as a manual target once the user pauses. */
    fun nudgeTarget(zoneId: String, target: Double) {
        _state.update { it.copy(pendingTargets = it.pendingTargets + (zoneId to target)) }
        targetJobs[zoneId]?.cancel()
        targetJobs[zoneId] = scope.launch {
            delay(TARGET_DEBOUNCE_MS)
            runCatching { api.setMode(zoneId, Mode.MANUAL, target) }
                .onSuccess { room -> _state.update { it.copy(rooms = it.rooms.upsert(room)) } }
                .onFailure(::showError)
            _state.update { it.copy(pendingTargets = it.pendingTargets - zoneId) }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun act(call: suspend () -> Room) = scope.launch {
        runCatching { call() }
            .onSuccess { room -> _state.update { it.copy(rooms = it.rooms.upsert(room)) } }
            .onFailure(::showError)
    }

    private fun showError(e: Throwable) =
        _state.update { it.copy(message = friendlyError(e)) }

    private fun List<Room>.upsert(room: Room) =
        if (any { it.id == room.id }) map { if (it.id == room.id) room else it } else this + room

    companion object {
        const val TARGET_DEBOUNCE_MS = 800L
    }
}

enum class ConnectionState { CONNECTING, LIVE, OFFLINE }

/** A short, plain message for [e]: the service's own words for a refused request, never a stack trace. */
fun friendlyError(e: Throwable): String = when (e) {
    is HeatingApiException ->
        if (e.message.isBlank()) {
            if (e.status == 404) "The heating service doesn't know about that yet. It may need updating." else "The heating service refused that (${e.status})."
        } else e.message.replaceFirstChar { it.uppercase() }.trimEnd('.') + "."
    is java.net.UnknownHostException, is java.net.ConnectException, is java.nio.channels.UnresolvedAddressException ->
        "Can't reach the heating service. Check this phone's internet connection."
    is java.net.SocketTimeoutException -> "The heating service took too long to answer."
    is java.io.IOException -> "Lost the connection to the heating service."
    else -> "Something went wrong. Please try again."
}
