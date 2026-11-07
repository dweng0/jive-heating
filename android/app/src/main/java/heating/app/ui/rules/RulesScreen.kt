package heating.app.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import heating.app.data.Rule
import heating.app.data.RuleChip
import heating.app.data.RuleHistoryEntry
import heating.app.data.RuleRow
import heating.app.data.deleteRuleQuestion
import heating.app.data.filterRuleRows
import heating.app.data.friendlyError
import heating.app.data.ruleRows
import heating.app.data.toRequest
import kotlinx.coroutines.launch

/** Account > Notifications: opens the rules list. */
@Composable
fun RulesCard(onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Notifications", style = MaterialTheme.typography.titleMedium)
            Text("Rules that tell you when something happens, and area notifications.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onOpen) { Text("Open notifications") }
        }
    }
}

/**
 * Settings > Notifications (admins): every rule with its topic, who it notifies, when it last sent and a switch, with the
 * area notifications; chips filter them, and a rule opens to edit, shows its history or is deleted after asking.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RulesScreen(api: HeatingApi, onEdit: (String) -> Unit, onError: (String) -> Unit = {}, contentPadding: PaddingValues = PaddingValues()) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<RuleRow>?>(null) }
    var chips by remember { mutableStateOf(emptySet<RuleChip>()) }
    var topicChip by remember { mutableStateOf(false) }
    var topic by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Rule?>(null) }
    var history by remember { mutableStateOf<Pair<Rule, List<RuleHistoryEntry>>?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        runCatching {
            val rules = api.rules()
            val areas = runCatching { api.energyAreas().map { it to api.areaAlert(it.id) } }.getOrDefault(emptyList())
            ruleRows(rules, areas)
        }.onSuccess { rows = it }.onFailure { onError(friendlyError(it)) }
    }
    fun act(block: suspend () -> Unit) = scope.launch { runCatching { block() }.onFailure { onError(friendlyError(it)) }; reload++ }

    val shown = filterRuleRows(rows.orEmpty(), chips, topic.takeIf { topicChip })
    LazyColumn(Modifier.padding(contentPadding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                RuleChip.entries.forEach { c ->
                    FilterChip(c in chips, { chips = if (c in chips) chips - c else chips + c }, label = { Text(c.label) })
                }
                FilterChip(topicChip, { topicChip = !topicChip }, label = { Text("Topic") })
            }
            if (topicChip) OutlinedTextField(topic, { topic = it }, label = { Text("Topic contains") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (rows != null && shown.isEmpty()) item { Text(if (rows!!.isEmpty()) "No rules yet. Open an event and choose Notify me when…" else "No rules match.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(shown, key = { it.rule?.id ?: "area-${it.areaId}" }) { row ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(row.name, style = MaterialTheme.typography.titleSmall)
                            Text(row.topic, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                        row.rule?.let { rule -> Switch(rule.enabled, { on -> act { api.updateRule(rule.id, rule.toRequest().copy(enabled = on)) } }) }
                    }
                    Text("Notifies: ${row.notifies}" + if (row.lastSent.isEmpty()) "" else " · ${row.lastSent}", style = MaterialTheme.typography.bodySmall)
                    row.paused?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    row.rule?.let { rule ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton({ onEdit(rule.id) }) { Text("Edit") }
                            TextButton({ scope.launch { history = rule to runCatching { api.ruleHistory(rule.id, 50) }.getOrDefault(emptyList()) } }) { Text("History") }
                            if (row.paused != null) TextButton({ act { api.resumeRule(rule.id) } }) { Text("Resume") }
                            TextButton({ deleting = rule }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }

    deleting?.let { rule ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(deleteRuleQuestion(rule.name)) },
            confirmButton = { TextButton({ deleting = null; act { api.deleteRule(rule.id) } }) { Text("Delete") } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } },
        )
    }
    history?.let { (rule, entries) ->
        AlertDialog(
            onDismissRequest = { history = null },
            title = { Text("History: ${rule.name}") },
            text = {
                if (entries.isEmpty()) Text("Nothing yet.")
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(entries) { e ->
                        Column {
                            Text("${e.at.replace('T', ' ').removeSuffix("Z")} · ${e.text}", style = MaterialTheme.typography.bodySmall)
                            Text(e.payload, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                    }
                }
            },
            confirmButton = { TextButton({ history = null }) { Text("Close") } },
        )
    }
}
