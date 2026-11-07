package heating.app.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import heating.app.data.FieldKind
import heating.app.data.OperatorChoice
import heating.app.data.RuleCondition
import heating.app.data.SeenField
import heating.app.data.buildCondition
import heating.app.data.operatorsFor

/** Add a condition: pick a field seen on the topic (with its recent values), an operator that fits it, and a value picked or typed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConditionAdder(seen: List<SeenField>, onAdd: (RuleCondition) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf<SeenField?>(null) }
    var typedField by remember { mutableStateOf("") }
    var choice by remember { mutableStateOf<OperatorChoice?>(null) }
    var value by remember { mutableStateOf("") }
    if (!open) {
        OutlinedButton({ open = true }) { Text("Add condition") }
        return
    }
    fun close() { open = false; field = null; typedField = ""; choice = null; value = "" }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Field", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                seen.forEach { f ->
                    FilterChip(field == f, { field = f; choice = null; value = "" }, label = { Text("${f.path}: ${f.recent.joinToString(", ")}", maxLines = 1) })
                }
            }
            if (seen.isEmpty()) {
                OutlinedTextField(typedField, { typedField = it; field = null }, label = { Text("Field path") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            val kind = field?.kind ?: FieldKind.TEXT
            val path = field?.path ?: typedField
            if (path.isNotBlank()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    operatorsFor(kind).forEach { o -> FilterChip(choice == o, { choice = o }, label = { Text(o.label) }) }
                }
            }
            if (choice?.takesValue == true) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    field?.recent?.forEach { v -> FilterChip(value == v, { value = v }, label = { Text(v) }) }
                }
                OutlinedTextField(value, { value = it }, label = { Text("Value") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onAdd(buildCondition(path, choice!!, value.trim())); close() },
                    enabled = path.isNotBlank() && choice != null && (choice?.takesValue != true || value.isNotBlank()),
                ) { Text("Add") }
                TextButton({ close() }) { Text("Cancel") }
            }
        }
    }
}
