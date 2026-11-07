package dev.crumble.android.ui

import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.Icons
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.padding
import dev.crumble.android.model.SETTINGS_GROUPS
import dev.crumble.android.model.Route
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.fillMaxSize
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.crumble.android.model.AccountMode
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.ThemeChoice
import dev.crumble.android.model.canCreateAccount
import dev.crumble.android.model.isExposedHttp
import dev.crumble.android.model.replyNotificationsDescription

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    description: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun NotificationSettingsSection(model: AppModel) {
    val context = LocalContext.current
    val on = model.replyNotifications && model.notificationsAllowed

    GroupLabel("Notifications")
    GroupCard {
        SwitchRow(
            title = "Bot replies",
            description = replyNotificationsDescription(model.replyNotifications, model.notificationsAllowed),
            checked = on,
            onCheckedChange = model::changeReplyNotifications,
        )
        if (!model.notificationsAllowed) {
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                },
            ) {
                Text("Open Android settings")
            }
        }
        SwitchRow(
            title = "Sound",
            checked = model.notificationSound,
            onCheckedChange = model::changeNotificationSound,
            enabled = on,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSection(model: AppModel) {
    GroupLabel("Theme")
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        ThemeChoice.entries.forEachIndexed { index, choice ->
            SegmentedButton(
                selected = model.theme == choice,
                onClick = { model.chooseTheme(choice) },
                shape = SegmentedButtonDefaults.itemShape(index, ThemeChoice.entries.size),
                label = { Text(choice.label) },
            )
        }
    }
}

private fun settingsSubtitle(page: Route): String? = when (page) {
    Route.General -> "Notifications, theme, and account"
    Route.Providers -> "API keys and account sign-in"
    Route.JudgeModel -> "Checks what desktop jobs do"
    Route.VisionModel -> "Describes images"
    Route.WebSearch -> "Search provider"
    Route.Credentials -> "Secrets your bots can use"
    Route.Logins -> "Sites your bots can sign in to"
    Route.PasswordManagers -> "1Password and Bitwarden"
    Route.Integrations -> "MCP servers"
    Route.Coding -> "GitHub and workspaces"
    Route.MessageGateways -> "Talk to bots from other apps"
    Route.EventRelay -> "Events from outside"
    Route.Skills -> "Skills your bots follow"
    Route.Avatars -> "Gallery and previews"
    Route.Users -> "Accounts and invites"
    else -> null
}

/** Everything the website keeps under Settings, laid out like the system Settings app. */
@Composable
fun SettingsPanel(model: AppModel) {
    val tones = Tone.entries
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SearchPill(hint = "Search settings", onClick = { model.showingPalette = true }) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.padding(start = 8.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SETTINGS_GROUPS.forEachIndexed { groupIndex, group ->
            val pages = group.filter { it != Route.Users || model.isAdmin == true }
            Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
                pages.forEachIndexed { index, page ->
                    GroupedRow(
                        title = page.title,
                        subtitle = settingsSubtitle(page),
                        shape = groupedShape(index, pages.size),
                        onClick = { model.navigate(page) },
                        leading = { IconTile(routeIcon(page), tones[groupIndex % tones.size]) },
                    )
                }
            }
        }
    }
}

@Composable
fun GeneralPanel(model: AppModel) {
    val signingUp = model.accountMode == AccountMode.SignUp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SheetErrorBanner(model.errorText)

        if (model.token.isNotEmpty()) {
            NotificationSettingsSection(model)
            ThemeSection(model)
        }

        GroupLabel("Server")
        GroupCard {
            model.client?.baseURL?.takeIf { model.token.isNotEmpty() }?.let { CopyField("Address", it) }
            OutlinedTextField(
                value = model.serverURL,
                onValueChange = { model.serverURL = it },
                label = { Text("Server URL") },
                placeholder = { Text("http://10.0.2.2:7337") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (isExposedHttp(model.serverURL)) {
                Text(
                    text = "This server uses http, so your password and messages can be read on the network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (signingUp) {
                OutlinedTextField(
                    value = model.accountName,
                    onValueChange = { model.accountName = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = model.email,
                onValueChange = { model.email = it },
                label = { Text("Email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model.password,
                onValueChange = { model.password = it },
                label = { Text("Password") },
                placeholder = { Text(if (model.token.isEmpty() || signingUp) "Required" else "Signed in") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = SECRET_KEYBOARD,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (signingUp) {
                OutlinedTextField(
                    value = model.inviteCode,
                    onValueChange = { model.inviteCode = it },
                    label = { Text("Invite code") },
                    placeholder = { Text("Optional") },
                    supportingText = { Text("Paste the invite link or its code.") },
                    keyboardOptions = SECRET_KEYBOARD,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = { model.saveSettings() },
                    enabled = !model.savingConnection &&
                        (!signingUp || canCreateAccount(model.accountName, model.email, model.password)),
                ) {
                    Text(if (signingUp) "Create account" else "Save Connection")
                }
                if (model.savingConnection) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = "Connecting…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(
                onClick = { model.switchAccountMode() },
                enabled = !model.savingConnection,
            ) {
                Text(if (signingUp) "Already have an account? Sign in" else "Create an account")
            }
            Text(
                text = "Your session stays encrypted in the Android Keystore; the password is not stored. On the Android emulator, use http://10.0.2.2:7337 to reach a server running on your computer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (model.token.isNotEmpty() && !signingUp) {
            GroupLabel("Account")
            GroupedRow(
                title = if (model.signingOut) "Signing out…" else "Sign out",
                subtitle = model.email.ifEmpty { null },
                titleColor = MaterialTheme.colorScheme.error,
                shape = groupedShape(0, 1),
                enabled = !model.signingOut && !model.savingConnection,
                onClick = { model.signOut() },
            )
        }
    }
}
