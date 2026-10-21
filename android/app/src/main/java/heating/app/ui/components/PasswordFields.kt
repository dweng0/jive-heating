package heating.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** A password box; [visible] shows what was typed (see [ShowPasswordsToggle]). */
@Composable
fun PasswordField(value: String, onChange: (String) -> Unit, label: String, visible: Boolean, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (visible) KeyboardType.Text else KeyboardType.Password,
            imeAction = ImeAction.Next,
            autoCorrectEnabled = false,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** "Show password" with the whole row as the touch target, for big thumbs. */
@Composable
fun ShowPasswordsToggle(checked: Boolean, onChange: (Boolean) -> Unit, label: String = "Show password") {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}
