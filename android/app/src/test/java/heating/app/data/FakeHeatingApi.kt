package heating.app.data

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** In-memory service for tests. Records calls; [fail] makes the next call throw. */
class FakeHeatingApi : HeatingApi {
    val rooms = linkedMapOf<String, Room>()
    val groups = linkedMapOf<String, Group>()
    var status = Status(heatDemand = false, holiday = null, serverTime = "2026-10-13T18:04:00+01:00", timezone = "Europe/London")
    val schedules = mutableMapOf<String, WeekSchedule>()
    val calls = mutableListOf<String>()
    val live = Channel<LiveEvent>(Channel.UNLIMITED)
    var fail: Exception? = null
    var me = AccountUser("u-jane", "jane", admin = true)

    private fun call(name: String) {
        calls += name
        fail?.let { fail = null; throw it }
    }

    private fun zoneRoom(zoneId: String) = rooms.getValue(zoneId.substringBefore(':'))

    private fun updateZone(zoneId: String, change: (Zone) -> Zone): Room {
        val room = zoneRoom(zoneId)
        val updated = room.copy(zones = room.zones.map { if (it.id == zoneId) change(it) else it })
            .let { r -> if (r.control == Control.GROUPED) r.copy(mode = r.zones[0].mode, target = r.zones[0].target) else r }
        rooms[room.id] = updated
        return updated
    }

    var activity = emptyList<ActivityEvent>()
    override suspend fun activity(limit: Int, before: Long?): List<ActivityEvent> {
        call("activity $limit $before")
        return activity.filter { before == null || it.id < before }.take(limit)
    }

    var history = History(from = "", to = "", rooms = emptyList(), house = emptyList(), boiler = emptyList())
    override suspend fun history(range: String): History { call("history $range"); return history }
    override suspend fun me(): AccountUser { call("me"); return me }
    override suspend fun status(): Status { call("status"); return status }
    override suspend fun rooms(): List<Room> { call("rooms"); return rooms.values.toList() }
    override suspend fun room(id: String): Room { call("room $id"); return rooms.getValue(id) }
    override suspend fun createRoom(name: String): Room {
        call("createRoom $name")
        return room(id = name.lowercase(), name = name).also { rooms[it.id] = it }
    }
    override suspend fun patchRoom(id: String, patch: PatchRoomRequest): Room {
        call("patchRoom $id $patch")
        val r = rooms.getValue(id)
        return r.copy(
            name = patch.name ?: r.name,
            control = patch.control ?: r.control,
            smartTemperatureControl = patch.smartTemperatureControl ?: r.smartTemperatureControl,
        ).also { rooms[id] = it }
    }
    override suspend fun deleteRoom(id: String) { call("deleteRoom $id"); rooms.remove(id) }
    override suspend fun assignValve(roomId: String, valve: String): Room { call("assignValve $roomId $valve"); return rooms.getValue(roomId) }
    override suspend fun removeValve(roomId: String, valve: String): Room { call("removeValve $roomId $valve"); return rooms.getValue(roomId) }

    override suspend fun setMode(zoneId: String, mode: Mode, target: Double?): Room {
        call("setMode $zoneId $mode $target")
        return updateZone(zoneId) {
            val manual = target ?: it.manualTarget
            it.copy(mode = mode, manualTarget = manual, target = if (mode == Mode.OFF) null else manual)
        }
    }

    override suspend fun boost(zoneId: String, minutes: Int, target: Double?): Room {
        call("boost $zoneId $minutes $target")
        return updateZone(zoneId) { it.copy(boost = Boost(target ?: 22.0, "2026-10-13T19:04:00+01:00"), target = target ?: 22.0) }
    }

    override suspend fun cancelBoost(zoneId: String): Room {
        call("cancelBoost $zoneId")
        return updateZone(zoneId) { it.copy(boost = null) }
    }

