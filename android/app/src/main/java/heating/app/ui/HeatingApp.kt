package heating.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import heating.app.notify.askedToNotify
import heating.app.notify.canNotify
import heating.app.notify.markAskedToNotify
import heating.app.notify.registerForPush
import heating.app.notify.shouldAskToNotify
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BeachAccess
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import heating.app.Connection
import heating.app.MainViewModel
import heating.app.data.Mode
import heating.app.data.Session
import heating.app.ui.account.AccountScreen
import heating.app.ui.dashboard.DashboardScreen
import heating.app.data.EnergyStore
import heating.app.ui.energy.EnergyPricesCard
import heating.app.ui.account.BoostSettingsCard
import heating.app.ui.energy.EnergySection
import heating.app.ui.energy.IgnoredDevicesCard
import heating.app.ui.energy.plugsWithNoRoom
import heating.app.ui.energy.MonitoredDevicesCard
import heating.app.ui.energy.RoomPickerDialog
import heating.app.ui.energy.WaitingDevicesCard
import heating.app.ui.dashboard.RoomHistory
import heating.app.ui.dashboard.RoomElectricity
import heating.app.ui.account.UsersScreen
import heating.app.ui.connect.SignInScreen
import heating.app.data.GroupControlRequest
import heating.app.ui.group.GroupActions
import heating.app.ui.group.GroupScreen
import heating.app.ui.room.RoomActions
import heating.app.ui.room.RoomScreen
import heating.app.ui.rooms.RoomsScreen
import heating.app.ui.schedule.ScheduleScreen
import heating.app.ui.settings.RoomSettingsScreen
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Composable
fun HeatingApp(vm: MainViewModel) {
    val connection = vm.connection
    if (connection == null) {
        SignInScreen(vm.lastUrl(), vm.lastUsername(), vm.signedOutReason, onSignedIn = vm::signedIn)
    } else {
        Connected(connection, onSignOut = vm::signOut, onPasswordChanged = vm::replaceSession)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Connected(connection: Connection, onSignOut: () -> Unit, onPasswordChanged: (Session) -> Unit) {
    val store = connection.store
    val state by store.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var holidaySheet by rememberSaveable { mutableStateOf(false) }
    val showError: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }

    // Notifications: ask once (Android 13+), and send this person's notifications to this phone.
    val context = LocalContext.current
    val askToNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(connection) {
        if (shouldAskToNotify(canNotify(context), askedToNotify(context))) {
            markAskedToNotify(context)
            askToNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        registerForPush(context, connection.account)
    }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); store.clearMessage() }
    }

    // Energy prices, meters and the dashboard: admins only.
    val energy = remember(connection) { EnergyStore(connection.api, scope) }
    val energyState by energy.state.collectAsStateWithLifecycle()
    LaunchedEffect(energy, state.admin) { energy.start(state.admin) }
    // A valve or sensor given to a room is no longer waiting to be set up.
    val assigned = state.rooms.map { it.valves to it.sensor }
    LaunchedEffect(energy, assigned) { if (state.admin) energy.loadWaiting() }
    LaunchedEffect(energyState.message) {
        energyState.message?.let { snackbar.showSnackbar(it); energy.clearMessage() }
    }

    val roomId = entry?.arguments?.getString("id")
    val room = roomId?.let { state.room(it) }
    val zoneArg = entry?.arguments?.getString("zoneId")
    val scheduleRoom = zoneArg?.let { z -> state.rooms.firstOrNull { r -> r.zones.any { it.id == z } } }
    val title = when (route) {
        "room/{id}" -> room?.name ?: ""
        "group/{id}" -> roomId?.let { state.group(it) }?.name ?: ""
        "schedule/{zoneId}" -> "${scheduleRoom?.name ?: ""} schedule"
        "settings/{id}" -> "${room?.name ?: ""} settings"
        "account" -> "Account"
        "users" -> "People"
        else -> null
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (title != null) {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    },
                    actions = {
                        if (route == "room/{id}" && roomId != null && state.admin) {
                            IconButton(onClick = { nav.navigate("settings/$roomId") }) { Icon(Icons.Rounded.MoreVert, "Room settings") }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        },
        bottomBar = {
            if (route == "rooms" || route == "dashboard" || route == null) {
                val onRooms = route != "dashboard"
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationBarItem(selected = onRooms, onClick = { if (!onRooms) nav.popBackStack("rooms", inclusive = false) }, icon = { Icon(Icons.Rounded.Home, null) }, label = { Text("Rooms") })
                    // The charts and holiday are the whole house's: admins only.
                    if (state.admin) {
                        NavigationBarItem(
                            selected = !onRooms,
                            onClick = { if (onRooms) nav.navigate("dashboard") { launchSingleTop = true } },
                            icon = { Icon(Icons.Rounded.ShowChart, null) },
                            label = { Text("Charts") },
                        )
                        NavigationBarItem(selected = false, onClick = { holidaySheet = true }, icon = { Icon(Icons.Rounded.BeachAccess, null) }, label = { Text("Holiday") })
                    }
                    NavigationBarItem(selected = false, onClick = { nav.navigate("account") }, icon = { Icon(Icons.Rounded.AccountCircle, null) }, label = { Text("Account") })
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "rooms") {
            composable("rooms") {
                // A plug may have been taken out of a room in its settings: list it here again.
                LaunchedEffect(Unit) { if (state.admin) energy.loadWaiting() }
                RoomsScreen(
                    state = state,
                    onRefresh = store::refresh,
                    onOpenRoom = { nav.navigate("room/$it") },
                    onCreateRoom = { name -> scope.launch { runCatching { connection.api.createRoom(name); store.refresh() }.onFailure { e -> showError(heating.app.data.friendlyError(e)) } } },
                    onOpenGroup = { nav.navigate("group/$it") },
                    onCreateGroup = { name, rooms -> store.createGroup(name, rooms) },
                    onHouseControl = store::controlHouse,
                    onCancelHouseBoosts = store::cancelHouseBoosts,
                    loadActivity = { limit, before -> connection.api.activity(limit, before) },
                    contentPadding = padding,
                    waitingDevices = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            WaitingDevicesCard(energyState.waiting, energy::useForHouse, energy::monitor, energy::ignore)
                            MonitoredDevicesCard(plugsWithNoRoom(energyState.monitored), state.rooms, energy::chooseRoom, energy::stopMonitoring)
                        }
                        energyState.placing?.let { RoomPickerDialog(it, state.rooms, energy::placeIn, energy::notNow) }
                    },
                )
            }
            composable("dashboard") {
                if (state.admin) {
                    DashboardScreen(connection.api, contentPadding = padding, energy = { EnergySection(energyState, energy::loadDashboard) })
                }
            }
            composable("group/{id}") {
                roomId?.let { state.group(it) }?.let { g ->
                    GroupScreen(
                        g, state, store.asGroupActions(),
                        onOpenRoom = { nav.navigate("room/$it") },
                        onDeleted = { nav.popBackStack("rooms", inclusive = false) },
                        contentPadding = padding,
                    )
                }
            }
            composable("room/{id}") {
                room?.let { r ->
                    RoomScreen(
                        r, state, store.asRoomActions(),
                        onEditSchedule = { zoneId -> nav.navigateSchedule(zoneId) },
                        contentPadding = padding,
                        // The service gives members only their own rooms' history, without the boiler.
                        history = {
                            Column {
                                RoomHistory(connection.api, r.id)
                                // Room energy is admin only.
                                if (state.admin) RoomElectricity(connection.api, r.id)
                            }
                        },
                    )
                }
            }
            composable("schedule/{zoneId}") { e ->
                ScheduleScreen(connection.api, e.arguments?.getString("zoneId")!!, onDone = { nav.popBackStack() }, onError = showError, contentPadding = padding)
            }
            composable("account") {
                AccountScreen(
                    account = connection.account,
                    baseUrl = connection.baseUrl,
                    onManageUsers = { nav.navigate("users") },
                    onPasswordChanged = onPasswordChanged,
                    onSignOut = onSignOut,
                    onError = showError,
                    contentPadding = padding,
                    adminSections = if (state.admin) ({
                        BoostSettingsCard(state.boostTarget, store::saveBoostTarget)
                        EnergyPricesCard(energyState.settings, energy::savePrices)
                        IgnoredDevicesCard(energyState.ignored, energy::showAgain)
                    }) else null,
                )
            }
            composable("users") {
                UsersScreen(connection.account, state.rooms, onError = showError, contentPadding = padding)
            }
            composable("settings/{id}") {
                room?.let { r ->
                    RoomSettingsScreen(r, connection.api, store, onDeleted = { nav.popBackStack("rooms", inclusive = false) }, onError = showError, contentPadding = padding)
                }
            }
        }
    }

    if (holidaySheet && state.admin) {
        HolidaySheet(
            active = state.status?.holiday != null,
            onStart = { days, target -> store.startHoliday(untilIso(days), target); holidaySheet = false },
            onEnd = { store.endHoliday(); holidaySheet = false },
            onDismiss = { holidaySheet = false },
        )
    }
}

