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

    /** Whether an admin has forced the boiler off. Every user may read it; only admins may change it. */
    suspend fun boiler(): BoilerOverrideStatus
    suspend fun boilerOff(): BoilerOverrideStatus
    suspend fun boilerOn(): BoilerOverrideStatus
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
    /** [room]'s and its plugs' power, use and cost over the [window] ("24h", "7d" or "30d") up to [to] (null: now). */
    suspend fun roomUsage(room: String, window: String, to: String? = null): EnergyUsage
    /** What no plug accounts for over the [window] up to [to] (null: now). */
    suspend fun everythingElseUsage(window: String, to: String? = null): EnergyUsage
    /** Leave [device] (a valve, sensor or meter) out of "Waiting to be set up". */
    suspend fun ignoreDevice(device: String)
    suspend fun ignoredDevices(): List<String>
    /** Show an ignored [device] again: it is waiting to be set up. */
    suspend fun showDeviceAgain(device: String)
    /** [period] is today, week or month. */
    suspend fun energyDashboard(period: String): EnergyDashboard

    // Energy areas (admins only): named things with meters and no valves.
    suspend fun energyAreas(): List<EnergyArea>
    suspend fun addEnergyArea(name: String): EnergyArea
    suspend fun renameEnergyArea(id: String, name: String): EnergyArea

    /** Area [id]'s "still using power" notification. */
    suspend fun areaAlert(id: String): AreaAlert
    suspend fun setAreaAlert(id: String, hours: Double, notify: String): AreaAlert
    suspend fun clearAreaAlert(id: String)
    suspend fun deleteEnergyArea(id: String)

    /** Notification deliveries (admins): [status] "failed" lists the failed ones; retry or clear them. */
    suspend fun deliveries(status: String? = null): DeliveriesList
    suspend fun retryDelivery(id: String)
    suspend fun retryAllDeliveries(): Int
    suspend fun clearDelivery(id: String)

    /** The event log (admins): [params] are GET /api/events query parameters, see [eventParams]. */
    suspend fun events(params: List<Pair<String, String>>): EventPage
    suspend fun eventTopics(): List<EventTopic>

    /** Notification rules (admins). [testRule] replays the last events on the topic; [ruleHistory] is newest first. */
    suspend fun rules(): List<Rule>
    suspend fun createRule(request: RuleRequest): Rule
    suspend fun updateRule(id: String, request: RuleRequest): Rule
    suspend fun deleteRule(id: String)
    suspend fun resumeRule(id: String): Rule
    suspend fun ruleHistory(id: String, limit: Int = 50): List<RuleHistoryEntry>
    suspend fun testRule(request: RuleRequest): RuleTestResult

    /** Connection setup (admins): broker and Zigbee2MQTT. */
    suspend fun setupStatus(): SetupStatus
    suspend fun savedBroker(): BrokerView?
    suspend fun testBroker(request: BrokerRequest): BrokerTestResult
    suspend fun saveBroker(request: BrokerRequest): BrokerView
    suspend fun discoverZigbee2mqtt(): List<BridgeFound>
    suspend fun saveBaseTopic(baseTopic: String): String
    suspend fun sendTestNotification(): TestNotificationResult
    suspend fun completeSetup()

    /** Colour bands and the last 30 minutes of house readings for the live power dial. */
    suspend fun stopUsingMeterForHouse(meter: String): EnergySettings
    suspend fun labelMeter(meter: String, label: String): Meter
    /** Gives [meter] a new job in one step; what it recorded stays where it was recorded. */
    suspend fun setMeterJob(meter: String, job: MeterJob): Meter
    suspend fun setDailyTarget(kwh: Double?): EnergySettings
    suspend fun putMeterInArea(area: String, meter: String)
    suspend fun takeMeterOutOfArea(area: String, meter: String)
    suspend fun shareCircuit(area: String, other: String)
    suspend fun stopSharingCircuit(area: String, other: String)
    suspend fun areaUsage(area: String, window: String, to: String? = null): EnergyUsage

    suspend fun powerSummary(): PowerSummary

    /** The house's use for the history chart: [range] is day, week, month or year; [to] (ISO time) ends it, null for now. */
    suspend fun houseHistory(range: String, to: String? = null): HouseHistory

    /** The house's use today, this week, month and year, and by this time yesterday. */
    suspend fun energyTotals(): EnergyTotals

    /** A day of the smart gas meter: [date], or the latest whole day; null with no readings. */
    suspend fun gasDay(date: String? = null): GasDay?

    /** Sets only the boiler's average burn rate the gas estimate uses. */
    suspend fun setBoilerAverageKw(kw: Double): EnergySettings

    /** Live events from /ws until the connection drops. */
    fun liveEvents(): Flow<LiveEvent>

    /** The house's power from /ws/power until the connection drops: one on connect, then one per reading. */
    fun livePower(): Flow<LivePower>
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

    override suspend fun boiler(): BoilerOverrideStatus = client.get("api/boiler").body()

    override suspend fun boilerOff(): BoilerOverrideStatus = client.post("api/boiler/off").body()

    override suspend fun boilerOn(): BoilerOverrideStatus = client.post("api/boiler/on").body()

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
    override suspend fun roomUsage(room: String, window: String, to: String?): EnergyUsage =
        client.get("api/rooms/${room.encodeURLPathPart()}/plugs/usage") { parameter("window", window); to?.let { parameter("to", it) } }.body()
    override suspend fun everythingElseUsage(window: String, to: String?): EnergyUsage =
        client.get("api/energy/else/usage") { parameter("window", window); to?.let { parameter("to", it) } }.body()

    override suspend fun ignoreDevice(device: String) {
        client.put("api/devices/${device.encodeURLPathPart()}/ignored")
    }

    override suspend fun ignoredDevices(): List<String> = client.get("api/devices/ignored").body()

    override suspend fun showDeviceAgain(device: String) {
        client.delete("api/devices/${device.encodeURLPathPart()}/ignored")
    }

    override suspend fun energyDashboard(period: String): EnergyDashboard =
        client.get("api/dashboard") { parameter("period", period) }.body()

    override suspend fun energyAreas(): List<EnergyArea> = client.get("api/energy/areas").body()

    override suspend fun addEnergyArea(name: String): EnergyArea = client.post("api/energy/areas") {
        contentType(ContentType.Application.Json)
        setBody(EnergyAreaName(name))
    }.body()

    override suspend fun renameEnergyArea(id: String, name: String): EnergyArea =
        client.patch("api/energy/areas/${id.encodeURLPathPart()}") {
            contentType(ContentType.Application.Json)
            setBody(EnergyAreaName(name))
        }.body()

    override suspend fun areaAlert(id: String): AreaAlert = client.get("api/energy/areas/${id.encodeURLPathPart()}/alert").body()

    override suspend fun setAreaAlert(id: String, hours: Double, notify: String): AreaAlert =
        client.put("api/energy/areas/${id.encodeURLPathPart()}/alert") {
            contentType(ContentType.Application.Json)
            setBody(AreaAlertRequest(hours, notify))
        }.body()

    override suspend fun clearAreaAlert(id: String) {
        client.delete("api/energy/areas/${id.encodeURLPathPart()}/alert")
    }

    override suspend fun deliveries(status: String?): DeliveriesList =
        client.get("api/deliveries") { status?.let { parameter("status", it) } }.body()

    override suspend fun retryDelivery(id: String) {
        client.post("api/deliveries/${id.encodeURLPathPart()}/retry") { contentType(ContentType.Application.Json); setBody("{}") }
    }

    override suspend fun retryAllDeliveries(): Int =
        client.post("api/deliveries/retry-all") { contentType(ContentType.Application.Json); setBody("{}") }.body<RetriedDeliveries>().retried

    override suspend fun clearDelivery(id: String) {
        client.delete("api/deliveries/${id.encodeURLPathPart()}")
    }

    override suspend fun events(params: List<Pair<String, String>>): EventPage =
        client.get("api/events") { params.forEach { (k, v) -> parameter(k, v) } }.body()

    override suspend fun eventTopics(): List<EventTopic> = client.get("api/events/topics").body()

    override suspend fun rules(): List<Rule> = client.get("api/rules").body()

    override suspend fun createRule(request: RuleRequest): Rule = client.post("api/rules") {
        contentType(ContentType.Application.Json)
        setBody(request)
    }.body()

    override suspend fun updateRule(id: String, request: RuleRequest): Rule = client.put("api/rules/${id.encodeURLPathPart()}") {
        contentType(ContentType.Application.Json)
        setBody(request)
    }.body()

    override suspend fun deleteRule(id: String) {
        client.delete("api/rules/${id.encodeURLPathPart()}")
    }

    override suspend fun resumeRule(id: String): Rule =
        client.post("api/rules/${id.encodeURLPathPart()}/resume") { contentType(ContentType.Application.Json); setBody("{}") }.body()

    override suspend fun ruleHistory(id: String, limit: Int): List<RuleHistoryEntry> =
        client.get("api/rules/${id.encodeURLPathPart()}/history") { parameter("limit", limit) }.body()

    override suspend fun testRule(request: RuleRequest): RuleTestResult = client.post("api/rules/test") {
        contentType(ContentType.Application.Json)
        setBody(request)
    }.body()

    override suspend fun setupStatus(): SetupStatus = client.get("api/setup/status").body()

    override suspend fun savedBroker(): BrokerView? = try {
        client.get("api/setup/broker").body<BrokerView>()
    } catch (e: HeatingApiException) {
        if (e.status == 404) null else throw e
    }

    override suspend fun testBroker(request: BrokerRequest): BrokerTestResult =
        client.post("api/setup/broker/test") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    override suspend fun saveBroker(request: BrokerRequest): BrokerView =
        client.put("api/setup/broker") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    override suspend fun discoverZigbee2mqtt(): List<BridgeFound> = client.get("api/setup/zigbee2mqtt/discover").body()

    override suspend fun saveBaseTopic(baseTopic: String): String =
        client.put("api/setup/zigbee2mqtt") {
            contentType(ContentType.Application.Json)
            setBody(BaseTopicRequest(baseTopic))
        }.body<BaseTopicSaved>().baseTopic

    override suspend fun sendTestNotification(): TestNotificationResult = client.post("api/setup/test-notification").body()

    override suspend fun completeSetup() {
        client.post("api/setup/complete")
    }

    override suspend fun deleteEnergyArea(id: String) {
        client.delete("api/energy/areas/${id.encodeURLPathPart()}")
    }

    override suspend fun stopUsingMeterForHouse(meter: String): EnergySettings =
        client.delete("api/meters/${meter.encodeURLPathPart()}/house").body()

    override suspend fun labelMeter(meter: String, label: String): Meter = client.put("api/meters/${meter.encodeURLPathPart()}/label") {
        contentType(ContentType.Application.Json)
        setBody(MeterLabelRequest(label))
    }.body()

    override suspend fun setMeterJob(meter: String, job: MeterJob): Meter = client.put("api/meters/${meter.encodeURLPathPart()}/job") {
        contentType(ContentType.Application.Json)
        setBody(job)
    }.body()

    override suspend fun setDailyTarget(kwh: Double?): EnergySettings = client.put("api/settings/energy") {
        contentType(ContentType.Application.Json)
        setBody(DailyTargetRequest(kwh))
    }.body()

    override suspend fun putMeterInArea(area: String, meter: String) {
        client.put("api/energy/areas/${area.encodeURLPathPart()}/meters/${meter.encodeURLPathPart()}")
    }

    override suspend fun takeMeterOutOfArea(area: String, meter: String) {
        client.delete("api/energy/areas/${area.encodeURLPathPart()}/meters/${meter.encodeURLPathPart()}")
    }

    override suspend fun shareCircuit(area: String, other: String) {
        client.put("api/energy/areas/${area.encodeURLPathPart()}/overlaps/${other.encodeURLPathPart()}")
    }

    override suspend fun stopSharingCircuit(area: String, other: String) {
        client.delete("api/energy/areas/${area.encodeURLPathPart()}/overlaps/${other.encodeURLPathPart()}")
    }

    override suspend fun areaUsage(area: String, window: String, to: String?): EnergyUsage =
        client.get("api/energy/areas/${area.encodeURLPathPart()}/usage") { parameter("window", window); to?.let { parameter("to", it) } }.body()

    override suspend fun powerSummary(): PowerSummary = client.get("api/power").body()

    override suspend fun energyTotals(): EnergyTotals = client.get("api/energy/totals").body()

    override suspend fun gasDay(date: String?): GasDay? = try {
        val response = client.get("api/energy/gas") { date?.let { parameter("date", it) } }
        if (response.status == io.ktor.http.HttpStatusCode.NotFound) null else response.body()
    } catch (e: io.ktor.client.plugins.ClientRequestException) {
        if (e.response.status == io.ktor.http.HttpStatusCode.NotFound) null else throw e
    }

    override suspend fun setBoilerAverageKw(kw: Double): EnergySettings = client.put("api/settings/energy") {
        contentType(ContentType.Application.Json)
        setBody(BoilerRateRequest(kw))
    }.body()

    override suspend fun houseHistory(range: String, to: String?): HouseHistory =
        client.get("api/energy/history") {
            parameter("range", range)
            to?.let { parameter("to", it) }
        }.body()

    override fun livePower(): Flow<LivePower> = flow {
        val wsUrl = config.baseUrl.trimEnd('/').replaceFirst("http", "ws") + "/ws/power"
        client.webSocket(urlString = wsUrl, request = { bearerAuth(config.token) }) {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    runCatching { apiJson.decodeFromString<LivePower>(frame.readText()) }
                        .onSuccess { emit(it) }
                }
            }
        }
    }

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
