package heating.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.dp
import heating.app.data.HeatingApi
import heating.app.data.Meter
import heating.app.data.Room
import heating.app.data.RoomPlugs
import heating.app.data.friendlyError
import heating.app.ui.energy.CostWarning
import heating.app.ui.energy.plugLine
import heating.app.ui.energy.plugWarning
import heating.app.ui.energy.plugsToAdd
import heating.app.ui.energy.roomTotal
import heating.app.ui.energy.roomWarning
import kotlinx.coroutines.launch

/** The room's monitored plugs: the room's cost today above each plug's power now and cost today. Admin only. */
@Composable
fun RoomPlugsSection(room: Room, api: HeatingApi, onError: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var plugs by remember { mutableStateOf<RoomPlugs?>(null) }
    var meters by remember { mutableStateOf<List<Meter>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    var adding by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(room.id, reload) {
        runCatching { api.roomPlugs(room.id) }.onSuccess { plugs = it }.onFailure { onError(friendlyError(it)) }
        meters = runCatching { api.meters() }.getOrElse { emptyList() }
    }

    fun run(block: suspend () -> Unit) = scope.launch {
        runCatching { block() }.onFailure { onError(friendlyError(it)) }
        reload++
    }

    val current = plugs
    if (current != null && current.plugs.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(roomTotal(current), style = MaterialTheme.typography.titleMedium)
            roomWarning(current)?.let { CostWarning(it) }
        }
    }
    current?.plugs?.forEach { plug ->
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(plug.meter, style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(plugLine(plug, current.currency), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        plugWarning(plug)?.let { CostWarning(it) }
                    }
                }
                TextButton(onClick = { run { api.takeMeterOutOfRoom(room.id, plug.meter) } }) { Text("Take out") }
            }
        }
    }
    if (current != null && current.plugs.isEmpty()) {
        Text(
            "Put a monitored plug here to see what this room uses. Its use is part of the main meter's.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val free = plugsToAdd(meters, room.id)
    OutlinedButton(onClick = { adding = true }, enabled = free.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(if (free.isEmpty()) "No other monitored plugs" else "Add plug")
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add plug") },
            text = {
                Column {
                    free.forEach { m ->
                        TextButton(
                            onClick = { adding = false; run { api.putMeterInRoom(room.id, m.name) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(m.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}