    val sensors = linkedMapOf<String, Sensor>()
    override suspend fun sensors(unassignedOnly: Boolean): List<Sensor> {
        call("sensors $unassignedOnly")
        return sensors.values.filter { !unassignedOnly || it.room == null }
    }
    override suspend fun assignSensor(roomId: String, sensor: String): Room {
        call("assignSensor $roomId $sensor")
        return rooms.getValue(roomId).copy(sensor = sensor, temperatureSource = "sensor").also { rooms[roomId] = it }
    }
    override suspend fun removeSensor(roomId: String, sensor: String): Room {
        call("removeSensor $roomId $sensor")
        return rooms.getValue(roomId).copy(sensor = null, temperatureSource = "valves").also { rooms[roomId] = it }
    }
    override suspend fun groups(): List<Group> { call("groups"); return groups.values.toList() }
    override suspend fun createGroup(name: String, rooms: List<String>): Group {
        call("createGroup $name $rooms")
        return Group(name.lowercase(), name, rooms, null, null, "schedule", false).also { groups[it.id] = it }
    }
    override suspend fun patchGroup(id: String, patch: GroupRequest): Group {
        call("patchGroup $id $patch")
        val g = groups.getValue(id)
        return g.copy(name = patch.name ?: g.name, rooms = patch.rooms ?: g.rooms).also { groups[id] = it }
    }
    override suspend fun deleteGroup(id: String) { call("deleteGroup $id"); groups.remove(id) }
    override suspend fun controlGroup(id: String, request: GroupControlRequest): Group {
        call("controlGroup $id $request")
        val g = groups.getValue(id)
        val mode = request.mode?.name?.lowercase() ?: g.mode
        return g.copy(mode = mode, target = if (request.mode == Mode.MANUAL) request.target else g.target).also { groups[id] = it }
    }
    override suspend fun cancelGroupBoost(id: String): Group { call("cancelGroupBoost $id"); return groups.getValue(id) }

    override suspend fun controlHouse(request: GroupControlRequest): List<Room> {
        call("controlHouse $request")
        allZones().forEach { id ->
            updateZone(id) { z ->
                when {
                    request.boost != null -> z.copy(boost = Boost(request.target ?: 22.0, "2026-10-13T19:04:00+01:00"), target = request.target ?: 22.0)
                    request.mode == Mode.OFF -> z.copy(mode = Mode.OFF, target = null, boost = null)
                    request.mode == Mode.MANUAL -> z.copy(mode = Mode.MANUAL, manualTarget = request.target!!, target = request.target, boost = null)
                    request.mode == Mode.SCHEDULE -> z.copy(mode = Mode.SCHEDULE, target = 20.0, boost = null, override = null)
                    else -> z
                }
            }
        }
        return rooms.values.toList()
    }

    override suspend fun cancelHouseBoosts(): List<Room> {
        call("cancelHouseBoosts")
        allZones().forEach { id -> updateZone(id) { it.copy(boost = null) } }
        return rooms.values.toList()
    }

    private fun allZones() = rooms.values.flatMap { r -> r.zones.map { it.id } }

    override suspend fun schedule(zoneId: String): WeekSchedule { call("schedule $zoneId"); return schedules.getOrPut(zoneId) { defaultWeek() } }

    override suspend fun saveDay(zoneId: String, day: Day, blocks: List<Block>): WeekSchedule {
        call("saveDay $zoneId $day ${blocks.joinToString { "${it.start}=${it.target}" }}")
        val week = schedule(zoneId).with(day, DaySchedule(blocks))
        schedules[zoneId] = week
        return week
    }

    override suspend fun copyDay(zoneId: String, from: Day, to: List<Day>): WeekSchedule {
        call("copyDay $zoneId $from $to")
        var week = schedule(zoneId)
        to.forEach { week = week.with(it, week[from]) }
        schedules[zoneId] = week
        return week
    }

    override suspend fun startHoliday(until: String, from: String?, target: Double?): Status {
        call("startHoliday $until")
        status = status.copy(holiday = Holiday(from ?: status.serverTime, until, target ?: 12.0, true))
        return status
    }

    override suspend fun endHoliday(): Status { call("endHoliday"); status = status.copy(holiday = null); return status }
    /** Names of valves no room has yet. */
    val newValves = mutableListOf<String>()
    override suspend fun valves(unassignedOnly: Boolean): List<Valve> {
        call("valves")
        return newValves.map { Valve(it, room = null, online = true, currentTemperature = null, reportedSetpoint = null, runningState = null, battery = null, lowBattery = false, openWindow = false, childLock = false) }
    }
    override suspend fun patchValve(name: String, patch: PatchValveRequest): Valve { call("patchValve $name"); error("not used") }
    override fun liveEvents(): Flow<LiveEvent> = live.receiveAsFlow()

    var boost = BoostSettings()
    override suspend fun boostSettings(): BoostSettings { call("boostSettings"); return boost }
    override suspend fun saveBoostSettings(settings: BoostSettings): BoostSettings {
        call("saveBoostSettings ${settings.defaultTarget}")
        boost = settings
        return boost
    }

