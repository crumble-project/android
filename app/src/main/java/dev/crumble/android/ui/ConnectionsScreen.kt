package dev.crumble.android.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.graphics.Bitmap
import android.os.PersistableBundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.crumble.android.generated.GitHubAccount
import dev.crumble.android.generated.GitHubApp
import dev.crumble.android.model.AddGitHubAccountForm
import dev.crumble.android.generated.AdminUser
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.ChoiceOption
import dev.crumble.android.model.CreateGitHubAppForm
import dev.crumble.android.model.GatewayAliasRow
import dev.crumble.android.model.GatewayChannelRow
import dev.crumble.android.model.GatewayForm
import dev.crumble.android.model.GitHubAccountForm
import dev.crumble.android.model.GitHubAppForm
import dev.crumble.android.model.GitHubFlow
import dev.crumble.android.model.InviteSheet
import dev.crumble.android.model.MessageGateway
import dev.crumble.android.model.PluginRow
import dev.crumble.android.model.PluginSecretForm
import dev.crumble.android.model.Route
import dev.crumble.android.model.SecretDraft
import dev.crumble.android.model.ServerSettingsForm
import dev.crumble.android.model.WEB_SEARCH_PROVIDERS
import dev.crumble.android.model.WorkspaceField
import dev.crumble.android.model.gatewayBotOptions
import dev.crumble.android.model.gatewayDeliveries
import dev.crumble.android.model.gatewayErrors
import dev.crumble.android.model.gatewayStatus
import dev.crumble.android.model.gatewayStatusLabel
import dev.crumble.android.model.githubAuthorizedURL
import dev.crumble.android.model.isGitHubReturn
import dev.crumble.android.model.isWebLink
import dev.crumble.android.model.judgeOptions
import dev.crumble.android.model.judgeStatus
import dev.crumble.android.model.judgeValue
import dev.crumble.android.model.pluginRows
import dev.crumble.android.model.relayStatusLabel
import dev.crumble.android.model.toggled
import dev.crumble.android.model.visionOptions
import dev.crumble.android.model.visionStatus
import dev.crumble.android.model.visionValue
import dev.crumble.android.model.workspaceHelp
import dev.crumble.android.model.dateOnly
import dev.crumble.android.model.outdatedWorkspaceRows
import dev.crumble.android.model.userRowTitle
import dev.crumble.android.model.workspaceRowTitle
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Composable
private fun ConnectionPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
private fun ErrorLine(text: String) {
    if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun Spinner() = CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)

@Composable
private fun StatusBadge(text: String, error: Boolean = false) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    description: String = "",
    descriptionIsError: Boolean = false,
    badge: @Composable () -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                badge()
            }
            if (description.isNotEmpty()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (descriptionIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChoiceField(
    label: String,
    options: List<ChoiceOption>,
    value: String,
    enabled: Boolean = true,
    isError: Boolean = false,
    onChoose: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it && enabled }) {
        OutlinedTextField(
            value = options.firstOrNull { it.value == value }?.label.orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            isError = isError,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in options) {
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onChoose(option.value)
                    },
                )
            }
        }
    }
}

