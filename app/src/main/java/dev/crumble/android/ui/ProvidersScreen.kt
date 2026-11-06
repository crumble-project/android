package dev.crumble.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.crumble.android.generated.Provider
import dev.crumble.android.generated.ProviderLoginEvent
import dev.crumble.android.generated.ProviderLoginSession
import dev.crumble.android.generated.ProviderLoginSessionPrompt
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.ProviderKeyForm
import dev.crumble.android.model.ProviderLoginView
import dev.crumble.android.model.isWebLink
import dev.crumble.android.model.keyAvailable
import dev.crumble.android.model.matchingProviders
import dev.crumble.android.model.providerStatus
import dev.crumble.android.model.signInAvailable

@Composable
private fun ProviderSection(model: AppModel, title: String, description: String?, providers: List<Provider>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
        if (description != null) {
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (provider in providers) ProviderRow(model, provider)
    }
}

@Composable
private fun ProviderRow(model: AppModel, provider: Provider) {
    val label = providerStatus(provider, model.passwordManagers)
    val oauth = provider.authMethods.firstOrNull { it.type == "oauth" }
    val busy = "provider:${provider.provider}" in model.pendingMutations
    val external = provider.externalAuth
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(provider.name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Column(modifier = Modifier.weight(1f)) {
                    Text(provider.name, style = MaterialTheme.typography.bodyMedium)
                    if (label != null) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (provider.configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (oauth != null) {
                        Text(oauth.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                when {
                    busy -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    external != null -> ExternalMenu(model, provider)
                    else -> ProviderActions(model, provider)
                }
            }
            external?.message?.takeIf { external.status == "cli_missing" || external.status == "error" }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProviderActions(model: AppModel, provider: Provider) {
    val signIn = provider.signInAvailable()
    val addKey = provider.keyAvailable()
    val signInLabel = if (provider.activeLoginId != null) "Continue sign-in" else "Sign in"
    if (provider.credentialType == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (addKey) TextButton(onClick = { model.openProviderKey(provider) }) { Text("Add key") }
            if (signIn) OutlinedButton(onClick = { model.openProviderLogin(provider) }) { Text(signInLabel) }
        }
        return
    }
    SecretOverflowMenu { close ->
        if (signIn) {
            DropdownMenuItem(text = { Text(signInLabel) }, onClick = {
                close()
                model.openProviderLogin(provider)
            })
        }
        if (addKey) {
            DropdownMenuItem(text = { Text(if (provider.credentialType == "api_key") "Replace key" else "Use a key") }, onClick = {
                close()
                model.openProviderKey(provider)
            })
        }
        DropdownMenuItem(
            text = { Text(if (provider.credentialType == "oauth") "Sign out" else "Remove key", color = MaterialTheme.colorScheme.error) },
            onClick = {
                close()
                model.removeProviderCredential(provider)
            },
        )
    }
}

/** A provider that signs in outside Crumble: its menu copies the command to run on the server. */
@Composable
private fun ExternalMenu(model: AppModel, provider: Provider) {
    val external = provider.externalAuth ?: return
    val signedIn = external.status == "signed_in"
    val command = (if (signedIn) external.signOutCommand else external.signInCommand) ?: return
    if (external.status == "cli_missing") return
    val clipboard = LocalClipboardManager.current
    SecretOverflowMenu { close ->
        DropdownMenuItem(text = { Text(if (signedIn) "Copy sign-out command" else "Copy sign-in command") }, onClick = {
            close()
            clipboard.setText(AnnotatedString(command))
            model.showNotice("Copied")
        })
    }
}

@Composable
fun ProvidersPanel(model: AppModel) {
    var search by remember { mutableStateOf("") }
    val shown = matchingProviders(model.providerAccounts, search.trim().lowercase())
    val ready = shown.filter { it.configured }
    val rest = shown.filter { !it.configured }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search providers") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (model.providerAccounts.isNotEmpty() && shown.isEmpty()) {
            item {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No providers match", style = MaterialTheme.typography.titleSmall)
                    Text("Try another name.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (ready.isNotEmpty()) {
            item {
                ProviderSection(model, "Set up", "Bots use these providers' models. Keys and sign-ins are stored encrypted on the server and shared by every bot.", ready)
            }
        }
        if (rest.isNotEmpty()) {
            item { ProviderSection(model, if (ready.isNotEmpty()) "Available" else "Providers", null, rest) }
        }
    }
}

@Composable
fun ProviderKeyDialog(model: AppModel, form: ProviderKeyForm) {
    val provider = form.provider
    AlertDialog(
        onDismissRequest = { model.closeProviderKey() },
        title = { Text("${if (provider.credentialType == "api_key") "Replace" else "Add"} ${provider.name} API key") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetErrorBanner(form.error)
                SecretSourceField(
                    model = model,
                    label = "API key",
                    draft = form.key,
                    onChange = { next -> model.providerKeyForm?.let { model.providerKeyForm = it.copy(key = next) } },
                    valueDescription = "Stored encrypted and never shown again.",
                    valuePlaceholder = "API key",
                    enabled = !form.saving,
                )
            }
        },
        confirmButton = {
            Button(enabled = form.key.changed && !form.saving, onClick = { model.saveProviderAccountKey() }) {
                if (form.saving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Save")
                }
            }
        },
        dismissButton = { TextButton(onClick = { model.closeProviderKey() }) { Text("Cancel") } },
    )
}

/** Runs a provider's account sign-in; closing keeps it going on the server so it can be continued. */
@Composable
fun ProviderLoginDialog(model: AppModel, view: ProviderLoginView) {
    val status = view.session?.status
    AlertDialog(
        onDismissRequest = { model.hideProviderLogin() },
        title = { Text("Sign in to ${view.provider.name}") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "The server keeps this sign-in. Bots that use ${view.provider.name} share it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProviderLoginBody(model, view)
                SheetErrorBanner(view.error)
            }
        },
        confirmButton = {
            OutlinedButton(onClick = { model.hideProviderLogin() }) { Text(if (status == "pending") "Hide" else "Close") }
        },
        dismissButton = {
            if (view.sessionID != null && status == "pending") {
                TextButton(enabled = !view.cancelling, onClick = { model.cancelProviderLogin() }) { Text("Cancel sign-in") }
            }
        },
    )
}

@Composable
private fun ProviderLoginBody(model: AppModel, view: ProviderLoginView) {
    val session = view.session
    when {
        view.sessionID == null -> Button(enabled = !view.starting, onClick = { model.startProviderLogin() }) {
            if (view.starting) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Text("Start sign-in")
            }
        }
        view.gone -> Text("This sign-in ended or was lost on restart. Close this and start again.", style = MaterialTheme.typography.bodySmall)
        session == null -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        session.status == "succeeded" -> Text("Signed in.", style = MaterialTheme.typography.bodyMedium)
        session.status != "pending" -> SheetErrorBanner(session.failure?.message ?: "Sign-in ${session.status}.")
        else -> ProviderLoginSteps(model, view, session)
    }
}

@Composable
private fun ProviderLoginSteps(model: AppModel, view: ProviderLoginView, session: ProviderLoginSession) {
    session.events.forEachIndexed { index, event ->
        ProviderLoginEventView(event, last = index == session.events.lastIndex)
    }
    val prompt = session.prompt
    when {
        prompt != null -> ProviderLoginPrompt(model, view, prompt)
        session.events.lastOrNull() !is ProviderLoginEvent.Variant4 -> Text("Waiting for the provider…", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ProviderLoginEventView(event: ProviderLoginEvent, last: Boolean) {
    val uriHandler = LocalUriHandler.current
    val open = { url: String -> if (isWebLink(url)) runCatching { uriHandler.openUri(url) } }
    when (event) {
        is ProviderLoginEvent.Variant1 -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(event.value.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (link in event.value.links.orEmpty()) {
                TextButton(onClick = { open(link.url) }) { Text(link.label ?: link.url) }
            }
        }
        is ProviderLoginEvent.Variant2 -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            event.value.instructions?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = { open(event.value.url) }) { Text("Open sign-in page") }
        }
        is ProviderLoginEvent.Variant3 -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Enter this code at", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { open(event.value.verificationUri) }) { Text(event.value.verificationUri) }
            SelectionContainer {
                Text(event.value.userCode, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
            }
        }
        is ProviderLoginEvent.Variant4 -> if (last) Text(event.value.message, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ProviderLoginPrompt(model: AppModel, view: ProviderLoginView, prompt: ProviderLoginSessionPrompt) {
    val error = view.answerError
    if (prompt.type == "select") {
        Text(prompt.message, style = MaterialTheme.typography.bodyMedium)
        for (option in prompt.options.orEmpty()) {
            OutlinedButton(enabled = !view.answering, onClick = { model.answerProviderLogin(option.id) }, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(option.label)
                    option.description?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        SheetErrorBanner(error)
        return
    }
    val concealed = prompt.type == "secret"
    val value = view.answer.text.trim()
    OutlinedTextField(
        value = view.answer.text,
        onValueChange = { text -> model.providerLogin?.let { model.providerLogin = it.copy(answer = it.answer.copy(text = text)) } },
        label = { Text(prompt.message) },
        placeholder = prompt.placeholder?.let { { Text(it) } },
        enabled = !view.answering,
        singleLine = true,
        isError = error.isNotEmpty(),
        visualTransformation = if (concealed) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = SECRET_KEYBOARD,
        modifier = Modifier.fillMaxWidth(),
    )
    SheetErrorBanner(error)
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Button(enabled = value.isNotEmpty() && !view.answering, onClick = { model.answerProviderLogin(value) }) {
            if (view.answering) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Text("Continue")
            }
        }
    }
}
