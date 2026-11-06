package heating.app.ui.energy

import heating.app.data.MeterJob
import heating.app.data.Meter
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import heating.app.data.EnergyArea
import heating.app.data.HeatingApi
import heating.app.data.friendlyError
import kotlinx.coroutines.launch

/** Named things with meters and no valves, such as "Lights": added, renamed and deleted here. Admins only. */
@Composable
fun EnergyAreasSection(
    api: HeatingApi,
    meters: List<Meter> = emptyList(),
    onChanged: () -> Unit = {},
    onMessage: (String) -> Unit = {},
    meterRow: (@Composable (Meter, @Composable () -> Unit) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var areas by remember { mutableStateOf(emptyList<EnergyArea>()) }
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<EnergyArea?>(null) }
    var deleting by remember { mutableStateOf<EnergyArea?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    // Read again whenever the meters change, so each area's clamp and shared circuits are current.
    LaunchedEffect(meters) { runCatching { api.energyAreas() }.onSuccess { areas = it.sortedBy { a -> a.name.lowercase() } } }
    fun act(action: suspend () -> Unit) {
        scope.launch { runCatching { action() }.onSuccess { onChanged() }.onFailure { onMessage(friendlyError(it)) } }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // On the management screen its section already has the heading.
        if (meterRow == null) {
            Text("Areas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Electricity that does not belong to a heated room, such as the lights or a garage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        areas.forEach { area ->
            val clamp = meters.firstOrNull { it.measures == area.id }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(area.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        clamp?.let { "${meterNow(it)} on ${it.shownAs}" } ?: "Sum of its plugs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("area-now-${area.id}"),
                    )
                }
                IconButton(onClick = { renaming = area }) { Icon(Icons.Filled.Edit, contentDescription = "Rename ${area.name}") }
                IconButton(onClick = { deleteError = null; deleting = area }) { Icon(Icons.Filled.Delete, contentDescription = "Delete ${area.name}") }
            }
            if (meterRow != null) {
                // Its whole-area meter: chosen, replaced or removed.
                clamp?.let { c -> meterRow(c) { TextButton(onClick = { act { api.setMeterJob(c.name, MeterJob("none")) } }) { Text("Remove") } } }
                val free = meters.filter { !it.inUse && !it.monitored && it.measures == null }
                if (free.isNotEmpty()) {
                    Picker(if (clamp == null) "Choose a clamp" else "Replace the clamp", free.map { it.name to it.shownAs }) { m ->
                        act { api.setMeterJob(m, MeterJob("area", area = area.id)) }
                    }
                }
                // Its plugs.
                meters.filter { it.area == area.id }.forEach { p ->
                    meterRow(p) { TextButton(onClick = { act { api.takeMeterOutOfArea(area.id, p.name) } }) { Text("Take out") } }
                }
                val loose = meters.filter { it.monitored && it.area != area.id }
                if (loose.isNotEmpty()) Picker("Add a monitored plug", loose.map { it.name to it.shownAs }) { m -> act { api.putMeterInArea(area.id, m) } }
                // Areas it shares a circuit with.
                val others = areas.filter { it.id != area.id }
                if (others.isNotEmpty()) {
                    Text("Shares a circuit with", style = MaterialTheme.typography.labelLarge)
                    others.forEach { o ->
                        val shared = o.id in area.overlaps
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(shared, { act { if (shared) api.stopSharingCircuit(area.id, o.id) else api.shareCircuit(area.id, o.id) } })
                            Text(o.name)
                        }
                    }
                }
                HorizontalDivider()
            }
        }
        deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick = { adding = true }) { Text("Add area") }
    }

    if (adding) {
        AreaNameDialog("New area", "Add area", "", onDismiss = { adding = false }) { name ->
            val area = api.addEnergyArea(name)
            areas = (areas + area).sortedBy { it.name.lowercase() }
            adding = false
            onChanged()
        }
    }
    renaming?.let { area ->
        AreaNameDialog("Rename ${area.name}", "Rename", area.name, onDismiss = { renaming = null }) { name ->
            val renamed = api.renameEnergyArea(area.id, name)
            areas = areas.map { if (it.id == renamed.id) renamed else it }.sortedBy { it.name.lowercase() }
            renaming = null
        }
    }
    deleting?.let { area ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${area.name}?") },
            text = { Text("The area goes from the Energy tab.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        runCatching { api.deleteEnergyArea(area.id) }
                            .onSuccess { areas = areas.filter { it.id != area.id } }
                            .onFailure { deleteError = friendlyError(it) }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/** Stays open with what was typed and says why when [onSave] throws, such as for a name another area uses. */
@Composable
private fun AreaNameDialog(title: String, action: String, initial: String, onDismiss: () -> Unit, onSave: suspend (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    singleLine = true,
                    label = { Text("Area name") },
                    modifier = Modifier.fillMaxWidth().testTag("area-name"),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("area-save"),
                onClick = { scope.launch { runCatching { onSave(name.trim()) }.onFailure { error = friendlyError(it) } } },
            ) { Text(action) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
