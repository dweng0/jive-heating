package heating.app.ui.energy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import heating.app.data.EnergyArea
import heating.app.data.EnergySettings
import heating.app.data.HeatingApi
import heating.app.data.Meter
import heating.app.data.MeterJob
import heating.app.data.Room
import heating.app.data.friendlyError
import kotlinx.coroutines.launch

/** "412 W now", or why there is no reading. */
fun meterNow(m: Meter): String = when {
    !m.online -> "Offline"
    m.stale -> "Not reporting"
    else -> "${formatPower(m.power)} now"
}

/** The jobs a meter can be given, in order. */

/** Everything that measures electricity, set up in one place. Admin only. */
@Composable
fun EnergyManageScreen(api: HeatingApi, rooms: List<Room>, contentPadding: PaddingValues, onMessage: (String) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<EnergySettings?>(null) }
    var meters by remember { mutableStateOf(emptyList<Meter>()) }
    var areas by remember { mutableStateOf(emptyList<EnergyArea>()) }
    var version by remember { mutableIntStateOf(0) }
    var renaming by remember { mutableStateOf<Meter?>(null) }
    var jobFor by remember { mutableStateOf<Meter?>(null) }

    LaunchedEffect(version) {
        runCatching { api.energySettings() }.onSuccess { settings = it }
        runCatching { api.meters() }.onSuccess { meters = it }
        runCatching { api.energyAreas() }.onSuccess { areas = it.sortedBy { a -> a.name.lowercase() } }
    }
    val reload: () -> Unit = { version++ }
    /** Runs [action]; reloads on success, says why on failure. */
    fun act(done: String? = null, action: suspend () -> Unit) {
        scope.launch {
            runCatching { action() }
                .onSuccess { done?.let(onMessage); reload() }
                .onFailure { onMessage(friendlyError(it)) }
        }
    }

    val row: @Composable (Meter, @Composable () -> Unit) -> Unit = { m, extra -> MeterRow(m, { renaming = m }, { jobFor = m }, extra) }

    Column(
        Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        val s = settings
        if (s == null) {
            Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        s.notices.forEach { Text(it, color = MaterialTheme.colorScheme.error) }

        // Main meter
        var choosingMain by remember { mutableStateOf(false) }
        var stoppingMain by remember { mutableStateOf(false) }
        val main = meters.firstOrNull { it.name == s.electricityMeter }
        Section("Main meter", "The clamp that measures all electricity coming in") {
            if (main != null) row(main) {} else Text("No main meter chosen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { choosingMain = true }) { Text("Change") }
                if (main != null) TextButton(onClick = { stoppingMain = true }) { Text("Stop using") }
            }
        }
        if (choosingMain) {
            val candidates = meters.filter { it.reportsEnergy && !it.inUse && it.measures == null && !it.monitored }
            AlertDialog(
                onDismissRequest = { choosingMain = false },
                title = { Text("Choose the main meter") },
                text = {
                    Column {
                        if (candidates.isEmpty()) Text("No other meter can be the main meter. Free one up with \"Change job\" first.")
                        candidates.forEach { m ->
                            TextButton(onClick = {
                                choosingMain = false
                                act("${m.shownAs} is now the main meter") { api.useMeterForHouse(m.name) }
                            }) { Text("${m.shownAs} · ${meterNow(m)}") }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { choosingMain = false }) { Text("Cancel") } },
            )
        }
        if (stoppingMain && main != null) {
            AlertDialog(
                onDismissRequest = { stoppingMain = false },
                title = { Text("Stop using ${main.shownAs} as the main meter?") },
                text = { Text("The house's electricity will not be counted until another is chosen. What it recorded is kept.") },
                confirmButton = { TextButton(onClick = { stoppingMain = false; act("No main meter now") { api.stopUsingMeterForHouse(main.name) } }) { Text("Stop using") } },
                dismissButton = { TextButton(onClick = { stoppingMain = false }) { Text("Cancel") } },
            )
        }

        // Daily target
        var editingTarget by remember { mutableStateOf(false) }
        val target = s.dailyKwhTarget
        Section("Daily target", "How much electricity the house aims to use each day") {
            Text(target?.let { String.format(java.util.Locale.UK, "%.1f kWh a day", it) } ?: "No target", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("target-value"))
            if (target != null) Text("${formatCost(target * s.electricityPencePerKwh, s.currency)} a day at today's price", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { editingTarget = true }) { Text(if (target == null) "Set a target" else "Change") }
                if (target != null) TextButton(onClick = { act("Daily target cleared") { api.setDailyTarget(null) } }) { Text("Clear") }
            }
        }
        if (editingTarget) {
            var text by remember { mutableStateOf(target?.toString() ?: "") }
            val kwh = text.toDoubleOrNull()?.takeIf { it > 0 && it <= 1000 }
            AlertDialog(
                onDismissRequest = { editingTarget = false },
                title = { Text("Daily target") },
                text = {
                    OutlinedTextField(
                        text, { text = it }, label = { Text("kWh a day") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.testTag("target-input"),
                    )
                },
                confirmButton = {
                    TextButton(enabled = kwh != null, onClick = { editingTarget = false; act("Daily target saved") { api.setDailyTarget(kwh) } }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { editingTarget = false }) { Text("Cancel") } },
            )
        }

        // Areas
        Section("Areas", "Electricity that does not belong to a heated room, such as all the lights") {
            EnergyAreasSection(api, meters = meters, onChanged = reload, onMessage = onMessage, meterRow = row)
        }

        // Monitored devices
        var stopping by remember { mutableStateOf<Meter?>(null) }
        val monitored = meters.filter { it.monitored }
        Section("Monitored devices", "Plugs and clamps on one appliance or circuit, counted inside the main meter's figure") {
            if (monitored.isEmpty()) Text("No devices are monitored.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            monitored.forEach { m ->
                row(m) {
                    val place = m.area?.let { a -> areas.firstOrNull { it.id == a }?.name } ?: m.room?.let { r -> rooms.firstOrNull { it.id == r }?.name } ?: "No room"
                    Picker("Place: $place", listOf("" to "No room") + rooms.map { it.id to it.name } + areas.map { "area:${it.id}" to it.name }) { v ->
                        val job = when {
                            v.startsWith("area:") -> MeterJob("monitored", area = v.removePrefix("area:"))
                            v.isNotEmpty() -> MeterJob("monitored", room = v)
                            else -> MeterJob("monitored")
                        }
                        act { api.setMeterJob(m.name, job) }
                    }
                    TextButton(onClick = { stopping = m }) { Text("Stop monitoring") }
                }
            }
        }
        stopping?.let { m ->
            AlertDialog(
                onDismissRequest = { stopping = null },
                title = { Text("Stop monitoring ${m.shownAs}?") },
                text = { Text("It goes back to waiting to be set up. What it recorded is kept and still counted.") },
                confirmButton = { TextButton(onClick = { stopping = null; act("${m.shownAs} is no longer monitored") { api.stopMonitoringMeter(m.name) } }) { Text("Stop monitoring") } },
                dismissButton = { TextButton(onClick = { stopping = null }) { Text("Cancel") } },
            )
        }

        // Waiting to be set up
        val waiting = meters.filter { !it.inUse && !it.monitored && it.measures == null }
        Section("Waiting to be set up", "New clamps and plugs paired in Zigbee2MQTT. Give each a job.") {
            if (waiting.isEmpty()) Text("No meters are waiting.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            waiting.forEach { m -> row(m) { TextButton(onClick = { act("${m.shownAs} ignored") { api.ignoreDevice(m.name) } }) { Text("Ignore") } } }
        }
    }

    renaming?.let { m ->
        var label by remember(m) { mutableStateOf(if (m.label != null && m.label != m.name) m.label else "") }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename ${m.name}") },
            text = {
                Column {
                    Text("Leave it empty to use its Zigbee2MQTT name.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(label, { if (it.length <= 40) label = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.testTag("meter-label"))
                }
            },
            confirmButton = { TextButton(onClick = { renaming = null; act("Meter renamed") { api.labelMeter(m.name, label) } }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    jobFor?.let { m ->
        MeterJobDialog(m, areas, rooms, onDismiss = { jobFor = null }) { _, job ->
            jobFor = null
            act("${m.shownAs} updated") { api.setMeterJob(m.name, job) }
        }
    }
}

@Composable
private fun Section(title: String, description: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
        }
    }
}

/** One meter: its label (and Zigbee2MQTT name when it has another), power now, a backwards clamp's note, Rename and Change job. */
@Composable
fun MeterRow(meter: Meter, onRename: () -> Unit, onJob: () -> Unit, extra: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().testTag("meter-${meter.name}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(meter.shownAs, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text((if (meter.shownAs != meter.name) "${meter.name} · " else "") + meterNow(meter), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (meter.reversed) Text(REVERSED, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onRename) { Text("Rename") }
            TextButton(onClick = onJob) { Text("Change job") }
            extra()
        }
        HorizontalDivider()
    }
}

/** A button that opens a menu of [options] (value to label). */
@Composable
fun Picker(label: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(label) }
        DropdownMenu(open, { open = false }) {
            options.forEach { (value, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(value) })
            }
        }
    }
}
