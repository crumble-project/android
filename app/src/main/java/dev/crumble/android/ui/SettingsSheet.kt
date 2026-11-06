package dev.crumble.android.ui

import androidx.compose.foundation.layout.padding
import dev.crumble.android.model.SETTINGS_PAGES
import dev.crumble.android.model.Route
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.ListItem
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
    )
}

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
            Text(title, style = MaterialTheme.typography.bodyMedium)
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

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Notifications")
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

@Composable
private fun ThemeSection(model: AppModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Theme")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeChoice.entries.forEach { choice ->
                FilterChip(
                    selected = model.theme == choice,
                    onClick = { model.chooseTheme(choice) },
                    label = { Text(choice.label) },
                )
            }
        }
    }
}

/** Everything the website keeps under Settings, in its order. */
@Composable
fun SettingsPanel(model: AppModel) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SETTINGS_PAGES.filter { it != Route.Users || model.isAdmin == true }.forEach { page ->
            ListItem(
                headlineContent = { Text(page.title) },
                leadingContent = { Icon(routeIcon(page), contentDescription = null) },
                modifier = Modifier.clickable(role = Role.Button) { model.navigate(page) },
            )
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
        .padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
) {
    SheetErrorBanner(model.errorText)

    if (model.token.isNotEmpty()) {
        NotificationSettingsSection(model)
        ThemeSection(model)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Server")
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
        if (model.token.isNotEmpty() && !signingUp) {
            OutlinedButton(
                onClick = { model.signOut() },
                enabled = !model.signingOut && !model.savingConnection,
            ) {
                Text(if (model.signingOut) "Signing out…" else "Sign out")
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

}
}