private fun NavHostController.navigateSchedule(zoneId: String) = navigate("schedule/${android.net.Uri.encode(zoneId)}")

private fun heating.app.data.HeatingStore.asRoomActions() = object : RoomActions {
    override fun setMode(zoneId: String, mode: Mode, target: Double?) { this@asRoomActions.setMode(zoneId, mode, target) }
    override fun nudgeTarget(zoneId: String, target: Double) = this@asRoomActions.nudgeTarget(zoneId, target)
    override fun boost(zoneId: String, minutes: Int, target: Double) { this@asRoomActions.boost(zoneId, minutes, target) }
    override fun cancelBoost(zoneId: String) { this@asRoomActions.cancelBoost(zoneId) }
}

private fun heating.app.data.HeatingStore.asGroupActions() = object : GroupActions {
    override fun control(groupId: String, request: GroupControlRequest) { this@asGroupActions.controlGroup(groupId, request) }
    override fun cancelBoost(groupId: String) { this@asGroupActions.cancelGroupBoost(groupId) }
    override fun update(groupId: String, name: String, rooms: List<String>) { this@asGroupActions.updateGroup(groupId, name, rooms) }
    override fun delete(groupId: String) { this@asGroupActions.deleteGroup(groupId) }
}

/** Return [days] from now at 15:00 local time, as the service expects (ISO-8601 with offset). */
private fun untilIso(days: Int): String =
    ZonedDateTime.now().plusDays(days.toLong()).withHour(15).withMinute(0).withSecond(0).withNano(0)
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HolidaySheet(active: Boolean, onStart: (Int, Double) -> Unit, onEnd: () -> Unit, onDismiss: () -> Unit) {
    var days by rememberSaveable { mutableIntStateOf(7) }
    var target by rememberSaveable { mutableDoubleStateOf(12.0) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (active) "Holiday is on" else "Going away?", style = MaterialTheme.typography.headlineSmall)
            if (active) {
                Text("Every room is held low until you're back.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onEnd, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("End holiday now") }
            } else {
                Stepper("Back in", "$days day${if (days == 1) "" else "s"} (15:00)", onLess = { days = (days - 1).coerceAtLeast(1) }, onMore = { days = (days + 1).coerceAtMost(60) })
                Stepper("Keep rooms at", formatTemp(target), onLess = { target = stepTarget(target, -1) }, onMore = { target = stepTarget(target, +1) })
                Button(onClick = { onStart(days, target) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Start holiday") }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, onLess: () -> Unit, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
        FilledIconButton(onClick = onLess) { Icon(Icons.Rounded.Remove, "Less $label") }
        Text(" ", textAlign = TextAlign.Center)
        FilledIconButton(onClick = onMore) { Icon(Icons.Rounded.Add, "More $label") }
    }
}
