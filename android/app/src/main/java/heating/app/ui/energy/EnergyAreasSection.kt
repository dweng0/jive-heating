package heating.app.ui.energy

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
fun EnergyAreasSection(api: HeatingApi) {
    val scope = rememberCoroutineScope()
    var areas by remember { mutableStateOf(emptyList<EnergyArea>()) }
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<EnergyArea?>(null) }
    var deleting by remember { mutableStateOf<EnergyArea?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { runCatching { api.energyAreas() }.onSuccess { areas = it } }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Areas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Electricity that does not belong to a heated room, such as the lights or a garage.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        areas.forEach { area ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(area.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                IconButton(onClick = { renaming = area }) { Icon(Icons.Filled.Edit, contentDescription = "Rename ${area.name}") }
                IconButton(onClick = { deleteError = null; deleting = area }) { Icon(Icons.Filled.Delete, contentDescription = "Delete ${area.name}") }
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