@Composable
private fun ConfirmDelete(title: String, description: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(description) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm()
                },
            ) {
                Text(action, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ClipDescription.EXTRA_IS_SENSITIVE as a literal: it arrived in API 33, and older versions read the same key.
private const val CLIP_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

/** Keeps the value out of clipboard previews and sync. */
private fun sensitiveClip(label: String, value: String): ClipEntry {
    val clip = ClipData.newPlainText(label, value)
    clip.description.extras = PersistableBundle().apply { putBoolean(CLIP_IS_SENSITIVE, true) }
    return ClipEntry(clip)
}

@Composable
internal fun CopyField(label: String, value: String, sensitive: Boolean = false) {
    val clipboard = LocalClipboardManager.current
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        singleLine = true,
        label = { Text(label) },
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        trailingIcon = {
            IconButton(
                onClick = {
                    if (sensitive) clipboard.setClip(sensitiveClip(label, value))
                    else clipboard.setText(AnnotatedString(value))
                },
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SimpleSheet(
    title: String,
    description: String = "",
    error: String = "",
    submitLabel: String,
    ready: Boolean,
    saving: Boolean,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (description.isNotEmpty()) SecretNote(description)
                SheetErrorBanner(error)
                content()
            }
        },
        confirmButton = {
            Button(enabled = ready && !saving, onClick = onSubmit) {
                if (saving) Spinner() else Text(submitLabel)
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

// MARK: Plugin settings

@Composable
private fun PluginRows(model: AppModel, contributes: String? = null, pluginID: String? = null, secretKey: String? = null) {
    ErrorLine(model.pluginSettings.error)
    for (row in pluginRows(model.pluginSettings.value, contributes, pluginID, secretKey)) {
        PluginRowView(model, row)
    }
}

@Composable
private fun PluginRowView(model: AppModel, row: PluginRow) {
    val secret = row.setting.secret
    val manager = model.passwordManagers.firstOrNull { it.id == secret.passwordManager }
    val busy = "plugin:${row.pluginID}:${row.setting.key}" in model.pendingMutations
    SettingRow(title = row.setting.label + (manager?.let { " (${it.name})" } ?: "")) {
        when {
            busy -> Spinner()
            secret.configured -> SecretOverflowMenu { close ->
                DropdownMenuItem(text = { Text("Edit") }, onClick = {
                    close()
                    model.openPluginSetting(row)
                })
                DropdownMenuItem(text = { Text("Remove") }, onClick = {
                    close()
                    model.removePluginSetting(row)
                })
            }
            else -> OutlinedButton(onClick = { model.openPluginSetting(row) }) { Text("Add") }
        }
    }
}

@Composable
private fun PluginSecretDialog(model: AppModel, form: PluginSecretForm) {
    SimpleSheet(
        title = form.setting.label,
        error = form.error,
        submitLabel = "Save",
        ready = form.secret.changed,
        saving = form.saving,
        onSubmit = { model.savePluginSetting() },
        onClose = { model.closePluginSetting() },
    ) {
        SecretSourceField(
            model = model,
            label = "Value",
            draft = form.secret,
            onChange = { next -> model.pluginForm = model.pluginForm?.copy(secret = next) },
            valueDescription = "Stored encrypted and never shown again.",
        )
    }
}

// MARK: Web search, judge and vision models

@Composable
fun WebSearchPanel(model: AppModel) {
    ConnectionPage {
        SectionCard(title = "Search provider", footer = "Bots use this unless their own settings choose another provider.") {
            ErrorLine(model.webSearch.error)
            model.webSearch.value?.let { data ->
                val busy = "web-search:default" in model.pendingMutations
                val description = if (data.defaultProvider == "exa" && !data.exaKeyConfigured) {
                    "Exa needs a key before bots can search with it."
                } else {
                    model.webSearch.failure
                }
                SettingRow(title = "Default", description = description, descriptionIsError = model.webSearch.failure == description && description.isNotEmpty()) {
                    for (provider in WEB_SEARCH_PROVIDERS) {
                        FilterChip(
                            selected = data.defaultProvider == provider.value,
                            enabled = !busy,
                            onClick = { model.chooseWebSearchProvider(provider.value) },
                            label = { Text(provider.label) },
                        )
                    }
                }
                PluginRows(model, contributes = "webSearchProviders")
            }
        }
    }
}

@Composable
private fun ModelPage(
    section: String,
    footer: String,
    title: String,
    readError: String,
    failure: String,
    status: String?,
    options: List<ChoiceOption>,
    value: String?,
    busy: Boolean,
    onChoose: (String) -> Unit,
) {
    ConnectionPage {
        SectionCard(title = section, footer = footer) {
            ErrorLine(readError)
            if (value != null && status != null) {
                ChoiceField(title, options, value, enabled = !busy, onChoose = onChoose)
                if (failure.isNotEmpty()) ErrorLine(failure) else SecretNote(status)
            }
        }
    }
}

@Composable
fun JudgeModelPanel(model: AppModel) {
    val data = model.judgeModel.value
    ModelPage(
        section = "Intent checks",
        footer = "Before a desktop job clicks, types, or presses a key, this model checks whether the action is irreversible and whether you asked for it. Actions it can't clear wait for your approval in the bot's chat.",
        title = "Judge model",
        readError = model.judgeModel.error,
        failure = model.judgeModel.failure,
        status = data?.let(::judgeStatus),
        options = data?.let(::judgeOptions).orEmpty(),
        value = data?.let(::judgeValue),
        busy = "judge:model" in model.pendingMutations,
        onChoose = model::chooseJudgeModel,
    )
}

@Composable
fun VisionModelPanel(model: AppModel) {
    val data = model.visionModel.value
    ModelPage(
        section = "Images for text-only models",
        footer = "When a bot's model can't read images, this model describes each image it gets, including any visible text, and the bot works from the description. The bot can also ask it questions about an image. Its tokens count toward the conversation's usage.",
        title = "Vision model",
        readError = model.visionModel.error,
        failure = model.visionModel.failure,
        status = data?.let(::visionStatus),
        options = data?.let(::visionOptions).orEmpty(),
        value = data?.let(::visionValue),
        busy = "vision:model" in model.pendingMutations,
        onChoose = model::chooseVisionModel,
    )
}

// MARK: Event relay

@Composable
fun EventRelayPanel(model: AppModel) {
    ConnectionPage {
        SectionCard(
            title = "Relay",
            footer = "A Doorstep relay receives webhooks for this account and forwards them to this server, so the server needs no public address.",
        ) {
            ErrorLine(model.eventRelay.error)
            model.eventRelay.value?.let { relay ->
                val next = model.relayDraft.trim().ifEmpty { null }
                SettingRow(title = "Relay URL", badge = { StatusBadge(relayStatusLabel(relay.status), relay.status == "error") })
                OutlinedTextField(
                    value = model.relayDraft,
                    onValueChange = { model.relayDraft = it },
                    placeholder = { Text("https://relay.example.com") },
                    singleLine = true,
                    enabled = !model.relaySaving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { model.saveRelay() }, enabled = !model.relaySaving && next != relay.url) { Text("Save") }
                    if (model.relaySaving) Spinner()
                }
                ErrorLine(model.eventRelay.failure)
                relay.error?.let {
                    SettingRow(title = "Relay needs attention", description = it, descriptionIsError = true)
                }
                PluginRows(model, pluginID = "doorstep")
            }
        }
        SectionCard(
            title = "Event sources",
            footer = "Each source is one webhook address. Automations that use a source run when an event arrives there.",
        ) {
            ErrorLine(model.eventSourcesError)
            if (model.eventSourcesLoaded && model.eventSources.isEmpty()) SecretNote("No event sources yet.")
            for (source in model.eventSources) EventSourceRow(model, source)
            TextButton(onClick = { model.openEventSourceForm() }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Add source")
            }
        }
    }
}

// MARK: Message gateways

@Composable
private fun GatewayBadge(gateway: MessageGateway) {
    StatusBadge(gatewayStatusLabel(gateway), gateway.config.enabled && gateway.status == "error")
}

@Composable
fun MessageGatewaysPanel(model: AppModel) {
    ConnectionPage {
        ErrorLine(model.gateways.error)
        model.gateways.value?.let { list ->
            SectionCard(title = "Gateways", footer = "Message your bots from other chat apps.") {
                if (list.isEmpty()) SecretNote("This server has no message gateways.")
                for (gateway in list) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { model.navigate(Route.MessageGateway(gateway.id)) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(gateway.label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                        GatewayBadge(gateway)
                        Box(modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                }
            }
        }
    }
}

@Composable
fun MessageGatewayPanel(model: AppModel, id: String) {
    BackHandler { model.navigate(Route.MessageGateways) }
    val gateway = model.gateways.value?.firstOrNull { it.id == id }
    val form = model.gatewayForm?.takeIf { it.gatewayID == id }
    ConnectionPage {
        ErrorLine(model.gateways.error)
        if (model.gateways.value != null && gateway == null) {
            SectionCard(title = "Not found") { SecretNote("This server has no gateway $id.") }
        }
        if (gateway != null && form != null) GatewayEditor(model, gateway, form)
    }
}

@Composable
private fun GatewayEditor(model: AppModel, gateway: MessageGateway, form: GatewayForm) {
    val config = form.config()
    val dirty = config != gateway.config
    val errors = if (form.attempted) gatewayErrors(config, model.agents.map { it.id }.toSet()) else emptyMap()
    val edit: ((GatewayForm) -> GatewayForm) -> Unit = { change ->
        model.gatewayForm?.let { model.gatewayForm = change(it).copy(saved = false, error = "") }
    }

    SectionCard(title = "Connection") {
        val deliveries = gatewayDeliveries(gateway)
        SettingRow(
            title = gateway.label + if (deliveries.isEmpty()) "" else " ($deliveries)",
            badge = { GatewayBadge(gateway) },
        ) {
            Switch(checked = form.enabled, onCheckedChange = { on -> edit { it.copy(enabled = on) } })
        }
        gateway.error?.let { SettingRow(title = "Connection needs attention", description = it, descriptionIsError = true) }
        PluginRows(model, contributes = "chatAdapters", secretKey = gateway.secretKey)
    }

    SectionCard(title = "Bots", footer = "Start a message with @alias to pick a bot.") {
        ChoiceField(
            label = "Default bot",
            options = gatewayBotOptions(model.agents, form.defaultBotId, optional = true),
            value = form.defaultBotId.orEmpty(),
            isError = "defaultBotId" in errors,
            onChoose = { value -> edit { it.copy(defaultBotId = value.ifEmpty { null }) } },
        )
        ErrorLine(errors["defaultBotId"].orEmpty())
        form.aliases.forEachIndexed { index, alias -> AliasRow(model, form, alias, index, "aliases" in errors, edit) }
        ErrorLine(errors["aliases"].orEmpty())
        TextButton(enabled = model.agents.isNotEmpty(), onClick = { edit { it.copy(aliases = it.aliases + GatewayAliasRow()) } }) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Alias")
        }
    }

    SectionCard(title = "Access") {
        OutlinedTextField(
            value = form.owner,
            onValueChange = { text -> edit { it.copy(owner = text.take(200)) } },
            label = { Text("Owner ${gateway.label} user ID") },
            isError = "ownerId" in errors,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorLine(errors["ownerId"].orEmpty())
        SettingRow(title = "Direct messages") {
            Switch(checked = form.allowDirectMessages, onCheckedChange = { on -> edit { it.copy(allowDirectMessages = on) } })
        }
        form.channels.forEachIndexed { index, channel -> ChannelRow(channel, index, edit) }
        TextButton(onClick = { edit { it.copy(channels = it.channels + GatewayChannelRow()) } }) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Channel")
        }
    }

    ErrorLine(form.error)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            gatewayStatus(dirty, form),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(enabled = dirty && !form.saving, onClick = { model.resetGatewayForm() }) { Text("Reset") }
        Button(enabled = dirty && !form.saving, onClick = { model.saveGateway() }) { Text("Save") }
    }
}

@Composable
private fun AliasRow(
    model: AppModel,
    form: GatewayForm,
    alias: GatewayAliasRow,
    index: Int,
    invalid: Boolean,
    edit: ((GatewayForm) -> GatewayForm) -> Unit,
) {
    val update = { change: (GatewayAliasRow) -> GatewayAliasRow ->
        edit { current -> current.copy(aliases = current.aliases.map { if (it.id == alias.id) change(it) else it }) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = alias.handle,
                onValueChange = { text -> update { it.copy(handle = text.take(100)) } },
                label = { Text("Alias ${index + 1}") },
                placeholder = { Text("research") },
                isError = invalid,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { edit { current -> current.copy(aliases = current.aliases.filterNot { it.id == alias.id }) } }) {
                Icon(Icons.Default.Delete, contentDescription = "Remove alias")
            }
        }
        ChoiceField(
            label = "Bot for alias ${index + 1}",
            options = gatewayBotOptions(model.agents, alias.botId, optional = false),
            value = alias.botId,
            isError = invalid,
            onChoose = { value -> update { it.copy(botId = value) } },
        )
    }
}

@Composable
private fun ChannelRow(channel: GatewayChannelRow, index: Int, edit: ((GatewayForm) -> GatewayForm) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = channel.value,
            onValueChange = { text ->
                edit { current -> current.copy(channels = current.channels.map { if (it.id == channel.id) it.copy(value = text.take(200)) else it }) }
            },
            label = { Text("Channel ID ${index + 1}") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { edit { current -> current.copy(channels = current.channels.filterNot { it.id == channel.id }) } }) {
            Icon(Icons.Default.Delete, contentDescription = "Remove channel")
        }
    }
}

// MARK: Coding

@Composable
fun CodingPanel(model: AppModel) {
    ConnectionPage {
        WorkspaceSection(model)
        AppsSection(model)
        AccountsSection(model)
    }
}

@Composable
private fun WorkspaceSection(model: AppModel) {
    val capabilities = model.capabilities
    val missing = capabilities?.let { workspaceHelp(it.coding, it.desktop) }.orEmpty()
    val admin = model.isAdmin == true
    if (!admin && missing.isEmpty()) return
    SectionCard(title = "Workspaces") {
        for (note in missing) {
            Text(note, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        }
        ErrorLine(model.serverSettings.error)
        val settings = model.serverSettings.value
        if (admin && settings != null) {
            val busy = "server-settings:patch" in model.pendingMutations
            for (field in WorkspaceField.entries) {
                SettingRow(title = workspaceRowTitle(field, settings)) {
                    OutlinedButton(enabled = !busy, onClick = { model.openServerSettings(field) }) { Text("Edit") }
                }
            }
        }
        if (admin) OutdatedWorkspaces(model)
    }
}

@Composable
private fun OutdatedWorkspaces(model: AppModel) {
    ErrorLine(model.outdatedWorkspaces.error)
    val rows = outdatedWorkspaceRows(model.outdatedWorkspaces.value.orEmpty(), model.agents, model.adminUsers.value.orEmpty())
    if (rows.isEmpty()) return
    SecretNote("Running on an older image. Each workspace switches at its next idle stop, keeping its files.")
    for (row in rows) {
        SettingRow(title = row.bot, description = row.owner)
    }
}

// MARK: Users

@Composable
fun UsersPanel(model: AppModel) {
    ConnectionPage {
        when (model.isAdmin) {
            true -> {
                UsersSection(model)
                SignUpSection(model)
            }
            false -> SectionCard(title = "Users") { SecretNote("Only admins manage users.") }
            null -> SectionCard(title = "Users") {
                ErrorLine(model.adminUsers.error)
                if (model.adminUsers.error.isEmpty()) Spinner()
            }
        }
    }
}

@Composable
private fun UsersSection(model: AppModel) {
    SectionCard(title = "Users") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { model.createInvite() }) { Text("Invite") }
            ErrorLine(model.adminUsers.error)
            ErrorLine(model.adminUsers.failure)
            ErrorLine(model.invites.error)
            ErrorLine(model.invites.failure)
            if (model.adminUsers.value == null && model.adminUsers.error.isEmpty()) Spinner()
            for (user in model.adminUsers.value.orEmpty()) {
                UserRow(model, user)
            }
            for (invite in model.invites.value.orEmpty()) {
                SettingRow(title = "Invite from ${dateOnly(invite.createdAt)}") {
                    OutlinedButton(onClick = { model.revokeInvite(invite) }) { Text("Revoke") }
                }
            }
        }
    }
}

@Composable
private fun UserRow(model: AppModel, user: AdminUser) {
    var removing by remember { mutableStateOf(false) }
    val busy = "admin-user:${user.id}" in model.pendingMutations
    SettingRow(title = userRowTitle(user)) {
        when {
            busy -> Spinner()
            model.accountID != null && user.id != model.accountID -> SecretOverflowMenu { close ->
                val admin = user.role == "admin"
                DropdownMenuItem(text = { Text(if (admin) "Make regular user" else "Make admin") }, onClick = {
                    close()
                    model.setUserRole(user, if (admin) "user" else "admin")
                })
                DropdownMenuItem(
                    text = { Text("Remove account and data", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        close()
                        removing = true
                    },
                )
            }
        }
    }
    if (removing) {
        ConfirmDelete(
            title = "Remove account and data",
            description = "This removes ${user.name.ifEmpty { user.email }}, their bots, and all their data. It can't be undone.",
            action = "Remove",
            onConfirm = { model.removeUser(user) },
            onDismiss = { removing = false },
        )
    }
}

@Composable
private fun SignUpSection(model: AppModel) {
    SectionCard(title = "Sign-up") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ErrorLine(model.serverSettings.error)
            ErrorLine(model.serverSettings.failure)
            SettingRow(title = "Open sign-up") {
                val settings = model.serverSettings.value
                if (settings == null) {
                    if (model.serverSettings.error.isEmpty()) Spinner()
                } else {
                    Switch(
                        checked = settings.signUpOpen,
                        enabled = "server-settings:patch" !in model.pendingMutations,
                        onCheckedChange = { model.setSignUpOpen(it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun InviteDialog(model: AppModel, sheet: InviteSheet) {
    val failed = sheet.error.isNotEmpty()
    SimpleSheet(
        title = "Invite link",
        description = "Anyone with this link can create one account. It's shown only now.",
        error = sheet.error,
        submitLabel = if (failed) "Try again" else "Done",
        ready = true,
        saving = sheet.url == null && !failed,
        onSubmit = { if (failed) model.createInvite() else model.closeInviteSheet() },
        onClose = { model.closeInviteSheet() },
    ) {
        sheet.url?.let { CopyField("Invite link", it, sensitive = true) }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ServerSettingsDialog(model: AppModel, form: ServerSettingsForm) {
    val edit = { change: (ServerSettingsForm) -> ServerSettingsForm -> model.serverSettingsForm?.let { model.serverSettingsForm = change(it) } }
    SimpleSheet(
        title = form.field.title,
        error = form.error,
        submitLabel = "Save",
        ready = form.patch() != null,
        saving = false,
        onSubmit = { model.saveServerSettings() },
        onClose = { model.serverSettingsForm = null },
    ) {
        when (form.field) {
            WorkspaceField.size -> {
                NumberField("CPUs", form.cpus) { text -> edit { it.copy(cpus = text) } }
                NumberField("Memory (GB)", form.memory) { text -> edit { it.copy(memory = text) } }
            }
            WorkspaceField.image -> OutlinedTextField(
                value = form.image,
                onValueChange = { text -> edit { it.copy(image = text) } },
                label = { Text("Image") },
                placeholder = { Text("ghcr.io/owner/image:tag") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            WorkspaceField.grace -> NumberField("Minutes", form.grace) { text -> edit { it.copy(grace = text) } }
        }
    }
}

@Composable
private fun AppsSection(model: AppModel) {
    SectionCard(
        title = "GitHub Apps",
        footer = "Each App is a GitHub identity in one account. Bots assigned to it push, commit, and open pull requests as it there.",
    ) {
        ErrorLine(model.githubApps.error)
        val apps = model.githubApps.value
        if (apps?.isEmpty() == true) SecretNote("No GitHub Apps yet.")
        apps?.forEach { AppRow(model, it) }
        TextButton(onClick = { model.openCreateApp() }) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("New GitHub App")
        }
    }
}

@Composable
private fun AppRow(model: AppModel, app: GitHubApp) {
    var confirming by remember { mutableStateOf(false) }
    val busy = "github-app:${app.id}" in model.pendingMutations
    val detail = (app.user?.let { "as $it, " } ?: "") + "used by ${app.botIds.size}"
    SettingRow(title = "${app.login} ($detail)") {
        if (!app.installed) {
            OutlinedButton(onClick = { model.installGitHubApp(app) }) { Text("Install") }
        }
        IconButton(onClick = { model.openGitHubApp(app) }) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
        if (busy) {
            Spinner()
        } else {
            IconButton(onClick = { confirming = true }) { Icon(Icons.Default.Delete, contentDescription = "Remove") }
        }
    }
    if (confirming) {
        ConfirmDelete(
            title = "Remove ${app.login}?",
            description = "Its bots lose access to GitHub through it. The App stays on GitHub; delete it in its settings there.",
            action = "Remove",
            onConfirm = { model.deleteGitHubApp(app) },
            onDismiss = { confirming = false },
        )
    }
}

@Composable
private fun CreateAppDialog(model: AppModel, form: CreateGitHubAppForm) {
    SimpleSheet(
        title = "New GitHub App",
        description = "GitHub opens to create a private App on one account. Bots you assign to it commit and open pull requests as it in that account's repositories.",
        error = form.error,
        submitLabel = "Create on GitHub",
        ready = form.ready,
        saving = form.saving,
        onSubmit = { model.createGitHubApp() },
        onClose = { model.closeCreateApp() },
    ) {
        OutlinedTextField(
            value = form.name,
            onValueChange = { text -> model.createAppForm = model.createAppForm?.copy(name = text.take(34)) },
            label = { Text("Name") },
            placeholder = { Text("frien-bot") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SecretNote("Unique across GitHub. Commits show it as name[bot].")
        OutlinedTextField(
            value = form.organization,
            onValueChange = { text -> model.createAppForm = model.createAppForm?.copy(organization = text) },
            label = { Text("Organization") },
            placeholder = { Text("Your account") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SecretNote("The App can only work in its owner's repositories. Create one App per account.")
    }
}

@Composable
private fun BotsField(model: AppModel, botIDs: List<String>, description: String, onChange: (List<String>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Bots", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
        SecretNote(description)
        BotPills(model.agents, { it.id in botIDs }, { bot, on -> onChange(toggled(botIDs, bot.id, on)) })
    }
}

@Composable
private fun GitHubLink(url: String) {
    val uriHandler = LocalUriHandler.current
    TextButton(onClick = { if (isWebLink(url)) runCatching { uriHandler.openUri(url) } }) { Text("Open on GitHub") }
}

@Composable
private fun AppDialog(model: AppModel, form: GitHubAppForm, app: GitHubApp) {
    SimpleSheet(
        title = app.login,
        error = form.error,
        submitLabel = "Save",
        ready = true,
        saving = false,
        onSubmit = { model.saveGitHubAppBots() },
        onClose = { model.closeGitHubApp() },
    ) {
        GitHubLink(app.htmlUrl)
        InstallationField(model, form, app)
        ActAsField(model, form, app)
        BotsField(
            model,
            form.botIDs,
            "They get GH_TOKEN once the App is installed. A bot uses one App per GitHub account; its first App is its default for commits and gh pr.",
        ) { next -> model.githubAppForm = model.githubAppForm?.copy(botIDs = next) }
    }
}

@Composable
private fun InstallationField(model: AppModel, form: GitHubAppForm, app: GitHubApp) {
    Text("Installation", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
    Text(
        if (app.installed) "Installed on ${app.account}" else "Not installed" + (app.account?.let { " on $it" } ?: ""),
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!app.installed) OutlinedButton(onClick = { model.installGitHubApp(app) }) { Text("Install") }
        OutlinedButton(enabled = !form.checking, onClick = { model.checkGitHubApp(app.id) }) {
            if (form.checking) Spinner() else Text("Check")
        }
    }
    ErrorLine(form.checkError)
}

/** Whether the App's bots act as its `slug[bot]` user or as the owner, signed in through the App. */
@Composable
private fun ActAsField(model: AppModel, form: GitHubAppForm, app: GitHubApp) {
    Text("Bots act as", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
    val user = app.user
    if (user != null) {
        Text("$user, marked as ${app.login}", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(enabled = !form.signingOut, onClick = { model.signOutOfGitHubApp(app.id) }) {
            if (form.signingOut) Spinner() else Text("Sign out")
        }
        SecretNote("GitHub shows their pull requests and comments with the App's badge, and their commits list ${app.login} as co-author.")
    } else {
        Text(app.login, style = MaterialTheme.typography.bodyMedium)
        if (app.clientSecret.configured) {
            OutlinedButton(enabled = !form.signingIn, onClick = { model.signInToGitHubApp(app) }) {
                if (form.signingIn) Spinner() else Text("Sign in as me")
            }
        }
        model.client?.baseURL?.let { CopyField("Callback URL", githubAuthorizedURL(it)) }
        if (!app.clientSecret.configured) ClientSecretFields(model, form)
    }
    ErrorLine(form.signInError)
}

@Composable
private fun ClientSecretFields(model: AppModel, form: GitHubAppForm) {
    SecretSourceField(
        model = model,
        label = "Client secret",
        draft = form.clientSecret,
        onChange = { next -> model.githubAppForm = model.githubAppForm?.copy(clientSecret = next) },
        valueDescription = "To let bots act as you, open the App's settings on GitHub, add the callback URL above, and generate a client secret.",
        enabled = !form.savingSecret,
    )
    OutlinedButton(enabled = form.clientSecret.changed && !form.savingSecret, onClick = { model.saveGitHubClientSecret() }) {
        if (form.savingSecret) Spinner() else Text("Save")
    }
    ErrorLine(form.secretError)
}

@Composable
private fun AccountsSection(model: AppModel) {
    SectionCard(
        title = "Machine accounts",
        footer = "GitHub users for your bots to act as wherever none of their Apps does.",
    ) {
        ErrorLine(model.githubAccounts.error)
        val accounts = model.githubAccounts.value
        if (accounts?.isEmpty() == true) SecretNote("No machine accounts yet.")
        accounts?.forEach { AccountRow(model, it) }
        TextButton(onClick = { model.openAddAccount() }) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("New machine account")
        }
    }
}

@Composable
private fun AccountRow(model: AppModel, account: GitHubAccount) {
    var confirming by remember { mutableStateOf(false) }
    val busy = "github-account:${account.id}" in model.pendingMutations
    SettingRow(title = "${account.login} (used by ${account.botIds.size})") {
        IconButton(onClick = { model.openGitHubAccount(account) }) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
        if (busy) {
            Spinner()
        } else {
            IconButton(onClick = { confirming = true }) { Icon(Icons.Default.Delete, contentDescription = "Remove") }
        }
    }
    if (confirming) {
        ConfirmDelete(
            title = "Remove ${account.login}?",
            description = "Crumble forgets its token and its bots stop acting as it. The user stays on GitHub; revoke the token there too.",
            action = "Remove",
            onConfirm = { model.deleteGitHubAccount(account) },
            onDismiss = { confirming = false },
        )
    }
}

@Composable
private fun TokenField(model: AppModel, label: String, draft: SecretDraft, enabled: Boolean, onChange: (SecretDraft) -> Unit) {
    val uriHandler = LocalUriHandler.current
    SecretSourceField(
        model = model,
        label = label,
        draft = draft,
        onChange = onChange,
        valuePlaceholder = "github_pat_…",
        valueDescription = "Signed in as the machine account on GitHub, create a personal access token with access to the repositories its bots work in.",
        enabled = enabled,
    )
    TextButton(onClick = { runCatching { uriHandler.openUri("https://github.com/settings/personal-access-tokens/new") } }) { Text("New token") }
}

@Composable
private fun AddAccountDialog(model: AppModel, form: AddGitHubAccountForm) {
    SimpleSheet(
        title = "New machine account",
        description = "A GitHub user you made for your bots. They act as it wherever none of their Apps does, such as other people's repositories.",
        error = form.error,
        submitLabel = "Add",
        ready = form.ready,
        saving = form.saving,
        onSubmit = { model.addGitHubAccount() },
        onClose = { model.closeAddAccount() },
    ) {
        TokenField(model, "Token", form.token, !form.saving) { next -> model.addAccountForm = model.addAccountForm?.copy(token = next) }
    }
}

@Composable
private fun AccountDialog(model: AppModel, form: GitHubAccountForm, account: GitHubAccount) {
    val uriHandler = LocalUriHandler.current
    SimpleSheet(
        title = account.login,
        error = form.error,
        submitLabel = "Save",
        ready = true,
        saving = form.saving,
        onSubmit = { model.saveGitHubAccount() },
        onClose = { model.closeGitHubAccount() },
    ) {
        GitHubLink(account.htmlUrl)
        BotsField(
            model,
            form.botIDs,
            "They get GH_TOKEN and act as this user wherever none of their Apps does; their commits default to it. A bot acts as one machine account.",
        ) { next -> model.githubAccountForm = model.githubAccountForm?.copy(botIDs = next) }
        TokenField(model, if (account.token.passwordManager != null) "Token" else "Replace token", form.token, !form.saving) { next ->
            model.githubAccountForm = model.githubAccountForm?.copy(token = next)
        }
        CopyField("Signing key", account.signingKey)
        SecretNote("Commits its bots make are signed with this key. Add it to ${account.login} on GitHub as a signing key so they show as verified.")
        TextButton(onClick = { runCatching { uriHandler.openUri("https://github.com/settings/ssh/new") } }) { Text("Add key") }
    }
}

// MARK: GitHub pages

/**
 * GitHub creates Apps only from a form post, which a browser intent can't send, and each page returns to the website,
 * where this app's session isn't. An embedded page can do the first and catch the second.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GitHubFlowDialog(model: AppModel, flow: GitHubFlow) {
    Dialog(
        onDismissRequest = { model.closeGitHubFlow() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("GitHub") },
                    navigationIcon = {
                        IconButton(onClick = { model.closeGitHubFlow() }) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    flow.error.isNotEmpty() -> Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ErrorLine(flow.error)
                        OutlinedButton(onClick = { model.closeGitHubFlow() }) { Text("Back to Coding") }
                    }
                    flow.finishing -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Spinner()
                        Text("Finishing GitHub setup…", style = MaterialTheme.typography.bodyMedium)
                    }
                    else -> GitHubPage(flow, model::finishGitHubFlow)
                }
            }
        }
    }
}

// GitHub's pages need scripts. Nothing is exposed to them: there are no JavaScript interfaces and no file access.
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GitHubPage(flow: GitHubFlow, onReturn: (String) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        if (!request.isForMainFrame) return false
                        if (isGitHubReturn(url, flow.step)) {
                            onReturn(url)
                            return true
                        }
                        return !isWebLink(url)
                    }

                    // The redirect out of GitHub's App form follows a POST, which skips the check above.
                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        if (isGitHubReturn(url, flow.step)) {
                            view.stopLoading()
                            onReturn(url)
                        }
                    }
                }
                val manifest = flow.manifest
                if (manifest != null) {
                    postUrl(flow.url, "manifest=${URLEncoder.encode(manifest, StandardCharsets.UTF_8.name())}".toByteArray())
                } else {
                    loadUrl(flow.url)
                }
            }
        },
        onRelease = { it.destroy() },
    )
}

/** The sheets for connections, hosted once at the app's root. */
@Composable
fun ConnectionDialogs(model: AppModel) {
    model.pluginForm?.let { PluginSecretDialog(model, it) }
    model.createAppForm?.let { CreateAppDialog(model, it) }
    model.githubAppForm?.let { form ->
        model.githubApps.value?.firstOrNull { it.id == form.appID }?.let { AppDialog(model, form, it) }
    }
    model.addAccountForm?.let { AddAccountDialog(model, it) }
    model.githubAccountForm?.let { form ->
        model.githubAccounts.value?.firstOrNull { it.id == form.accountID }?.let { AccountDialog(model, form, it) }
    }
    model.serverSettingsForm?.let { ServerSettingsDialog(model, it) }
    model.inviteSheet?.let { InviteDialog(model, it) }
    model.githubFlow?.let { GitHubFlowDialog(model, it) }
}
