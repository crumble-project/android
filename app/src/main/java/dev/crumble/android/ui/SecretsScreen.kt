package dev.crumble.android.ui

import androidx.compose.foundation.shape.CircleShape
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.crumble.android.generated.PasswordManager
import dev.crumble.android.model.Agent
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.CredentialForm
import dev.crumble.android.model.LoginForm
import dev.crumble.android.model.LoginMode
import dev.crumble.android.model.ManagerTokenForm
import dev.crumble.android.model.ONE_PASSWORD
import dev.crumble.android.model.Route
import dev.crumble.android.model.SKILLS_ALL
import dev.crumble.android.model.SecretAsk
import dev.crumble.android.model.SecretDraft
import dev.crumble.android.model.SecretKind
import dev.crumble.android.model.SecretRow
import dev.crumble.android.model.SecretUsesView
import dev.crumble.android.model.activityTime
import dev.crumble.android.model.keepPlaceholder
import dev.crumble.android.model.matchesSecret
import dev.crumble.android.model.secretBotsShown
import dev.crumble.android.model.secretKey
import dev.crumble.android.model.taskRunDate

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

private val TOKEN_LABELS = mapOf(ONE_PASSWORD to "Service account token", "bitwarden" to "Access token")

/** One kind of secret on its page: credentials and logins differ only in copy and data. */
private class SecretPage(
    val kind: SecretKind,
    val searchLabel: String,
    val empty: String,
    val noMatch: String,
    val note: String,
    val usedVerb: String,
    val neverUsed: String,
    val usesLabel: String,
    val deleteText: String,
    val mono: Boolean,
    val scope: String,
    val onScope: (String) -> Unit,
    val rowsOf: (String) -> List<SecretRow>?,
    val asksOf: (String) -> List<SecretAsk>,
    val failures: Map<String, String>,
    val onAdd: (String, SecretAsk) -> Unit,
    val onDecline: (String, String) -> Unit,
    val onEdit: (String, String) -> Unit,
    val onDelete: (String, String) -> Unit,
)

@Composable
fun CredentialsPanel(model: AppModel) {
    SecretPageView(
        model,
        SecretPage(
            kind = SecretKind.credential,
            searchLabel = "Search credentials",
            empty = "None yet. The bot asks for one when a coding job needs it.",
            noMatch = "No credentials match your search.",
            note = "Coding jobs get a placeholder for each credential; the value only goes to its hosts. Uses are kept for 90 days.",
            usedVerb = "Used",
            neverUsed = "Never used",
            usesLabel = "Uses",
            deleteText = "Requests with its placeholder stop working at once, and its uses are deleted. This cannot be undone.",
            mono = true,
            scope = model.credentialScope,
            onScope = { model.credentialScope = it },
            rowsOf = { id -> model.botCredentials[id]?.credentials?.map { SecretRow(it.name, it.hosts, it.lastUsedAt, it.lastError, it.github) } },
            asksOf = { id -> model.botCredentials[id]?.requests.orEmpty().map { SecretAsk(it.name, it.hosts, it.reason) } },
            failures = model.credentialLoadErrors,
            onAdd = { id, ask -> model.openCredentialForm(id, request = model.botCredentials[id]?.requests?.firstOrNull { it.name == ask.name }) },
            onDecline = model::declineCredentialRequest,
            onEdit = { id, name -> model.openCredentialForm(id, existing = model.botCredentials[id]?.credentials?.firstOrNull { it.name == name }) },
            onDelete = model::deleteCredential,
        ),
    )
}

@Composable
fun LoginsPanel(model: AppModel) {
    SecretPageView(
        model,
        SecretPage(
            kind = SecretKind.login,
            searchLabel = "Search logins",
            empty = "None yet. The bot asks for one when a desktop job needs to sign in.",
            noMatch = "No logins match your search.",
            note = "Desktop jobs fill a login only into https pages on its hosts, and never see its password. Fills are kept for 90 days.",
            usedVerb = "Filled",
            neverUsed = "Never filled",
            usesLabel = "Fills",
            deleteText = "Desktop jobs can no longer fill it, and its fills are deleted. This cannot be undone.",
            mono = false,
            scope = model.loginScope,
            onScope = { model.loginScope = it },
            rowsOf = { id -> model.botLogins[id]?.logins?.map { SecretRow(it.name, it.hosts, it.lastUsedAt, it.lastError) } },
            asksOf = { id -> model.botLogins[id]?.requests.orEmpty().map { SecretAsk(it.name, it.hosts, it.reason) } },
            failures = model.loginLoadErrors,
            onAdd = { id, ask -> model.openLoginForm(id, request = model.botLogins[id]?.requests?.firstOrNull { it.name == ask.name }) },
            onDecline = model::declineLoginRequest,
            onEdit = { id, name -> model.openLoginForm(id, existing = model.botLogins[id]?.logins?.firstOrNull { it.name == name }) },
            onDelete = model::deleteLogin,
        ),
    )
}

