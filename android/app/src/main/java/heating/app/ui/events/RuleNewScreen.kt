package heating.app.ui.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Placeholder for the rule builder (Feature: Rule builder): shows the topic, field and value "Notify me when…" carried here. */
@Composable
fun RuleNewScreen(topic: String, field: String, value: String, contentPadding: PaddingValues = PaddingValues()) {
    Column(Modifier.padding(contentPadding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Rule builder coming soon", style = MaterialTheme.typography.titleMedium)
                listOf("Topic" to topic, "Field" to field, "Value" to value).filter { it.second.isNotEmpty() }.forEach { (k, v) ->
                    Text("$k: $v", fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

/** Account > Events: opens the event explorer. */
@Composable
fun EventsCard(onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Events", style = MaterialTheme.typography.titleMedium)
            Text("Every message the service saw: filter it, open one, or be told when it happens.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onOpen) { Text("Open events") }
        }
    }
}
