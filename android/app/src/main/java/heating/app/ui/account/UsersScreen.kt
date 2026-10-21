package heating.app.ui.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import heating.app.data.AccountApi
import heating.app.data.AccountUser
import heating.app.data.Room
import heating.app.ui.components.PasswordField
import heating.app.ui.components.ShowPasswordsToggle
import kotlinx.coroutines.launch

/**
 * Admin only: everyone who can sign in. Add people, reset a password, make or remove an admin, delete,
 * and tap a member to tick the rooms they can see ([rooms] is every room, as an admin sees them).
 */
@Composable
fun UsersScreen(account: AccountApi, rooms: List<Room>, onError: (String) -> Unit, contentPadding: PaddingValues) {
    var users by remember { mutableStateOf<List<AccountUser>>(emptyList()) }
    var me by remember { mutableStateOf<AccountUser?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var resetting by remember { mutableStateOf<AccountUser?>(null) }
    var deleting by remember { mutableStateOf<AccountUser?>(null) }
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun act(failure: String, block: suspend () -> Unit) = scope.launch {
        runCatching { block(); users = account.users() }.onFailure { e -> onError(heating.app.data.friendlyError(e)) }
    }

    LaunchedEffect(account) {
        runCatching { me = account.me(); users = account.users() }.onFailure { e -> onError(heating.app.data.friendlyError(e)) }
    }

    Box(Modifier.fillMaxSize().padding(contentPadding)) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(users, key = { it.id ?: it.username }) { user ->
                UserCard(
                    user = user,
                    isMe = user.id == me?.id,
                    onOpen = { opened = user.id },
                    onResetPassword = { resetting = user },
                    onToggleAdmin = { act("Couldn't change admin") { account.patchUser(user.id!!, admin = !user.admin) } },
                    onDelete = { deleting = user },
                )
            }
        }
        ExtendedFloatingActionButton(
            onClick = { adding = true },
            icon = { Icon(Icons.Rounded.Add, null) },
            text = { Text("Add person") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (adding) {
        AddUserDialog(
            onDismiss = { adding = false },
            onAdd = { name, password, admin ->
                act("Couldn't add ${name.trim()}") { account.createUser(name.trim(), password, admin); adding = false }
            },
        )
    }

    resetting?.let { user ->
        ResetPasswordDialog(
            username = user.username,
            onDismiss = { resetting = null },
            onReset = { password ->
                act("Couldn't reset the password") {
                    account.patchUser(user.id!!, password = password)
                    resetting = null
                    onError("${user.username}'s password changed. They're signed out everywhere.")
                }
            },
        )
    }

    users.firstOrNull { it.id != null && it.id == opened }?.let { user ->
        RoomsDialog(
            user = user,
            rooms = rooms,
            saving = saving,
            onTick = { roomId, ticked ->
                saving = true
                scope.launch {
                    // Each tick saves straight away, with the member's full new list.
                    runCatching { account.setUserRooms(user.id!!, tickRoom(rooms, user.rooms, roomId, ticked)) }
                        .onSuccess { saved -> users = users.map { if (it.id == saved.id) saved else it } }
                        .onFailure { e -> onError(heating.app.data.friendlyError(e)) }
                    saving = false
                }
            },
            onDismiss = { opened = null },
        )
    }

    deleting?.let { user ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove ${user.username}?") },
            text = { Text("They'll be signed out on every device and won't be able to sign in again.") },
            confirmButton = {
                TextButton(onClick = { act("Couldn't remove ${user.username}") { account.deleteUser(user.id!!) }; deleting = null }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun UserCard(user: AccountUser, isMe: Boolean, onOpen: () -> Unit, onResetPassword: () -> Unit, onToggleAdmin: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(onClick = onOpen, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        ListItem(
            headlineContent = { Text(if (isMe) "${user.username} (you)" else user.username) },
            supportingContent = { Text((if (user.admin) "Admin" else "Member") + " · " + roomsSummary(user)) },
            leadingContent = { Icon(if (user.admin) Icons.Rounded.Shield else Icons.Rounded.Person, null) },
            trailingContent = {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Options for ${user.username}") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Reset password") }, onClick = { menu = false; onResetPassword() })
                        DropdownMenuItem(
                            text = { Text(if (user.admin) "Remove admin" else "Make admin") },
                            onClick = { menu = false; onToggleAdmin() },
                        )
                        if (!isMe) {
                            DropdownMenuItem(
                                text = { Text("Remove", color = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; onDelete() },
                            )
                        }
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        )
    }
}

/** A member's rooms: a tick box per room, saved on every change. Admins see every room and get no tick boxes. */
@Composable
private fun RoomsDialog(user: AccountUser, rooms: List<Room>, saving: Boolean, onTick: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${user.username}'s rooms") },
        text = {
            Column {
                if (user.admin) {
                    Text("Sees every room. Admins see and control the whole house.")
                } else {
                    Text(
                        "They see and control only the ticked rooms. Changes save straight away.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (rooms.isEmpty()) Text("There are no rooms yet.", Modifier.padding(top = 8.dp))
                    roomTicks(user, rooms).forEach { tick ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !saving) { onTick(tick.id, !tick.ticked) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = tick.ticked, onCheckedChange = { onTick(tick.id, it) }, enabled = !saving)
                            Text(tick.name)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun AddUserDialog(onDismiss: () -> Unit, onAdd: (String, String, Boolean) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var admin by rememberSaveable { mutableStateOf(false) }
    var show by rememberSaveable { mutableStateOf(false) }
    val valid = name.isNotBlank() && password.length >= AccountApi.MIN_PASSWORD
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add person") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                PasswordField(password, { password = it }, "Password", show)
                ShowPasswordsToggle(show, { show = it })
                Text(
                    "At least ${AccountApi.MIN_PASSWORD} characters. They can change it later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { admin = !admin }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Admin")
                        Text("Can add and remove people", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = admin, onCheckedChange = { admin = it })
                }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onAdd(name, password, admin) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ResetPasswordDialog(username: String, onDismiss: () -> Unit, onReset: (String) -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    var show by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New password for $username") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PasswordField(password, { password = it }, "New password", show)
                ShowPasswordsToggle(show, { show = it })
                Text(
                    "They'll be signed out everywhere and need this password to sign back in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(enabled = password.length >= AccountApi.MIN_PASSWORD, onClick = { onReset(password) }) { Text("Set password") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun Modifier.clickableRow(onClick: () -> Unit) = this.clickable(onClick = onClick)
