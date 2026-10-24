package heating

import heating.notify.ActionTracker
import heating.notify.HeatingAlerts
import heating.notify.NotificationSettings
import heating.notify.PushSender
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import heating.api.roomView
import heating.auth.Passwords
import heating.auth.UserRepository
import heating.auth.UserService
import heating.boost.BoostControl
import heating.demand.DemandControl
import heating.history.HistoryRecorder
import heating.history.HistoryRepository
import heating.holiday.HolidayControl
import heating.groups.GroupService
import heating.house.HouseControl
import heating.holiday.HolidayRepository
import heating.monitoring.KumaReporter
import heating.mqtt.MqttBus
import heating.mqtt.Z2mTopics
import heating.rooms.RoomRepository
import heating.rooms.RoomService
import heating.rooms.ScheduleControl
import heating.rooms.ZoneControl
import heating.sensors.SensorControl
import heating.sensors.SensorRegistry
import heating.store.Store
import heating.valves.ValveCommander
import heating.valves.ValveRegistry
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.time.Clock
import java.time.ZonedDateTime

/** Shared JSON settings for the API and the store. */
val heatingJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** An error that maps straight to an HTTP status and {"error": message}. */
class ApiException(val status: HttpStatusCode, override val message: String) : RuntimeException(message) {
    companion object {
        fun badRequest(message: String) = ApiException(HttpStatusCode.BadRequest, message)
        fun notFound(message: String) = ApiException(HttpStatusCode.NotFound, message)
        fun conflict(message: String) = ApiException(HttpStatusCode.Conflict, message)
        fun unprocessable(message: String) = ApiException(HttpStatusCode.UnprocessableEntity, message)
    }
}

/**
 * Wires the service together. Tests build one with an in-memory bus, a temp store
 * and a controllable clock; production builds one from [Config].
 */
