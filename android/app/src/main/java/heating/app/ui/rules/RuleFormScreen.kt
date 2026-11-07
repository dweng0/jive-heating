package heating.app.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import heating.app.data.HeatingApi
import heating.app.data.NOTIFY_EVERYONE
import heating.app.data.NOTIFY_ME
import heating.app.data.QuietHours
import heating.app.data.RuleRequest
import heating.app.data.RuleTestEvent
import heating.app.data.TRIGGER_CHANGES_TO
import heating.app.data.TRIGGER_IS
import heating.app.data.conditionText
import heating.app.data.draftFromEvent
import heating.app.data.friendlyError
import heating.app.data.previewLabel
import heating.app.data.toRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

private const val PREVIEW_EVENTS = 5

/**
 * The rule builder (admins). With [ruleId] it edits that rule; otherwise it starts from the event "Notify me when…" came
 * from: [topic] and, from a field, its path and [value]. It shows how the last 5 events on the topic would have gone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleFormScreen(
    api: HeatingApi,
    ruleId: String?,
    topic: String = "",
    field: String = "",
    value: String = "",
    onDone: () -> Unit,
    onError: (String) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
) {
    val scope = rememberCoroutineScope()
    var request by remember { mutableStateOf<RuleRequest?>(null) }
    var preview by remember { mutableStateOf<List<RuleTestEvent>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(ruleId) {
        runCatching {
            if (ruleId != null) api.rules().first { it.id == ruleId }.toRequest()
            else {
                val last: JsonElement? = if (topic.isEmpty()) null else api.events(listOf("topic" to topic, "limit" to "1")).events.firstOrNull()?.payload
                draftFromEvent(topic, last, field.ifEmpty { null }, value.ifEmpty { null })
            }
        }.onSuccess { request = it }.onFailure { onError(friendlyError(it)) }
    }
    val r = request ?: return
    LaunchedEffect(r.topic, r.conditions, r.trigger, r.title, r.message) {
        delay(400)
        preview = if (r.topic.isBlank()) emptyList() else runCatching { api.testRule(r).events.take(PREVIEW_EVENTS) }.getOrDefault(emptyList())
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(contentPadding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(r.name, { request = r.copy(name = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(r.topic, { request = r.copy(topic = it) }, label = { Text("Topic") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Text("When", style = MaterialTheme.typography.titleSmall)
        if (r.conditions.isEmpty()) Text("Every message on the topic", color = MaterialTheme.colorScheme.onSurfaceVariant)
        r.conditions.forEachIndexed { i, c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(conditionText(c), fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                TextButton({ request = r.copy(conditions = r.conditions.filterIndexed { j, _ -> j != i }) }) { Text("Remove") }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(r.trigger == TRIGGER_CHANGES_TO, { request = r.copy(trigger = TRIGGER_CHANGES_TO) }, label = { Text("changes to") })
            FilterChip(r.trigger == TRIGGER_IS, { request = r.copy(trigger = TRIGGER_IS) }, label = { Text("is") })
        }

        OutlinedTextField(r.title, { request = r.copy(title = it) }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(r.message, { request = r.copy(message = it) }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())

        Text("Notify", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(r.notify == NOTIFY_ME, { request = r.copy(notify = NOTIFY_ME) }, label = { Text("Me") })
            FilterChip(r.notify == NOTIFY_EVERYONE, { request = r.copy(notify = NOTIFY_EVERYONE) }, label = { Text("Everyone") })
        }
        OutlinedTextField(
            r.cooldownSeconds.toString(), { request = r.copy(cooldownSeconds = it.filter(Char::isDigit).take(5).toIntOrNull() ?: 0) },
            label = { Text("Cooldown (seconds)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Quiet hours", Modifier.weight(1f))
            Switch(r.quietHours != null, { request = r.copy(quietHours = if (it) QuietHours("22:00", "07:00") else null) })
        }
        r.quietHours?.let { q ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(q.from, { request = r.copy(quietHours = q.copy(from = it.take(5))) }, label = { Text("From") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(q.to, { request = r.copy(quietHours = q.copy(to = it.take(5))) }, label = { Text("To") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Send a summary when quiet hours end", Modifier.weight(1f))
                Switch(q.sendSummary, { request = r.copy(quietHours = q.copy(sendSummary = it)) })
            }
        }

        Text("Last $PREVIEW_EVENTS events", style = MaterialTheme.typography.titleSmall)
        if (preview.isEmpty()) Text("No events on this topic yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        preview.forEach { e ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    Text(previewLabel(e), style = MaterialTheme.typography.bodyMedium)
                    Text(e.payload, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }
        }

        Button(
            onClick = {
                saving = true
                scope.launch {
                    runCatching { if (ruleId == null) api.createRule(r) else api.updateRule(ruleId, r) }
                        .onSuccess { onDone() }.onFailure { onError(friendlyError(it)) }
                    saving = false
                }
            },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (ruleId == null) "Create rule" else "Save rule") }
    }
}
