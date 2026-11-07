package heating.app.ui.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
