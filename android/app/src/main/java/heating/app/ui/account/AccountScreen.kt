package heating.app.ui.account

import heating.app.notify.canNotify
import heating.app.data.NotificationPrefs
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PhonelinkErase
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import heating.app.data.AccountApi
import heating.app.data.AccountUser
import heating.app.data.Session
import heating.app.ui.appVersionLabel
import heating.app.ui.components.PasswordField
import heating.app.ui.components.ShowPasswordsToggle
import kotlinx.coroutines.launch

/** Who is signed in, change password, sign out, and (for admins) the way into user management. */
@Composable
fun AccountScreen(
    account: AccountApi,
    baseUrl: String,
    onManageUsers: () -> Unit,
    onPasswordChanged: (Session) -> Unit,
    onSignOut: () -> Unit,
    onError: (String) -> Unit,
    contentPadding: PaddingValues,
    /** Admin-only extras, such as energy prices; null for members. */
    adminSections: (@Composable () -> Unit)? = null,
) {
    var me by remember { mutableStateOf<AccountUser?>(null) }
    var changing by rememberSaveable { mutableStateOf(false) }
    var confirmEverywhere by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(account) {
        runCatching { account.me() }.onSuccess { me = it }.onFailure { e -> onError(heating.app.data.friendlyError(e)) }
    }

    Column(
        Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(me?.username ?: "…", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 4.dp))
        Text(
            buildString {
                append(if (me?.admin == true) "Admin" else "Member")
                append(" · ")
                append(baseUrl.removePrefix("https://").removePrefix("http://"))
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            if (me?.admin == true) {
                Row(Icons.Rounded.Group, "Manage users", "Add people, reset passwords", onManageUsers, chevron = true)
            }
            if (me?.id != null) {
                Row(Icons.Rounded.Key, "Change password", null, { changing = true })
                Row(Icons.Rounded.PhonelinkErase, "Sign out everywhere", "Ends your sessions on every device", { confirmEverywhere = true })
            }
            Row(Icons.AutoMirrored.Rounded.Logout, "Sign out", null, onSignOut)
        }

        if (me?.id != null) NotificationSwitches(account, onError)

        if (me?.admin == true) adminSections?.invoke()

        Text(
            appVersionLabel(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }

    if (changing) {
        ChangePasswordDialog(
            onDismiss = { changing = false },
            onChange = { current, new ->
                scope.launch {
                    runCatching { account.changePassword(current, new) }
                        .onSuccess { changing = false; onPasswordChanged(it); onError("Password changed. Other devices are signed out.") }
                        .onFailure { e -> onError(heating.app.data.friendlyError(e)) }
                }
            },
        )
    }

    if (confirmEverywhere) {
        AlertDialog(
            onDismissRequest = { confirmEverywhere = false },
            title = { Text("Sign out everywhere?") },
            text = { Text("You'll be signed out on this phone and every other device using your account.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEverywhere = false
                    scope.launch {
                        runCatching { account.logoutAll() }
                            .onSuccess { onSignOut() }
                            .onFailure { e -> onError(heating.app.data.friendlyError(e)) }
                    }
                }) { Text("Sign out everywhere") }
            },
            dismissButton = { TextButton(onClick = { confirmEverywhere = false }) { Text("Cancel") } },
        )
    }
}

/** This person's two notification switches, saved on the service so they follow them to any phone. */
@Composable
private fun NotificationSwitches(account: AccountApi, onError: (String) -> Unit) {
    var prefs by remember { mutableStateOf<NotificationPrefs?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(account) {
        runCatching { account.notifications() }.onSuccess { prefs = it }.onFailure { e -> onError(heating.app.data.friendlyError(e)) }
    }
    val current = prefs ?: return
    fun save(updated: NotificationPrefs) {
        prefs = updated
        scope.launch {
            runCatching { account.setNotifications(updated) }
                .onFailure { e -> prefs = current; onError(heating.app.data.friendlyError(e)) }
        }
    }

    Text("Notifications", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        SwitchRow("Someone turns the heating on", "From the app or a radiator's knob, when it isn't you", current.heatingOn) {
            save(current.copy(heatingOn = it))
        }
        SwitchRow("Heating finished", "When rooms someone turned on have warmed up", current.heatingFinished) {
            save(current.copy(heatingFinished = it))
        }
    }
    if (!canNotify(context)) {
        Text(
            "Notifications are turned off for this app in Android settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun Row(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit, chevron: Boolean = false) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(icon, null) },
        trailingContent = if (chevron) ({ Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) }) else null,
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickableRow(onClick),
    )
}

@Composable
private fun ChangePasswordDialog(onDismiss: () -> Unit, onChange: (current: String, new: String) -> Unit) {
    var current by rememberSaveable { mutableStateOf("") }
    var new by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var show by rememberSaveable { mutableStateOf(false) }
    val valid = current.isNotEmpty() && new.length >= AccountApi.MIN_PASSWORD && new == confirm
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PasswordField(current, { current = it }, "Current password", show)
                PasswordField(new, { new = it }, "New password", show)
                PasswordField(confirm, { confirm = it }, "Confirm new password", show)
                ShowPasswordsToggle(show, { show = it }, "Show passwords")
                Text(
                    "At least ${AccountApi.MIN_PASSWORD} characters.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onChange(current, new) }) { Text("Change") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
