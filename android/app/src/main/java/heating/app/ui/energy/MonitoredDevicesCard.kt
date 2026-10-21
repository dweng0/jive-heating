package heating.app.ui.energy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import heating.app.data.Meter
import heating.app.data.Room

/**
 * Monitored plugs in no room, each with a room picker. A plug that is not a room's own (a shared kettle)
 * is left here; one put in a room leaves the card and lives in that room's settings. Admins only; nothing
 * shows when every plug is in a room. "Disconnect" stops monitoring one that is not worth listing.
 */
@Composable
fun MonitoredDevicesCard(monitored: List<Meter>, rooms: List<Room>, onChooseRoom: (meter: String, room: String?) -> Unit, onStopMonitoring: (String) -> Unit) {
    if (monitored.isEmpty()) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Monitored devices", style = MaterialTheme.typography.titleMedium)
            Text(
                "Part of the house's electricity. Put a plug in a room to see that room's use; leave a shared one in no room.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            monitored.forEach { meter -> MonitoredDevice(meter, rooms, onChooseRoom, onStopMonitoring) }
        }
    }
}

@Composable
private fun MonitoredDevice(meter: Meter, rooms: List<Room>, onChooseRoom: (meter: String, room: String?) -> Unit, onStopMonitoring: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(meter.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (meter.stale || !meter.online) "not reporting" else "${formatPower(meter.power)} now",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            OutlinedButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "Room for ${meter.name}" }) {
                Text(plugRoomName(meter.room, rooms.associate { it.id to it.name }))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(NO_ROOM) }, onClick = { menu = false; onChooseRoom(meter.name, null) })
                rooms.forEach { room ->
                    DropdownMenuItem(text = { Text(room.name) }, onClick = { menu = false; onChooseRoom(meter.name, room.id) })
                }
            }
        }
        TextButton(onClick = { onStopMonitoring(meter.name) }) { Text(DISCONNECT) }
    }
}
