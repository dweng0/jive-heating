package heating.app.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

/** Where the service lives and how to authenticate. */
data class ServerConfig(val baseUrl: String, val token: String)

class HeatingApiException(val status: Int, override val message: String) : Exception(message)

/** The service API (see openapi.json). Zone ids are a room id or "<roomId>:<valve>". */
interface HeatingApi {
    /** Who is signed in: whether they are an admin, and which rooms they can see. */
    suspend fun me(): AccountUser
    suspend fun status(): Status
    suspend fun rooms(): List<Room>
    suspend fun room(id: String): Room
    suspend fun createRoom(name: String): Room
    suspend fun patchRoom(id: String, patch: PatchRoomRequest): Room
    suspend fun deleteRoom(id: String)
    suspend fun assignValve(roomId: String, valve: String): Room
    suspend fun removeValve(roomId: String, valve: String): Room
    suspend fun setMode(zoneId: String, mode: Mode, target: Double? = null): Room
    suspend fun boost(zoneId: String, minutes: Int, target: Double? = null): Room
    suspend fun cancelBoost(zoneId: String): Room
    suspend fun schedule(zoneId: String): WeekSchedule
    suspend fun saveDay(zoneId: String, day: Day, blocks: List<Block>): WeekSchedule
    suspend fun copyDay(zoneId: String, from: Day, to: List<Day>): WeekSchedule
    suspend fun startHoliday(until: String, from: String? = null, target: Double? = null): Status
    suspend fun endHoliday(): Status
    suspend fun valves(unassignedOnly: Boolean = false): List<Valve>
    suspend fun patchValve(name: String, patch: PatchValveRequest): Valve
    suspend fun sensors(unassignedOnly: Boolean = false): List<Sensor>
    suspend fun assignSensor(roomId: String, sensor: String): Room
    suspend fun removeSensor(roomId: String, sensor: String): Room
    suspend fun groups(): List<Group>
    suspend fun createGroup(name: String, rooms: List<String>): Group
    suspend fun patchGroup(id: String, patch: GroupRequest): Group
    suspend fun deleteGroup(id: String)
    suspend fun controlGroup(id: String, request: GroupControlRequest): Group
    suspend fun cancelGroupBoost(id: String): Group
    /** Every room with valves to one mode, or boost them all (same body as a group). Returns every room. */
    suspend fun controlHouse(request: GroupControlRequest): List<Room>
    /** Cancel every boost. Returns every room. */
    suspend fun cancelHouseBoosts(): List<Room>

    /** What changed in the heating, newest first: up to [limit] events older than event [before]. */
    suspend fun activity(limit: Int = 50, before: Long? = null): List<ActivityEvent>

    /** Room temperatures, the whole-house median and boiler states; [range] is 24h, 7d or 30d. */
    suspend fun history(range: String): History

    /** Where every boost starts; any signed-in user can read it. */
    suspend fun boostSettings(): BoostSettings
    /** Admins only. */
    suspend fun saveBoostSettings(settings: BoostSettings): BoostSettings

    // Energy (admins only): prices, electricity meters and the dashboard.
    suspend fun energySettings(): EnergySettings
    suspend fun saveEnergyPrices(prices: EnergyPricesRequest): EnergySettings
    suspend fun meters(unassignedOnly: Boolean = false): List<Meter>
    /** Make [meter] the house electricity meter. */
    suspend fun useMeterForHouse(meter: String): EnergySettings
    /** Monitor [meter]'s use: it is part of the house meter's, never added to it. */
    suspend fun monitorMeter(meter: String): Meter
    /** Stop monitoring [meter]; its use recorded so far stays. */
    suspend fun stopMonitoringMeter(meter: String)
    /** Put monitored [meter] in [room], taking it out of any other. */
    suspend fun putMeterInRoom(room: String, meter: String): Meter
    /** Take [meter] out of [room]; it stays monitored. */
    suspend fun takeMeterOutOfRoom(room: String, meter: String)
    /** [room]'s monitored plugs with power now and cost today. */
    suspend fun roomPlugs(room: String): RoomPlugs
    /** [room]'s use as bars over [range] ("24h", "7d" or "30d"). */
    suspend fun roomEnergyHistory(room: String, range: String): RoomEnergyHistory
    /** Leave [device] (a valve, sensor or meter) out of "Waiting to be set up". */
    suspend fun ignoreDevice(device: String)
    suspend fun ignoredDevices(): List<String>
    /** Show an ignored [device] again: it is waiting to be set up. */
    suspend fun showDeviceAgain(device: String)
    /** [period] is today, week or month. */
    suspend fun energyDashboard(period: String): EnergyDashboard

    /** Live events from /ws until the connection drops. */
    fun liveEvents(): Flow<LiveEvent>
}

val apiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

class KtorHeatingApi(
    private val config: ServerConfig,
    engine: HttpClientEngine = OkHttp.create(),
    /** Called when the server rejects the session (expired, password changed, user deleted). */
    private val onUnauthorized: () -> Unit = {},
) : HeatingApi {

    private val client = HttpClient(engine) {
        install(ContentNegotiation) { json(apiJson) }
        install(WebSockets)
        expectSuccess = false
        defaultRequest {
            url(config.baseUrl.trimEnd('/') + "/")
            bearerAuth(config.token)
        }
        HttpResponseValidator {
            validateResponse { response ->
                if (response.status.value == 401) onUnauthorized()
                if (response.status.value >= 400) {
                    val text = response.bodyAsText()
                    val message = runCatching { apiJson.decodeFromString<ApiError>(text).error }
                        .getOrElse { text.ifBlank { response.status.description } }
                    throw HeatingApiException(response.status.value, message)
                }
            }
        }
    }

    private fun zone(id: String) = "api/zones/${id.encodeURLPathPart()}"

    override suspend fun me(): AccountUser = client.get("api/auth/me").body()

    override suspend fun status(): Status = client.get("api/status").body()

    override suspend fun rooms(): List<Room> = client.get("api/rooms").body()

    override suspend fun room(id: String): Room = client.get("api/rooms/${id.encodeURLPathPart()}").body()

    override suspend fun createRoom(name: String): Room = client.post("api/rooms") {
        contentType(ContentType.Application.Json)
        setBody(CreateRoomRequest(name))
    }.body()

    override suspend fun patchRoom(id: String, patch: PatchRoomRequest): Room =
        client.patch("api/rooms/${id.encodeURLPathPart()}") {
            contentType(ContentType.Application.Json)
            setBody(patch)
        }.body()

    override suspend fun deleteRoom(id: String) {
        client.delete("api/rooms/${id.encodeURLPathPart()}")
    }

    override suspend fun assignValve(roomId: String, valve: String): Room =
        client.put("api/rooms/${roomId.encodeURLPathPart()}/valves/${valve.encodeURLPathPart()}").body()

    override suspend fun removeValve(roomId: String, valve: String): Room =
        client.delete("api/rooms/${roomId.encodeURLPathPart()}/valves/${valve.encodeURLPathPart()}").body()

    override suspend fun setMode(zoneId: String, mode: Mode, target: Double?): Room =
        client.put("${zone(zoneId)}/mode") {
            contentType(ContentType.Application.Json)
            setBody(SetModeRequest(mode, target))
        }.body()

    override suspend fun boost(zoneId: String, minutes: Int, target: Double?): Room =
        client.post("${zone(zoneId)}/boost") {
            contentType(ContentType.Application.Json)
            setBody(BoostRequest(minutes, target))
        }.body()

    override suspend fun cancelBoost(zoneId: String): Room = client.delete("${zone(zoneId)}/boost").body()

    override suspend fun schedule(zoneId: String): WeekSchedule = client.get("${zone(zoneId)}/schedule").body()

    override suspend fun saveDay(zoneId: String, day: Day, blocks: List<Block>): WeekSchedule =
        client.put("${zone(zoneId)}/schedule/${day.name.lowercase()}") {
            contentType(ContentType.Application.Json)
            setBody(DaySchedule(blocks))
        }.body()

    override suspend fun copyDay(zoneId: String, from: Day, to: List<Day>): WeekSchedule =
        client.post("${zone(zoneId)}/schedule/copy") {
            contentType(ContentType.Application.Json)
            setBody(CopyDayRequest(from, to))
        }.body()

    override suspend fun startHoliday(until: String, from: String?, target: Double?): Status =
        client.put("api/holiday") {
            contentType(ContentType.Application.Json)
            setBody(HolidayRequest(until, from, target))
        }.body()

    override suspend fun endHoliday(): Status = client.delete("api/holiday").body()

    override suspend fun valves(unassignedOnly: Boolean): List<Valve> =
        client.get(if (unassignedOnly) "api/valves?unassigned=true" else "api/valves").body()

    override suspend fun patchValve(name: String, patch: PatchValveRequest): Valve =
        client.patch("api/valves/${name.encodeURLPathPart()}") {
            contentType(ContentType.Application.Json)
            setBody(patch)
        }.body()

    override suspend fun sensors(unassignedOnly: Boolean): List<Sensor> =
        client.get(if (unassignedOnly) "api/sensors?unassigned=true" else "api/sensors").body()

    override suspend fun assignSensor(roomId: String, sensor: String): Room =
        client.put("api/rooms/${roomId.encodeURLPathPart()}/sensor/${sensor.encodeURLPathPart()}").body()

    override suspend fun removeSensor(roomId: String, sensor: String): Room =
        client.delete("api/rooms/${roomId.encodeURLPathPart()}/sensor/${sensor.encodeURLPathPart()}").body()

    private fun group(id: String) = "api/groups/${id.encodeURLPathPart()}"

    override suspend fun groups(): List<Group> = client.get("api/groups").body()

    override suspend fun createGroup(name: String, rooms: List<String>): Group = client.post("api/groups") {
        contentType(ContentType.Application.Json)
        setBody(GroupRequest(name, rooms))
    }.body()

    override suspend fun patchGroup(id: String, patch: GroupRequest): Group = client.patch(group(id)) {
        contentType(ContentType.Application.Json)
        setBody(patch)
    }.body()

    override suspend fun deleteGroup(id: String) {
        client.delete(group(id))
    }

    override suspend fun controlGroup(id: String, request: GroupControlRequest): Group = client.post("${group(id)}/control") {
        contentType(ContentType.Application.Json)
        setBody(request)
    }.body()

    override suspend fun cancelGroupBoost(id: String): Group = client.delete("${group(id)}/boost").body()

    override suspend fun controlHouse(request: GroupControlRequest): List<Room> = client.post("api/house") {
        contentType(ContentType.Application.Json)
        setBody(request)
    }.body()

    override suspend fun cancelHouseBoosts(): List<Room> = client.delete("api/house/boost").body()

    override suspend fun activity(limit: Int, before: Long?): List<ActivityEvent> =
        client.get("api/activity") {
            parameter("limit", limit)
            before?.let { parameter("before", it) }
        }.body<ActivityList>().events

    override suspend fun history(range: String): History = client.get("api/history") { parameter("range", range) }.body()

    override suspend fun boostSettings(): BoostSettings = client.get("api/settings/boost").body()

    override suspend fun saveBoostSettings(settings: BoostSettings): BoostSettings = client.put("api/settings/boost") {
        contentType(ContentType.Application.Json)
        setBody(settings)
    }.body()

    override suspend fun energySettings(): EnergySettings = client.get("api/settings/energy").body()

    override suspend fun saveEnergyPrices(prices: EnergyPricesRequest): EnergySettings = client.put("api/settings/energy") {
        contentType(ContentType.Application.Json)
        setBody(prices)
    }.body()

    override suspend fun meters(unassignedOnly: Boolean): List<Meter> =
        client.get(if (unassignedOnly) "api/meters?unassigned=true" else "api/meters").body()

    override suspend fun useMeterForHouse(meter: String): EnergySettings =
        client.put("api/meters/${meter.encodeURLPathPart()}/house").body()

    override suspend fun monitorMeter(meter: String): Meter =
        client.put("api/meters/${meter.encodeURLPathPart()}/monitored").body()

    override suspend fun stopMonitoringMeter(meter: String) {
        client.delete("api/meters/${meter.encodeURLPathPart()}/monitored")
    }

    override suspend fun putMeterInRoom(room: String, meter: String): Meter =
        client.put("api/rooms/${room.encodeURLPathPart()}/meters/${meter.encodeURLPathPart()}").body()

    override suspend fun takeMeterOutOfRoom(room: String, meter: String) {
        client.delete("api/rooms/${room.encodeURLPathPart()}/meters/${meter.encodeURLPathPart()}")
    }

    override suspend fun roomPlugs(room: String): RoomPlugs = client.get("api/rooms/${room.encodeURLPathPart()}/plugs").body()
    override suspend fun roomEnergyHistory(room: String, range: String): RoomEnergyHistory =
        client.get("api/rooms/${room.encodeURLPathPart()}/plugs/history") { parameter("range", range) }.body()

    override suspend fun ignoreDevice(device: String) {
        client.put("api/devices/${device.encodeURLPathPart()}/ignored")
    }

    override suspend fun ignoredDevices(): List<String> = client.get("api/devices/ignored").body()

    override suspend fun showDeviceAgain(device: String) {
        client.delete("api/devices/${device.encodeURLPathPart()}/ignored")
    }

    override suspend fun energyDashboard(period: String): EnergyDashboard =
        client.get("api/dashboard") { parameter("period", period) }.body()

    override fun liveEvents(): Flow<LiveEvent> = flow {
        val wsUrl = config.baseUrl.trimEnd('/').replaceFirst("http", "ws") + "/ws"
        client.webSocket(urlString = wsUrl, request = { bearerAuth(config.token) }) {
            // The service only speaks when something changes; say the connection is up straight away.
            emit(LiveEvent(LiveEvent.CONNECTED))
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    runCatching { apiJson.decodeFromString<LiveEvent>(frame.readText()) }
                        .onSuccess { emit(it) }
                }
            }
        }
    }
}