class HeatingApp(
    val config: Config,
    val bus: MqttBus,
    val store: Store,
    val clock: Clock,
    /** Sends a GET to the given URL; wired to a real HTTP client in production. */
    private val httpGet: (String) -> Unit = {},
    /** Phone notifications (Firebase in production); none by default. */
    push: PushSender = PushSender.None,
    /** Where notifications are sent from, off the heating's own threads. */
    pushExecutor: Executor = Executors.newSingleThreadExecutor { Thread(it, "push").apply { isDaemon = true } },
    /** Browsers' push services (the JDK's HTTP client in production). */
    webPushHttp: heating.notify.WebPushHttp = heating.notify.WebPushHttp.java(),
) {
    val topics = Z2mTopics(config.z2mBaseTopic)
    val changes = ChangeNotifier()
    val valves = ValveRegistry(bus, topics, changes, clock)
    val commander = ValveCommander(bus, topics, clock::instant)
    val rooms = RoomService(RoomRepository(store), valves, commander, changes)
    val holidays = HolidayRepository(store)
    val actions = ActionTracker(clock::instant)
    val activity = heating.activity.ActivityLog(config.dbPath, clock::instant)
    val zones = ZoneControl(rooms, commander, ::now, holidays::current, actions, activity)
    val boostSettings = heating.boost.BoostSettingsRepository(store)
    val boosts = BoostControl(rooms, zones, commander, holidays, ::now, actions, activity) { boostSettings.get().defaultTarget }
    val holiday = HolidayControl(holidays, boosts, zones, changes, config.timezone, ::now, activity)
    val schedules = ScheduleControl(rooms, zones, valves, commander, ::now, actions, activity)
    val house = HouseControl(rooms, zones, boosts, activity)
    val groups = GroupService(store, rooms, changes)
    val sensors = SensorRegistry(bus, topics, changes, clock)
    val roomSensors = SensorControl(rooms, sensors, commander, clock::instant)
    val demand = DemandControl(bus, rooms, valves, zones, commander, changes, ::now, roomSensors::temperatureOf, config.boilerMinRun, config.boilerMinRest, activity)
    val history = HistoryRepository(config.dbPath)
    val historyRecorder = HistoryRecorder(history, changes, clock::instant, ::roomSamples, { demand.state().state })
    val users = UserService(UserRepository(store), Passwords(config.passwordIterations), config.apiToken, clock::instant)
    val notificationSettings = NotificationSettings(store)
    val webPush = heating.notify.WebPush(store, config.webPushContact, webPushHttp, clock::instant)
    val ignoredDevices = heating.devices.IgnoredDevices(store)
    val energySettings = heating.energy.EnergySettingsRepository(store)
    val monitoredMeters = heating.energy.MonitoredMeters(store)
    val meters = heating.energy.MeterRegistry(bus, topics, clock)
    val energyHistory = heating.energy.EnergyHistory(config.dbPath)
    val livePower = heating.energy.LivePower(
        meters, energySettings, monitoredMeters, { id -> runCatching { rooms.get(id).name }.getOrNull() },
        energyHistory, config.timezone, changes, clock,
    )
    val meterRecorder = heating.energy.MeterRecorder(energyHistory, meters, energySettings, monitoredMeters, clock::instant)
    val energyDashboards = heating.energy.EnergyDashboards(history, energyHistory, energySettings, monitoredMeters, meters, rooms::list, ::now)
    val roomPlugs = heating.energy.RoomPlugsReport(energyHistory, energySettings, monitoredMeters, meters, ::now)
    val roomEnergyHistory = heating.energy.RoomEnergyHistoryReport(energyHistory, config.timezone, clock)
    val alerts = HeatingAlerts(
        users, notificationSettings, push, actions, pushExecutor, clock::instant,
        zoneName = { zone -> rooms.list().firstOrNull { it.id == zone.roomId }?.zoneName(zone.id) ?: zone.id },
        target = { zone -> zones.effectiveTarget(zone.settings) },
        temperature = demand::zoneTemperature,
        webPush = webPush,
    )

    private val offlineActivity = heating.activity.OfflineActivity(activity, rooms, valves, sensors, meters, monitoredMeters)

    init {
        activity.onAdded { changes.emit(Change.ActivityAdded(it)) }
        changes.subscribe { change ->
            if (change is Change.RoomDeleted) {
                users.forgetRoom(change.roomId)
                monitoredMeters.roomDeleted(change.roomId)
            }
        }
    }

    /** Local time in the configured time zone. */
    fun now(): ZonedDateTime = ZonedDateTime.now(clock).withZoneSameInstant(config.timezone)

    fun start() {
        rooms.start()
        users.grantLegacyMembers(rooms.list().map { it.id })
        groups.start()
        valves.start()
        schedules.start()
        periodic += schedules.blockChanges
        periodic += schedules.settledKnobs
        periodic += holiday.transitions
        periodic += boosts.expiry
        periodic += Periodic { valves.markSilent(VALVE_SILENCE_LIMIT) }
        roomSensors.start()
        sensors.start()
        offlineActivity.start()
        meters.onRemoved { meter ->
            if (energySettings.get().electricityMeter == meter) actionLog.info("house meter {} removed from Zigbee2MQTT", meter)
            energySettings.meterRemoved(meter)
            monitoredMeters.meterRemoved(meter)
        }
        livePower.start()
        meters.start()
        meterRecorder.start()
        // After the registries, which hear the same message first: a device that left Zigbee2MQTT is no longer ignored,
        // nor monitored (one that left while the service was down is not in the registry to be removed).
        bus.subscribe(topics.bridgeDevices) { _, payload ->
            if (runCatching { Json.parseToJsonElement(payload) }.getOrNull() is JsonArray) {
                ignoredDevices.prune { valves[it] != null || sensors[it] != null || meters[it] != null }
                monitoredMeters.prune { meters[it] != null }
            }
        }
        periodic += meterRecorder.tick
        periodic += livePower.tick
        periodic += Periodic { sensors.markSilent(SENSOR_SILENCE_LIMIT) }
        periodic += roomSensors.resend
        config.kumaPushUrl?.let { periodic += KumaReporter(it, valves, httpGet, ::offlineRoomSensors).heartbeat }
        demand.onCallingChanged(alerts::callingChanged)
        demand.start()
        periodic += demand.tick
        periodic += alerts.tick
        historyRecorder.start()
        periodic += historyRecorder.tick
        recover()
    }

    /** Each room's id, temperature and target as the app shows them; independent rooms average their zones' targets. */
    private fun roomSamples() = rooms.list().map { room ->
        val view = roomView(room)
        val target = view.target ?: view.zones.mapNotNull { it.target }.takeIf { it.isNotEmpty() }?.average()
        Triple(room.id, view.current, target)
    }

    private fun offlineRoomSensors(): List<String> =
        rooms.list().mapNotNull { it.sensor }.filter { sensors[it]?.online == false }

    /** After a restart: drop boosts that ran out while down, then push every zone's target to its valves. */
    private fun recover() {
        boosts.expiry.tick(now())
        zones.applyAll()
    }

    /**
     * Periodic jobs (schedule block changes, boost expiry, holiday, demand, polling). Each feature
     * registers its own in [start] or its constructor rather than editing [tick].
     */
    val periodic = mutableListOf<Periodic>()

    /** Called every 5 s in production; tests call it after moving the clock. */
    fun tick() {
        val now = now()
        periodic.forEach { it.tick(now) }
    }
}

/** A valve that has sent nothing for this long is treated as offline. */
private val VALVE_SILENCE_LIMIT = java.time.Duration.ofMinutes(30)

/** A sensor that has sent nothing for this long is treated as offline (they report about every 3 min). */
private val SENSOR_SILENCE_LIMIT = java.time.Duration.ofMinutes(60)

/** Work that runs on every [HeatingApp.tick]. */
fun interface Periodic {
    fun tick(now: ZonedDateTime)
}
