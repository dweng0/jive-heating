package heating.app.ui.energy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import heating.app.data.Room
import heating.app.data.WaitingDevices

/**
 * New valves, sensors and electricity meters that nothing uses yet. Valves and sensors are added
 * from a room; a meter can be made the house meter or monitored here. Admins only; nothing shows when none wait.
 */
@Composable
fun WaitingDevicesCard(waiting: WaitingDevices, onUseForHouse: (String) -> Unit, onMonitor: (String) -> Unit, onIgnore: (String) -> Unit) {
    if (waiting.isEmpty) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Waiting to be set up", style = MaterialTheme.typography.titleMedium)
            waiting.meters.forEach { meter ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(meter.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        meterKind(meter) + " · " + if (meter.stale || !meter.online) "not reporting" else "${formatPower(meter.power)} now",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { onUseForHouse(meter.name) }, modifier = Modifier.fillMaxWidth()) { Text(MAIN_METER_BUTTON) }
                    Text(MAIN_METER_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { onMonitor(meter.name) }, modifier = Modifier.fillMaxWidth()) { Text(MONITOR_BUTTON) }
                    Text(MONITOR_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { onIgnore(meter.name) }, modifier = Modifier.fillMaxWidth()) { Text("Ignore") }
                }
            }
            waiting.valves.forEach { Device(it, "Radiator valve · add it from a room", onIgnore) }
            waiting.sensors.forEach { Device(it, "Temperature sensor · add it from a room", onIgnore) }
        }
    }
}

/** Asks which room [meter], just monitored, is in: one button per room, or "Not now" to leave it in no room. */
@Composable
fun RoomPickerDialog(meter: String, rooms: List<Room>, onPick: (String) -> Unit, onNotNow: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNotNow,
        title = { Text(roomPickerTitle(meter)) },
        text = {
            Column {
                Text(ROOM_PICKER_HINT, style = MaterialTheme.typography.bodyMedium)
                rooms.forEach { room ->
                    TextButton(onClick = { onPick(room.id) }, modifier = Modifier.fillMaxWidth()) { Text(room.name) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onNotNow) { Text(NOT_NOW) } },
    )
}

@Composable
private fun Device(name: String, kind: String, onIgnore: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(kind, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { onIgnore(name) }) { Text("Ignore") }
    }
}
