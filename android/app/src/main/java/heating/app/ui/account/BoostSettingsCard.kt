package heating.app.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import heating.app.ui.boostSettingLine
import heating.app.ui.formatTemp
import heating.app.ui.stepTarget

/** The household's boost temperature: where every boost starts. Shown to admins only. */
@Composable
fun BoostSettingsCard(target: Double, onSave: (Double) -> Unit) {
    var draft by remember { mutableStateOf<Double?>(null) }

    Text("Boost", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(boostSettingLine(target), style = MaterialTheme.typography.bodyLarge)
            Text(
                "Where every boost starts. Anyone can still pick another temperature when they boost.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { draft = target }, modifier = Modifier.align(Alignment.End)) { Text("Edit") }
        }
    }

    draft?.let { current ->
        AlertDialog(
            onDismissRequest = { draft = null },
            title = { Text("Default boost temperature") },
            text = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { draft = stepTarget(current, -1) }) { Icon(Icons.Rounded.Remove, contentDescription = "Cooler default") }
                    Text(formatTemp(current), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 16.dp))
                    IconButton(onClick = { draft = stepTarget(current, 1) }) { Icon(Icons.Rounded.Add, contentDescription = "Warmer default") }
                }
            },
            confirmButton = { TextButton(onClick = { onSave(current); draft = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { draft = null }) { Text("Cancel") } },
        )
    }
}
