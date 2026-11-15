package heating.app.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The button for [state]: "Update", "Try again" or "Allow", or null while downloading or installing. */
fun updateAction(state: UpdateState): String? = when (state) {
    is UpdateState.Available -> "Update"
    is UpdateState.Failed -> "Try again"
    is UpdateState.NeedsPermission -> "Allow"
    else -> null
}

/** A strip at the top of the app while a newer version is on offer or on its way. */
@Composable
fun UpdateBanner(state: UpdateState, onUpdate: () -> Unit) {
    val text = updateBanner(state) ?: return
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val error = state is UpdateState.Failed
            Icon(Icons.Rounded.SystemUpdate, null, tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            updateAction(state)?.let { TextButton(onClick = onUpdate) { Text(it) } }
        }
        if (state is UpdateState.Downloading) {
            val f = state.fraction
            if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
