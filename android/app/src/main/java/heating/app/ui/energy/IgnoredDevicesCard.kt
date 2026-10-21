package heating.app.ui.energy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Devices an admin chose to leave alone, each with "Show again". Nothing shows when none are ignored. */
@Composable
fun IgnoredDevicesCard(ignored: List<String>, onShowAgain: (String) -> Unit) {
    if (ignored.isEmpty()) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Ignored devices", style = MaterialTheme.typography.titleMedium)
            ignored.forEach { name ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onShowAgain(name) }) { Text("Show again") }
                }
            }
        }
    }
}
