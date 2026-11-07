package heating.app.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import heating.app.data.DeliveriesList
import heating.app.data.Delivery
import heating.app.data.HeatingApi
import heating.app.data.friendlyError
import kotlinx.coroutines.launch

/** "1 failed notification", "2 failed notifications". */
fun failedNotifications(count: Int): String = "$count failed notification${if (count == 1) "" else "s"}"

/** "jane: push service unreachable or refusing" for the last try of a failed delivery; empty with none. */
fun lastFailure(delivery: Delivery): String = delivery.attempts.lastOrNull()?.error.orEmpty()

/** Failed notification deliveries, with Retry, Retry all and Clear. Admins only (the service answers 403 otherwise). */
@Composable
fun FailedDeliveries(api: HeatingApi, onError: (String) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<DeliveriesList?>(null) }

    suspend fun load() {
        runCatching { api.deliveries("failed") }.onSuccess { list = it }
    }
    LaunchedEffect(api) { load() }

    fun act(work: suspend () -> Unit) {
        scope.launch { runCatching { work() }.onFailure { onError(friendlyError(it)) }; load() }
    }

    val failed = list ?: return
    Column(Modifier.fillMaxWidth().testTag("failed-deliveries"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Notification deliveries", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (failed.deliveries.isEmpty()) {
            Text("Every notification has been delivered.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Text(failedNotifications(failed.failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("failed-count"))
        failed.deliveries.forEach { d ->
            Column(Modifier.fillMaxWidth().testTag("failed-${d.id}")) {
                Text(d.title, fontWeight = FontWeight.SemiBold)
                Text(d.body, style = MaterialTheme.typography.bodySmall)
                Text("For ${d.person} · ${d.at.take(16).replace('T', ' ')}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(lastFailure(d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Row {
                    TextButton(onClick = { act { api.retryDelivery(d.id) } }) { Text("Retry") }
                    TextButton(onClick = { act { api.clearDelivery(d.id) } }) { Text("Clear") }
                }
            }
        }
        OutlinedButton(onClick = { act { api.retryAllDeliveries() } }) { Text("Retry all") }
    }
}