    var energy = EnergySettings()
    val meters = linkedMapOf<String, Meter>()
    /** Dashboards by period; a missing one is an empty dashboard. */
    val dashboards = mutableMapOf<String, EnergyDashboard>()
    override suspend fun energySettings(): EnergySettings { call("energySettings"); return energy }
    override suspend fun saveEnergyPrices(prices: EnergyPricesRequest): EnergySettings {
        call("saveEnergyPrices $prices")
        energy = energy.copy(
            gasPencePerKwh = prices.gasPencePerKwh,
            gasStandingPencePerDay = prices.gasStandingPencePerDay,
            boilerAverageKw = prices.boilerAverageKw,
            electricityPencePerKwh = prices.electricityPencePerKwh,
            electricityStandingPencePerDay = prices.electricityStandingPencePerDay,
        )
        return energy
    }
    override suspend fun meters(unassignedOnly: Boolean): List<Meter> {
        call("meters $unassignedOnly")
        return meters.values.map { it.copy(inUse = it.name == energy.electricityMeter, monitored = it.name in monitoredMeters, room = meterRooms[it.name]) }
            .filter { !unassignedOnly || (!it.inUse && !it.monitored) }
    }
    val monitoredMeters = mutableSetOf<String>()
    override suspend fun stopMonitoringMeter(meter: String) {
        call("stopMonitoringMeter $meter")
        monitoredMeters -= meter
        meterRooms.remove(meter)
    }

    override suspend fun monitorMeter(meter: String): Meter {
        call("monitorMeter $meter")
        monitoredMeters += meter
        return meters.getValue(meter).copy(monitored = true)
    }
    /** Meter name to room id. */
    val meterRooms = mutableMapOf<String, String>()
    override suspend fun putMeterInRoom(room: String, meter: String): Meter {
        call("putMeterInRoom $room $meter")
        if (meter !in monitoredMeters) throw IllegalStateException("only a monitored device can be put in a room")
        meterRooms[meter] = room
        return meters.getValue(meter).copy(monitored = true, room = room)
    }
    override suspend fun takeMeterOutOfRoom(room: String, meter: String) {
        call("takeMeterOutOfRoom $room $meter")
        meterRooms.remove(meter, room)
    }
    override suspend fun useMeterForHouse(meter: String): EnergySettings {
        call("useMeterForHouse $meter")
        energy = energy.copy(electricityMeter = meter, notices = emptyList())
        return energy
    }
    override suspend fun ignoreDevice(device: String) {
        call("ignoreDevice $device")
        ignoredNames += device
        ignoredMeters[device] = meters[device] ?: Meter(device)
        meters.remove(device)
        newValves.remove(device)
        sensors.remove(device)
    }
    val ignoredNames = sortedSetOf<String>()
    private val ignoredMeters = linkedMapOf<String, Meter>()
    override suspend fun ignoredDevices(): List<String> = ignoredNames.toList()
    override suspend fun showDeviceAgain(device: String) {
        call("showDeviceAgain $device")
        ignoredNames -= device
        ignoredMeters.remove(device)?.let { meters[device] = it }
    }
    val roomEnergyHistories = mutableMapOf<String, RoomEnergyHistory>()
    override suspend fun roomEnergyHistory(room: String, range: String): RoomEnergyHistory {
        call("roomEnergyHistory $room $range")
        return roomEnergyHistories[room] ?: RoomEnergyHistory(room, "2026-10-12T00:00:00Z", "2026-10-13T00:00:00Z")
    }

    val roomPlugs = mutableMapOf<String, RoomPlugs>()
    override suspend fun roomPlugs(room: String): RoomPlugs {
        call("roomPlugs $room")
        return roomPlugs[room] ?: RoomPlugs(room)
    }
    override suspend fun energyDashboard(period: String): EnergyDashboard {
        call("energyDashboard $period")
        return dashboards[period] ?: EnergyDashboard(period)
    }

    companion object {
        fun zone(id: String, mode: Mode = Mode.SCHEDULE, target: Double? = 20.0, current: Double? = 19.5, heating: Boolean = false) =
            Zone(id, listOf("$id TRV"), mode, manualTarget = 20.0, target = target, current = current, heating = heating, callingForHeat = heating)

        fun room(id: String, name: String, zone: Zone = zone(id)) = Room(
            id = id, name = name, control = Control.GROUPED, mode = zone.mode, current = zone.current,
            target = zone.target, heating = zone.heating, valves = zone.valves, zones = listOf(zone),
            notices = emptyList(), smartTemperatureControl = true,
        )

        fun defaultWeek(): WeekSchedule {
            val day = DaySchedule(listOf(Block("00:00", 16.0), Block("06:30", 20.0), Block("08:30", 16.0), Block("16:30", 20.0), Block("22:30", 16.0)))
            return WeekSchedule(day, day, day, day, day, day, day)
        }

        fun WeekSchedule.with(day: Day, schedule: DaySchedule) = when (day) {
            Day.MONDAY -> copy(monday = schedule)
            Day.TUESDAY -> copy(tuesday = schedule)
            Day.WEDNESDAY -> copy(wednesday = schedule)
            Day.THURSDAY -> copy(thursday = schedule)
            Day.FRIDAY -> copy(friday = schedule)
            Day.SATURDAY -> copy(saturday = schedule)
            Day.SUNDAY -> copy(sunday = schedule)
        }
    }
}