@Composable
private fun OverflowMenu(content: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { content { open = false } }
    }
}

@Composable
internal fun SecretOverflowMenu(content: @Composable (close: () -> Unit) -> Unit) = OverflowMenu(content)

@Composable
internal fun SecretNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SecretPageView(model: AppModel, page: SecretPage) {
    var search by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Pair<String, String>?>(null) }
    val needle = search.trim().lowercase()
    val bots = model.agents
    val inScope = bots.filter { page.scope == SKILLS_ALL || it.id == page.scope }
    val waiting = inScope.flatMap { bot ->
        page.asksOf(bot.id).filter { matchesSecret(it.name, it.hosts, needle) }.map { bot to it }
    }
    val shown = secretBotsShown(bots.map { it.id }, page.scope, needle, { page.rowsOf(it).orEmpty() }, { it in page.failures })

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text(page.searchLabel) },
                    shape = CircleShape,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    fun count(id: String) = page.rowsOf(id).orEmpty().size + page.asksOf(id).size
                    FilterChip(page.scope == SKILLS_ALL, { page.onScope(SKILLS_ALL) }, { Text("All · ${bots.sumOf { count(it.id) }}") })
                    for (bot in bots) {
                        FilterChip(
                            selected = page.scope == bot.id,
                            onClick = { page.onScope(bot.id) },
                            label = { Text("${bot.name} · ${count(bot.id)}") },
                            leadingIcon = { BotAvatarView(agent = bot, size = 18.dp) },
                        )
                    }
                }
            }
        }
        if (waiting.isNotEmpty()) {
            item {
                RowsSection(title = "Waiting for you") {
                    for ((bot, ask) in waiting) row { shape -> AskRow(model, page, bot, ask, shape) }
                }
            }
        }
        for (id in shown) {
            val bot = bots.first { it.id == id }
            item(key = id) { BotSecrets(model, page, bot, needle) { deleting = id to it } }
        }
        item { SecretNote(page.note) }
    }
    deleting?.let { (id, name) ->
        ConfirmDialog(
            title = "Delete $name?",
            text = page.deleteText,
            confirmLabel = "Delete",
            onConfirm = { page.onDelete(id, name) },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun AskRow(model: AppModel, page: SecretPage, bot: Agent, ask: SecretAsk, shape: Shape) {
    val busy = secretKey(page.kind.name, bot.id, ask.name) in model.pendingMutations
    GroupedItem(
        shape = shape,
        leading = { BotAvatarView(agent = bot, size = 44.dp) },
        below = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(enabled = !busy, onClick = { page.onDecline(bot.id, ask.name) }) { Text("Decline") }
                Button(enabled = !busy, onClick = { page.onAdd(bot.id, ask) }) { Text("Add") }
            }
        },
    ) {
        RowTitle("${bot.name} asks for ${ask.name}")
        RowSubtitle("${ask.reason} · ${ask.hosts.joinToString(", ")}", maxLines = 2)
    }
}

@Composable
private fun BotSecrets(model: AppModel, page: SecretPage, bot: Agent, needle: String, onDelete: (String) -> Unit) {
    val all = page.rowsOf(bot.id)
    val rows = all.orEmpty().filter { matchesSecret(it.name, it.hosts, needle) }
    val error = page.failures[bot.id]
    RowsSection(title = bot.name) {
        when {
            error != null -> note(error)
            all != null && rows.isEmpty() -> note(if (all.isNotEmpty()) page.noMatch else page.empty)
        }
        for (row in rows) row { shape -> SecretRowView(model, page, bot, row, shape) { onDelete(row.name) } }
    }
}

