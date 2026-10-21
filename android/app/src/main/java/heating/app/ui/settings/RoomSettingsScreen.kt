package heating.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import heating.app.data.Control
import heating.app.data.HeatingApi
import heating.app.data.HeatingStore
import heating.app.data.PatchRoomRequest
import heating.app.data.PatchValveRequest
import heating.app.data.Room
import heating.app.data.Valve
import heating.app.ui.formatTemp
import heating.app.ui.theme.HeatingTheme
import kotlinx.coroutines.launch

@Composable
fun RoomSettingsScreen(
    room: Room,
    api: HeatingApi,
    store: HeatingStore,
    onDeleted: () -> Unit,
    onError: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    val scope = rememberCoroutineScope()
    var valves by remember { mutableStateOf<List<Valve>>(emptyList()) }
    var sensors by remember { mutableStateOf<List<heating.app.data.Sensor>>(emptyList()) }
    var pickingSensor by rememberSaveable { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var choosingSource by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(room.valves, reload) {
        runCatching { api.valves() }.onSuccess { valves = it }.onFailure { onError(heating.app.data.friendlyError(it)) }
        // An older service has no sensors; show none.
        sensors = runCatching { api.sensors() }.getOrElse { emptyList() }
    }

    fun run(block: suspend () -> Unit) = scope.launch {
        runCatching { block() }.onFailure { onError(heating.app.data.friendlyError(it)) }
        reload++
    }

    Column(
        Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Section("Radiators in this room") {
            val options = listOf(Control.GROUPED to "Work together", Control.INDEPENDENT to "Separately")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, (control, label) ->
                    SegmentedButton(
                        selected = room.control == control,
                        onClick = {
                            when {
                                room.control == control -> Unit
                                control == Control.INDEPENDENT -> store.patchRoom(room.id, PatchRoomRequest(control = control))
                                room.valves.size <= 1 -> store.patchRoom(room.id, PatchRoomRequest(control = control, sourceValve = room.valves.firstOrNull()))
                                else -> choosingSource = true
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(label) }
                }
            }
            Text(
                if (room.control == Control.GROUPED) {
                    "One dial and one schedule for the whole room. The room temperature is the average of its radiators."
                } else {
                    "Each radiator gets its own dial, schedule and boost. They still show together under ${room.name}."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Valves") {
            valves.filter { it.name in room.valves }.forEach { valve ->
                ValveCard(
                    valve = valve,
                    onOpenWindow = { on -> run { api.patchValve(valve.name, PatchValveRequest(openWindow = on)) } },
                    onChildLock = { on -> run { api.patchValve(valve.name, PatchValveRequest(childLock = on)) } },
                    onRemove = { run { api.removeValve(room.id, valve.name); store.refresh() } },
                )
            }
            val unassigned = valves.filter { it.room == null }
            OutlinedButton(
                onClick = { picking = true },
                enabled = unassigned.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text(if (unassigned.isEmpty()) "No unassigned valves" else "+ Add a valve (${unassigned.size} unassigned)") }
        }

        Section("Temperature sensor") {
            val current = room.sensor?.let { name -> sensors.firstOrNull { it.name == name } }
            if (room.sensor != null) {
                SensorCard(
                    name = room.sensor,
                    sensor = current,
                    offline = room.sensorOffline,
                    onRemove = { run { api.removeSensor(room.id, room.sensor); store.refresh() } },
                )
            } else {
                Text(
                    "Optional. A sensor away from the radiator measures the room better than the valves, which warm up with their radiator.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val unassignedSensors = sensors.filter { it.room == null }
            OutlinedButton(
                onClick = { pickingSensor = true },
                enabled = unassignedSensors.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Text(
                    when {
                        unassignedSensors.isEmpty() -> "No unassigned sensors"
                        room.sensor != null -> "Replace sensor (${unassignedSensors.size} available)"
                        else -> "+ Add a sensor (${unassignedSensors.size} available)"
                    },
                )
            }
        }

        Section("Plugs") {
            RoomPlugsSection(room, api, onError)
        }

        Section("Comfort") {
            SwitchRow(
                title = "Smart temperature control",
                subtitle = "Steadier temperature, fewer swings",
                checked = room.smartTemperatureControl,
                onChange = { store.patchRoom(room.id, PatchRoomRequest(smartTemperatureControl = it)) },
            )
        }

        Section("Room") {
            OutlinedButton(onClick = { renaming = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Rename room") }
            Button(
                onClick = { deleting = true },
                colors = ButtonDefaults.buttonColors(containerColor = HeatingTheme.colors.danger, contentColor = HeatingTheme.colors.onDanger),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text("Delete room") }
        }
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Add a valve") },
            text = {
                Column {
                    valves.filter { it.room == null }.forEach { v ->
                        TextButton(
                            onClick = { picking = false; run { api.assignValve(room.id, v.name); store.refresh() } },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(v.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        )
    }

    if (pickingSensor) {
        AlertDialog(
            onDismissRequest = { pickingSensor = false },
            title = { Text(if (room.sensor != null) "Replace sensor" else "Add a sensor") },
            text = {
                Column {
                    if (room.sensor != null) {
                        Text("${room.sensor} will be taken out of this room.", style = MaterialTheme.typography.bodyMedium)
                    }
                    sensors.filter { it.room == null }.forEach { s ->
                        TextButton(
                            onClick = { pickingSensor = false; run { api.assignSensor(room.id, s.name); store.refresh() } },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                listOfNotNull(s.name, formatTemp(s.temperature), s.battery?.let { "battery $it%" }, if (s.online) null else "offline")
                                    .joinToString(" · "),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingSensor = false }) { Text("Cancel") } },
        )
    }

    if (choosingSource) {
        var source by rememberSaveable { mutableStateOf(room.valves.first()) }
        AlertDialog(
            onDismissRequest = { choosingSource = false },
            title = { Text("Work together") },
            text = {
                Column {
                    Text("Which radiator's settings should the room keep?", style = MaterialTheme.typography.bodyMedium)
                    room.valves.forEach { v ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = v == source, onClick = { source = v })
                            Text(v)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    choosingSource = false
                    store.patchRoom(room.id, PatchRoomRequest(control = Control.GROUPED, sourceValve = source))
                }) { Text("Combine") }
            },
            dismissButton = { TextButton(onClick = { choosingSource = false }) { Text("Cancel") } },
        )
    }

    if (renaming) {
        var name by rememberSaveable { mutableStateOf(room.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename room") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    renaming = false
                    store.patchRoom(room.id, PatchRoomRequest(name = name.trim()))
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete ${room.name}?") },
            text = { Text("Its valves will be unassigned and turned off (frost protection still works).") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    scope.launch {
                        runCatching { api.deleteRoom(room.id); store.refresh() }
                            .onSuccess { onDeleted() }
                            .onFailure { onError(heating.app.data.friendlyError(it)) }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun SensorCard(name: String, sensor: heating.app.data.Sensor?, offline: Boolean, onRemove: () -> Unit) {
    val colors = HeatingTheme.colors
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                val (bg, fg, label) = when {
                    offline -> Triple(colors.danger, colors.onDanger, "Offline")
                    sensor?.lowBattery == true -> Triple(colors.danger, colors.onDanger, "Battery low")
                    else -> Triple(colors.ok, colors.onOk, "OK")
                }
                Surface(shape = CircleShape, color = bg) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = fg, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
            Text(
                listOfNotNull(
                    formatTemp(sensor?.temperature),
                    sensor?.battery?.let { "battery $it%" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (offline) "Not reporting, so the room is using its valves' own readings." else heating.app.ui.components.sensorDisclaimer(name),
                style = MaterialTheme.typography.bodySmall,
                color = if (offline) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRemove) { Text("Remove from room") }
        }
    }
}

@Composable
private fun ValveCard(valve: Valve, onOpenWindow: (Boolean) -> Unit, onChildLock: (Boolean) -> Unit, onRemove: () -> Unit) {
    val colors = HeatingTheme.colors
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(valve.name, style = MaterialTheme.typography.titleMedium)
                val (bg, fg, label) = when {
                    !valve.online -> Triple(colors.off, colors.onOff, "Offline")
                    valve.lowBattery -> Triple(colors.danger, colors.onDanger, "Battery low")
                    else -> Triple(colors.ok, colors.onOk, "OK")
                }
                Surface(shape = CircleShape, color = bg) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = fg, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
            Text(
                listOfNotNull(
                    if (valve.online) "Online" else "Offline",
                    formatTemp(valve.currentTemperature),
                    valve.battery?.let { "battery $it%" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.padding(top = 6.dp), color = colors.track)
            SwitchRow("Open window detection", null, valve.openWindow, onOpenWindow)
            HorizontalDivider(color = colors.track)
            SwitchRow("Child lock", null, valve.childLock, onChildLock)
            TextButton(onClick = onRemove) { Text("Remove from room") }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
