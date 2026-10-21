package heating.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import heating.app.data.Room

/** Disclaimer shown when someone taps the sensor info icon. */
fun sensorDisclaimer(sensor: String) =
    "This room's temperature comes from a separate thermometer ($sensor), not the radiator valves. " +
        "Its readings can lag a few minutes behind the room, and the valves work from the last reading " +
        "received. If it stops reporting, the room goes back to the valves' own readings."

/**
 * For a room with a sensor: an info button that explains where the temperature comes from, or a
 * warning when the sensor is offline and the valves are used instead. Nothing otherwise.
 */
@Composable
fun SensorInfo(room: Room, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val sensor = room.sensor ?: return
    var explaining by rememberSaveable { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (room.sensorOffline) {
            Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(4.dp))
            Text("Sensor offline · using valve readings", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
        IconButton(onClick = { explaining = true }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Outlined.Info, contentDescription = "About this temperature", tint = color, modifier = Modifier.size(18.dp))
        }
    }
    if (explaining) {
        AlertDialog(
            onDismissRequest = { explaining = false },
            title = { Text(if (room.sensorOffline) "$sensor is offline" else "External thermometer") },
            text = { Text(sensorDisclaimer(sensor)) },
            confirmButton = { TextButton(onClick = { explaining = false }) { Text("OK") } },
        )
    }
}