private fun agoLabel(iso: String): String {
    val at = taskRunDate(iso) ?: return iso
    return DateUtils.getRelativeTimeSpanString(at.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}

@Composable
private fun SecretRowView(model: AppModel, page: SecretPage, bot: Agent, row: SecretRow, shape: Shape, onDelete: () -> Unit) {
    val busy = secretKey(page.kind.name, bot.id, row.name) in model.pendingMutations
    GroupedItem(
        shape = shape,
        onClick = { page.onEdit(bot.id, row.name) },
        enabled = !busy && !row.managed,
        leading = { IconTile(routeIcon(if (page.kind == SecretKind.credential) Route.Credentials else Route.Logins), Tone.Lavender) },
        trailing = {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                OverflowMenu { close ->
                    DropdownMenuItem(text = { Text(page.usesLabel) }, onClick = {
                        close()
                        model.openSecretUses(page.kind, bot.id, row.name)
                    })
                    if (!row.managed) {
                        DropdownMenuItem(text = { Text("Edit") }, onClick = {
                            close()
                            page.onEdit(bot.id, row.name)
                        })
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                close()
                                onDelete()
                            },
                        )
                    }
                }
            }
        },
    ) {
        RowTitle(row.name, fontFamily = if (page.mono) FontFamily.Monospace else null)
        RowSubtitle(row.hosts.joinToString(", "), maxLines = 1)
        RowSubtitle(
            row.lastError ?: row.lastUsedAt?.let { "${page.usedVerb} ${agoLabel(it)}" } ?: page.neverUsed,
            color = if (row.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
fun SecretUsesDialog(model: AppModel, view: SecretUsesView) {
    val credential = view.kind == SecretKind.credential
    AlertDialog(
        onDismissRequest = { model.closeSecretUses() },
        title = { Text(if (credential) "Uses of ${view.name}" else "Fills of ${view.name}") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecretNote(
                    if (credential) "Requests sent with the value in the last 90 days, newest first."
                    else "Fields desktop jobs filled in the last 90 days, newest first.",
                )
                SheetErrorBanner(view.error)
                when {
                    view.loading -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    view.rows.isEmpty() && view.error.isEmpty() -> SecretNote(if (credential) "Not used yet." else "Not filled yet.")
                }
                for (use in view.rows) {
                    Column {
                        Text(activityTime(use.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(use.detail, style = MaterialTheme.typography.bodySmall, fontFamily = if (credential) FontFamily.Monospace else null)
                        use.note?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (use.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (view.hasMore) {
                    OutlinedButton(enabled = !view.loadingMore, onClick = { model.loadMoreSecretUses() }) { Text("Load More") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { model.closeSecretUses() }) { Text("Done") } },
    )
}

/** A secret's value, or a password manager reference once one is connected. */
@Composable
internal fun SecretSourceField(
    model: AppModel,
    label: String,
    draft: SecretDraft,
    onChange: (SecretDraft) -> Unit,
    valueDescription: String,
    valuePlaceholder: String? = null,
    concealed: Boolean = true,
    enabled: Boolean = true,
) {
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
            placeholder = (if (draft.manager != null) REFERENCE_PLACEHOLDERS[draft.manager] else valuePlaceholder)?.let { { Text(it) } },
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
                    modifier = Modifier.clickable { model.navigate(Route.PasswordManagers) },
                )
            }
        }
    }
}

@Composable
private fun OwnerField(model: AppModel, owner: String, onOwner: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("For bot", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
        BotPills(model.agents, { it.id == owner }, { bot, _ -> onOwner(bot.id) })
    }
}

@Composable
private fun SecretSheet(
    noun: String,
    existing: String?,
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
        title = { Text(if (existing != null) "Edit $existing" else "New $noun") },
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
                    Text(if (existing != null) "Save" else "Add")
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
    locked: Boolean,
    invalid: Boolean,
    enabled: Boolean,
    placeholder: String,
    nameNote: String,
    hostsPlaceholder: String,
    hostsNote: String,
    mono: Boolean,
    onName: (String) -> Unit,
    onHosts: (String) -> Unit,
) {
    OutlinedTextField(
        value = name,
        onValueChange = onName,
        label = { Text("Name") },
        placeholder = { Text(placeholder) },
        singleLine = true,
        enabled = !locked && enabled,
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
        existing = form.existing?.name,
        reason = form.request?.reason,
        error = form.error,
        ready = form.ready,
        saving = form.saving,
        onSubmit = { model.saveCredential() },
        onClose = { model.closeCredentialForm() },
    ) {
        if (form.botID == null) OwnerField(model, form.owner) { update(form.copy(owner = it)) }
        NameAndHosts(
            name = form.name,
            hosts = form.hosts,
            locked = form.locked,
            invalid = form.nameInvalid,
            enabled = !form.saving,
            placeholder = "ACME_TOKEN",
            nameNote = "The environment variable coding jobs see.",
            hostsPlaceholder = "api.example.com, *.example.com",
            hostsNote = if (form.request != null) {
                "The bot proposed these. The value goes only to them, so check they belong to the service."
            } else {
                "The value goes only to these hosts. *.example.com covers every subdomain."
            },
            mono = true,
            onName = { update(form.copy(name = it.uppercase())) },
            onHosts = { update(form.copy(hosts = it)) },
        )
        SecretSourceField(
            model = model,
            label = "Value",
            draft = form.secret,
            onChange = { update(form.copy(secret = it)) },
            valueDescription = "Stored encrypted and never shown again.",
            valuePlaceholder = keepPlaceholder(form.existing),
            enabled = !form.saving,
        )
    }
}

@Composable
fun LoginFormDialog(model: AppModel, form: LoginForm) {
    val update = { next: LoginForm -> if (model.loginForm != null) model.loginForm = next }
    val fields = form.existing?.fields
    SecretSheet(
        noun = "Login",
        existing = form.existing?.name,
        reason = form.request?.reason,
        error = form.error,
        ready = form.ready,
        saving = form.saving,
        onSubmit = { model.saveLogin() },
        onClose = { model.closeLoginForm() },
    ) {
        if (form.botID == null) OwnerField(model, form.owner) { update(form.copy(owner = it)) }
        NameAndHosts(
            name = form.name,
            hosts = form.hosts,
            locked = form.locked,
            invalid = form.nameInvalid,
            enabled = !form.saving,
            placeholder = "github",
            nameNote = "How desktop jobs refer to it.",
            hostsPlaceholder = "github.com, *.github.com",
            hostsNote = if (form.request != null) {
                "The bot proposed these. The login is filled only into https pages on them, so check they belong to the site."
            } else {
                "Filled only into https pages on these hosts. *.example.com covers every subdomain."
            },
            mono = false,
            onName = { update(form.copy(name = it.lowercase())) },
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
                valuePlaceholder = keepPlaceholder(fields?.username),
                concealed = false,
                enabled = !form.saving,
            )
            SecretSourceField(
                model = model,
                label = "Password",
                draft = form.password,
                onChange = { update(form.copy(password = it)) },
                valueDescription = "Stored encrypted and never shown again. Desktop jobs fill it without seeing it.",
                valuePlaceholder = keepPlaceholder(fields?.password),
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
                    valuePlaceholder = keepPlaceholder(fields?.one_time_code),
                    enabled = !form.saving,
                )
            }
        }
    }
}

@Composable
private fun ManagerRow(model: AppModel, manager: PasswordManager, shape: Shape) {
    val inUse = manager.usedBy.isNotEmpty()
    val busy = "password-manager:${manager.id}" in model.pendingMutations
    GroupedItem(
        shape = shape,
        leading = { LetterTile(manager.name) },
        trailing = {
            when {
                busy -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                manager.connected -> OverflowMenu { close ->
                    DropdownMenuItem(text = { Text("Replace token") }, onClick = {
                        close()
                        model.openManagerToken(manager)
                    })
                    DropdownMenuItem(
                        text = { Text(if (inUse) "Disconnect (in use)" else "Disconnect") },
                        enabled = !inUse,
                        onClick = {
                            close()
                            model.disconnectManager(manager)
                        },
                    )
                }
                else -> OutlinedButton(onClick = { model.openManagerToken(manager) }) { Text("Connect") }
            }
        },
    ) {
        RowTitle(manager.name + if (inUse) " (used by ${manager.usedBy.size})" else "")
    }
}

@Composable
fun PasswordManagersPanel(model: AppModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SecretNote("Credentials, provider API keys, and MCP server tokens can be read from a connected password manager instead of being stored in Crumble.")
        SheetErrorBanner(model.passwordManagersError)
        if (!model.passwordManagersLoaded && model.passwordManagersError.isEmpty()) SecretNote("Loading…")
        Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
            model.passwordManagers.forEachIndexed { index, manager ->
                ManagerRow(model, manager, groupedShape(index, model.passwordManagers.size))
            }
        }
    }
}

@Composable
fun ManagerTokenDialog(model: AppModel, form: ManagerTokenForm) {
    val manager = form.manager
    AlertDialog(
        onDismissRequest = { model.closeManagerToken() },
        title = { Text(if (manager.connected) "Replace ${manager.name} token" else "Connect ${manager.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SecretNote("Crumble signs in once to check the token and stores it encrypted. It only reads secrets you point it at.")
                SheetErrorBanner(form.error)
                OutlinedTextField(
                    value = form.token.text,
                    onValueChange = { text -> model.tokenForm?.let { model.tokenForm = it.copy(token = it.token.copy(text = text)) } },
                    label = { Text(TOKEN_LABELS[manager.id] ?: "Token") },
                    singleLine = true,
                    enabled = !form.saving,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = SECRET_KEYBOARD,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (manager.connected) SecretNote("Values read with the old token are dropped.")
            }
        },
        confirmButton = {
            Button(enabled = form.ready, onClick = { model.saveManagerToken() }) {
                if (form.saving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (manager.connected) "Save" else "Connect")
                }
            }
        },
        dismissButton = { TextButton(onClick = { model.closeManagerToken() }) { Text("Cancel") } },
    )
}
