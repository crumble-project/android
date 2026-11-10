package dev.crumble.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.CredentialForm
import dev.crumble.android.model.LoginForm
import dev.crumble.android.model.LoginMode
import dev.crumble.android.model.ONE_PASSWORD
import dev.crumble.android.model.SecretDraft

// The password keyboard turns off suggestions and learning, so typed secrets stay out of its dictionary.
internal val SECRET_KEYBOARD = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Password,
)

private val PLAIN_KEYBOARD = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Ascii,
)

private val REFERENCE_PLACEHOLDERS = mapOf(ONE_PASSWORD to "op://vault/item/field", "bitwarden" to "Secret ID")

@Composable
private fun SecretNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A secret's value, or a password manager reference once one is connected. */
@Composable
private fun SecretSourceField(
    model: AppModel,
    label: String,
    draft: SecretDraft,
    onChange: (SecretDraft) -> Unit,
    valueDescription: String,
    concealed: Boolean = true,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val connected = model.connectedManagers
    val manager = connected.firstOrNull { it.id == draft.manager }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (connected.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                FilterChip(draft.manager == null, { onChange(draft.source(null)) }, { Text("Crumble") }, enabled = enabled)
                for (item in connected) {
                    FilterChip(draft.manager == item.id, { onChange(draft.source(item.id)) }, { Text(item.name) }, enabled = enabled)
                }
            }
        }
        OutlinedTextField(
            value = draft.text,
            onValueChange = { onChange(draft.copy(text = it)) },
            label = { Text(label) },
            placeholder = draft.manager?.let { REFERENCE_PLACEHOLDERS[it] }?.let { { Text(it) } },
            singleLine = true,
            enabled = enabled,
            visualTransformation = if (draft.manager == null && concealed) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (draft.manager == null) SECRET_KEYBOARD else PLAIN_KEYBOARD,
            textStyle = if (draft.manager == null) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            draft.manager != null -> SecretNote("Read from ${manager?.name ?: "the password manager"} when it's used. Crumble checks it can read it when you save.")
            connected.isNotEmpty() -> SecretNote(valueDescription)
            else -> {
                SecretNote(valueDescription)
                Text(
                    "Connect a password manager to read it from there instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { openWebSettings(context, model, "password-managers") },
                )
            }
        }
    }
}

@Composable
private fun SecretSheet(
    noun: String,
    reason: String?,
    error: String,
    ready: Boolean,
    saving: Boolean,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("New $noun") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!reason.isNullOrEmpty()) SecretNote(reason)
                SheetErrorBanner(error)
                content()
            }
        },
        confirmButton = {
            Button(enabled = ready, onClick = onSubmit) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Add")
                }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun NameAndHosts(
    name: String,
    hosts: String,
    invalid: Boolean,
    enabled: Boolean,
    nameNote: String,
    hostsPlaceholder: String,
    hostsNote: String,
    mono: Boolean,
    onHosts: (String) -> Unit,
) {
    OutlinedTextField(
        value = name,
        onValueChange = {},
        label = { Text("Name") },
        singleLine = true,
        enabled = false,
        isError = invalid,
        keyboardOptions = PLAIN_KEYBOARD,
        textStyle = if (mono) MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
        supportingText = { Text(nameNote) },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = hosts,
        onValueChange = onHosts,
        label = { Text("Hosts") },
        placeholder = { Text(hostsPlaceholder) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = PLAIN_KEYBOARD,
        supportingText = { Text(hostsNote) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun CredentialFormDialog(model: AppModel, form: CredentialForm) {
    val update = { next: CredentialForm -> if (model.credentialForm != null) model.credentialForm = next }
    SecretSheet(
        noun = "Credential",
        reason = form.request.reason,
        error = form.error,
        ready = form.ready,
        saving = form.saving,
        onSubmit = { model.saveCredential() },
        onClose = { model.closeCredentialForm() },
    ) {
        NameAndHosts(
            name = form.name,
            hosts = form.hosts,
            invalid = form.nameInvalid,
            enabled = !form.saving,
            nameNote = "The environment variable coding jobs see.",
            hostsPlaceholder = "api.example.com, *.example.com",
            hostsNote = "The bot proposed these. The value goes only to them, so check they belong to the service.",
            mono = true,
            onHosts = { update(form.copy(hosts = it)) },
        )
        SecretSourceField(
            model = model,
            label = "Value",
            draft = form.secret,
            onChange = { update(form.copy(secret = it)) },
            valueDescription = "Stored encrypted and never shown again.",
            enabled = !form.saving,
        )
    }
}

@Composable
fun LoginFormDialog(model: AppModel, form: LoginForm) {
    val update = { next: LoginForm -> if (model.loginForm != null) model.loginForm = next }
    SecretSheet(
        noun = "Login",
        reason = form.request.reason,
        error = form.error,
        ready = form.ready,
        saving = form.saving,
        onSubmit = { model.saveLogin() },
        onClose = { model.closeLoginForm() },
    ) {
        NameAndHosts(
            name = form.name,
            hosts = form.hosts,
            invalid = form.nameInvalid,
            enabled = !form.saving,
            nameNote = "How desktop jobs refer to it.",
            hostsPlaceholder = "github.com, *.github.com",
            hostsNote = "The bot proposed these. The login is filled only into https pages on them, so check they belong to the site.",
            mono = false,
            onHosts = { update(form.copy(hosts = it)) },
        )
        if (model.connectedManagers.any { it.id == ONE_PASSWORD }) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(form.mode == LoginMode.fields, { update(form.copy(mode = LoginMode.fields)) }, { Text("Fields") }, enabled = !form.saving)
                FilterChip(form.mode == LoginMode.item, { update(form.copy(mode = LoginMode.item)) }, { Text("1Password item") }, enabled = !form.saving)
            }
        }
        if (form.mode == LoginMode.item) {
            OutlinedTextField(
                value = form.item,
                onValueChange = { update(form.copy(item = it)) },
                label = { Text("Item") },
                placeholder = { Text("op://vault/item") },
                singleLine = true,
                enabled = !form.saving,
                keyboardOptions = PLAIN_KEYBOARD,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                supportingText = { Text("Fills the item's username, password, and one-time code, if it has one. Crumble checks it can read them when you save.") },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            SecretSourceField(
                model = model,
                label = "Username",
                draft = form.username,
                onChange = { update(form.copy(username = it)) },
                valueDescription = "Stored encrypted and never shown again.",
                concealed = false,
                enabled = !form.saving,
            )
            SecretSourceField(
                model = model,
                label = "Password",
                draft = form.password,
                onChange = { update(form.copy(password = it)) },
                valueDescription = "Stored encrypted and never shown again. Desktop jobs fill it without seeing it.",
                enabled = !form.saving,
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("One-time code", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = form.hasCode, enabled = !form.saving, onCheckedChange = { update(form.copy(hasCode = it)) })
            }
            if (form.hasCode) {
                SecretSourceField(
                    model = model,
                    label = "One-time code secret",
                    draft = form.code,
                    onChange = { update(form.copy(code = it)) },
                    valueDescription = "The setup key or otpauth:// URI from the site's two-factor setup. Crumble computes each code from it.",
                    enabled = !form.saving,
                )
            }
        }
    }
}
