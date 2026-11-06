package dev.crumble.android.model

import dev.crumble.android.generated.GetEventRelayResponse
import dev.crumble.android.generated.GetWebSearchResponse
import dev.crumble.android.generated.JudgeChoice
import dev.crumble.android.generated.JudgeModel
import dev.crumble.android.generated.JudgeModelJudgeModel
import dev.crumble.android.generated.MessageGatewaysResponseGatewaysItem
import dev.crumble.android.generated.OptionalField
import dev.crumble.android.generated.PluginSetting
import dev.crumble.android.generated.PluginSettings
import dev.crumble.android.generated.ServerSettings
import dev.crumble.android.generated.UpdateEventRelayResponse
import dev.crumble.android.generated.UpdateMessageGatewayRequest
import dev.crumble.android.generated.UpdateMessageGatewayRequestAliasesItem
import dev.crumble.android.generated.UpdateMessageGatewayResponse
import dev.crumble.android.generated.UpdateServerSettingsRequest
import dev.crumble.android.generated.UpdateWebSearchResponse
import dev.crumble.android.generated.VisionModel
import java.net.URI
import java.text.Normalizer
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** A page's data: `error` is a failed read, `failure` a failed change to it. */
data class Remote<T>(val value: T? = null, val error: String = "", val failure: String = "")

data class ChoiceOption(val value: String, val label: String)

// MARK: Relay and web search

data class RelayStatus(val url: String?, val tokenConfigured: Boolean, val status: String, val error: String?)

internal fun GetEventRelayResponse.domain() = RelayStatus(url, tokenConfigured, status, error)
internal fun UpdateEventRelayResponse.domain() = RelayStatus(url, tokenConfigured, status, error)

private val RELAY_LABELS = mapOf(
    "disconnected" to "Not set up",
    "connecting" to "Connecting",
    "connected" to "Connected",
    "error" to "Connection error",
)

fun relayStatusLabel(status: String) = RELAY_LABELS[status] ?: status

data class WebSearchSettings(val defaultProvider: String, val exaKeyConfigured: Boolean)

internal fun GetWebSearchResponse.domain() = WebSearchSettings(defaultProvider, exaKeyConfigured)
internal fun UpdateWebSearchResponse.domain() = WebSearchSettings(defaultProvider, exaKeyConfigured)

val WEB_SEARCH_PROVIDERS = listOf(ChoiceOption("mixed", "Mixed"), ChoiceOption("exa", "Exa"))

// MARK: Plugin settings

data class PluginRow(val pluginID: String, val setting: PluginSetting)

/** The settings a page shows, as the server declares them. `secretKey` is `pluginId:key`. */
fun pluginRows(
    settings: PluginSettings?,
    contributes: String? = null,
    pluginID: String? = null,
    secretKey: String? = null,
): List<PluginRow> = settings?.plugins.orEmpty()
    .filter { (contributes == null || contributes in it.contributes) && (pluginID == null || it.id == pluginID) }
    .flatMap { plugin ->
        plugin.settings
            .filter { secretKey == null || "${plugin.id}:${it.key}" == secretKey }
            .map { PluginRow(plugin.id, it) }
    }

data class PluginSecretForm(
    val pluginID: String,
    val setting: PluginSetting,
    val secret: SecretDraft,
    val saving: Boolean = false,
    val error: String = "",
)

// MARK: Judge and vision models

const val JUDGE_DEFAULT = "default"
const val VISION_OFF = "off"

fun modelKey(provider: String, model: String) = "$provider/$model"

private val JudgeModelJudgeModel.key get() = modelKey(provider, model)
private val JudgeChoice.key get() = modelKey(provider, model)

fun judgeValue(data: JudgeModel) = data.judgeModel?.key ?: JUDGE_DEFAULT

fun judgeChoices(data: JudgeModel) = data.choices.filterNotNull()

/** The classifier chosen by `value`, or null for the default. */
fun judgeChoice(data: JudgeModel, value: String) = judgeChoices(data).firstOrNull { it.key == value }

/** What judges now: the classifier, the chat fallback, or nothing. */
fun judgeStatus(data: JudgeModel): String {
    val effective = data.effective
        ?: return "No model is available, so only commit points such as Pay or Send are held. Add an OpenAI API key for GPT-6 Luna."
    return if (effective.kind == "chat") {
        "${effective.name} judges as a chat model: no provider key reaches a classifier."
    } else {
        "${effective.name} judges now."
    }
}

/** The default, each reachable classifier, and a chosen one whose key is gone, so the owner sees why. */
fun judgeOptions(data: JudgeModel): List<ChoiceOption> {
    val chosen = data.judgeModel
    val choices = judgeChoices(data)
    val gone = chosen?.takeIf { ref -> choices.none { it.key == ref.key } }
    return listOf(ChoiceOption(JUDGE_DEFAULT, "Default (GPT-6 Luna)")) +
        choices.map { ChoiceOption(it.key, it.name) } +
        listOfNotNull(gone?.let { ChoiceOption(it.key, "${it.model} (no key)") })
}

fun visionValue(data: VisionModel) = data.visionModel?.let { modelKey(it.provider, it.model) } ?: VISION_OFF

fun visionChoice(data: VisionModel, value: String) = data.choices.firstOrNull { modelKey(it.provider, it.model) == value }

private fun visionUnreachable(data: VisionModel) = data.visionModel != null && visionChoice(data, visionValue(data)) == null

fun visionStatus(data: VisionModel): String {
    val chosen = data.visionModel
        ?: return "Off. A bot whose model can't read images refuses them in its chat and gets only their names in group chats."
    if (visionUnreachable(data)) {
        return "No provider key reaches ${chosen.model}, so images can't be read. Choose another model or turn it off."
    }
    val name = visionChoice(data, visionValue(data))?.name ?: chosen.model
    return "$name describes images for bots whose model can't read them."
}

fun visionOptions(data: VisionModel): List<ChoiceOption> {
    val gone = data.visionModel?.takeIf { visionUnreachable(data) }
    return listOf(ChoiceOption(VISION_OFF, "Off")) +
        data.choices.map { ChoiceOption(modelKey(it.provider, it.model), it.name) } +
        listOfNotNull(gone?.let { ChoiceOption(modelKey(it.provider, it.model), "${it.model} (no key)") })
}

// MARK: Message gateways

data class GatewayAlias(val handle: String, val botId: String)

data class GatewayConfig(
    val enabled: Boolean = false,
    val defaultBotId: String? = null,
    val ownerId: String? = null,
    val allowedChannelIds: List<String> = emptyList(),
    val allowDirectMessages: Boolean = false,
    val aliases: List<GatewayAlias> = emptyList(),
)

data class MessageGateway(
    val id: String,
    val label: String,
    val secretKey: String,
    val tokenConfigured: Boolean,
    val config: GatewayConfig,
    val status: String,
    val error: String?,
    val pendingCount: Long,
    val failedCount: Long,
)

internal fun MessageGatewaysResponseGatewaysItem.domain() = MessageGateway(
    id, label, secretKey, tokenConfigured,
    GatewayConfig(
        config.enabled, config.defaultBotId, config.ownerId, config.allowedChannelIds, config.allowDirectMessages,
        config.aliases.map { GatewayAlias(it.handle, it.botId) },
    ),
    status, error, pendingCount, failedCount,
)

internal fun UpdateMessageGatewayResponse.domain() = MessageGateway(
    id, label, secretKey, tokenConfigured,
    GatewayConfig(
        config.enabled, config.defaultBotId, config.ownerId, config.allowedChannelIds, config.allowDirectMessages,
        config.aliases.map { GatewayAlias(it.handle, it.botId) },
    ),
    status, error, pendingCount, failedCount,
)

internal fun GatewayConfig.wire() = UpdateMessageGatewayRequest(
    enabled, defaultBotId, ownerId, allowedChannelIds, allowDirectMessages,
    aliases.map { UpdateMessageGatewayRequestAliasesItem(it.handle, it.botId) },
)

private val GATEWAY_LABELS = mapOf(
    "disconnected" to "Disconnected",
    "connecting" to "Connecting",
    "connected" to "Connected",
    "error" to "Connection error",
)

fun gatewayStatusLabel(gateway: MessageGateway) =
    if (gateway.config.enabled) GATEWAY_LABELS[gateway.status] ?: gateway.status else "Disabled"

fun gatewayDeliveries(gateway: MessageGateway) = listOfNotNull(
    gateway.pendingCount.takeIf { it > 0 }?.let { "$it pending" },
    gateway.failedCount.takeIf { it > 0 }?.let { "$it failed" },
).joinToString(", ")

data class GatewayChannelRow(val value: String = "", val id: String = UUID.randomUUID().toString())

data class GatewayAliasRow(val handle: String = "", val botId: String = "", val id: String = UUID.randomUUID().toString())

/** A gateway's unsaved edits. Rows carry their own IDs so text fields keep focus when one above goes. */
data class GatewayForm(
    val gatewayID: String,
    val enabled: Boolean,
    val defaultBotId: String?,
    val owner: String,
    val channels: List<GatewayChannelRow>,
    val allowDirectMessages: Boolean,
    val aliases: List<GatewayAliasRow>,
    val attempted: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String = "",
) {
    fun config() = GatewayConfig(
        enabled = enabled,
        defaultBotId = defaultBotId,
        ownerId = owner.trim().ifEmpty { null },
        allowedChannelIds = channels.map { it.value.trim() }.filter { it.isNotEmpty() }.distinct(),
        allowDirectMessages = allowDirectMessages,
        aliases = aliases.map { GatewayAlias(it.handle.trim(), it.botId) },
    )
}

fun gatewayForm(id: String, config: GatewayConfig) = GatewayForm(
    gatewayID = id,
    enabled = config.enabled,
    defaultBotId = config.defaultBotId,
    owner = config.ownerId.orEmpty(),
    channels = config.allowedChannelIds.map { GatewayChannelRow(it) },
    allowDirectMessages = config.allowDirectMessages,
    aliases = config.aliases.map { GatewayAliasRow(it.handle, it.botId) },
)

fun gatewayStatus(dirty: Boolean, form: GatewayForm) = when {
    form.saving -> "Saving…"
    dirty -> "Unsaved changes"
    form.saved -> "Saved"
    else -> ""
}

private val GATEWAY_OWNER = Regex("^[^\\s\\p{Z},]+$")
private val GATEWAY_HANDLE = Regex("^[\\p{L}\\p{N}_-]+$")

private fun normalizedHandle(handle: String) = Normalizer.normalize(handle, Normalizer.Form.NFKC).lowercase()

private fun aliasIssue(aliases: List<GatewayAlias>): String? {
    for (alias in aliases) {
        if (!GATEWAY_HANDLE.matches(alias.handle)) return "Use letters, numbers, underscores or hyphens, without @."
        if (alias.botId.isEmpty()) return "Choose a bot for every alias."
    }
    return "Alias handles must be unique (ignoring case)".takeIf { aliases.distinctBy { normalizedHandle(it.handle) }.size != aliases.size }
}

/** The server's `gatewayConfigInput`, plus what only the bot list can tell: saved bots that were deleted. */
fun gatewayErrors(config: GatewayConfig, botIDs: Set<String>): Map<String, String> = buildMap {
    if (config.ownerId?.let { GATEWAY_OWNER.matches(it) } == false) put("ownerId", "Enter one user ID, without spaces or commas.")
    aliasIssue(config.aliases)?.let { put("aliases", it) }
    if (config.enabled && config.defaultBotId != null && config.defaultBotId !in botIDs) {
        put("defaultBotId", "The saved bot was deleted. Choose another.")
    }
    if (config.enabled && config.aliases.any { it.botId.isNotEmpty() && it.botId !in botIDs }) {
        put("aliases", "Choose an existing bot for every alias.")
    }
}

/** The bots a select offers, plus the saved one when it no longer exists. */
fun gatewayBotOptions(bots: List<Agent>, value: String?, optional: Boolean): List<ChoiceOption> = buildList {
    add(ChoiceOption("", if (optional) "None" else "Choose a bot"))
    for (bot in bots) add(ChoiceOption(bot.id, if (bot.enabled) bot.name else "${bot.name} (disabled)"))
    if (!value.isNullOrEmpty() && bots.none { it.id == value }) add(ChoiceOption(value, "Deleted bot"))
}

// MARK: GitHub

/** The pages GitHub sends the owner back to, from the server's `GITHUB_APP_WEB_PATHS`. */
enum class GitHubStep(val path: String) {
    created("/github-app/callback"),
    installed("/github-app/installed"),
    authorized("/github-app/authorized"),
}

/** A GitHub page open inside the app, so its return can be caught instead of landing on the website. */
data class GitHubFlow(
    val step: GitHubStep,
    val url: String,
    /** Set when GitHub must be opened with the form post that creates an App. */
    val manifest: String? = null,
    val appID: String? = null,
    val finishing: Boolean = false,
    val error: String = "",
    val id: String = UUID.randomUUID().toString(),
)

// Older Apps registered these pages under another prefix, so only the end of the path is fixed.
fun isGitHubReturn(url: String, step: GitHubStep): Boolean =
    runCatching { URI(url).path.orEmpty().trimEnd('/').endsWith(step.path) }.getOrDefault(false)

/** The web UI's origin, which GitHub's callbacks hang off; the app talks to the same address. */
fun webOrigin(baseURL: String): String {
    val uri = URI(baseURL)
    return "${uri.scheme}://${uri.host}" + if (uri.port > 0) ":${uri.port}" else ""
}

fun githubAuthorizedURL(baseURL: String) = webOrigin(baseURL) + GitHubStep.authorized.path

data class CreateGitHubAppForm(
    val name: String = "",
    val organization: String = "",
    val saving: Boolean = false,
    val error: String = "",
) {
    val ready: Boolean get() = name.isNotBlank() && !saving
}

data class GitHubAppForm(
    val appID: String,
    val botIDs: List<String>,
    val clientSecret: SecretDraft = SecretDraft(),
    val checking: Boolean = false,
    val checkError: String = "",
    val signingIn: Boolean = false,
    val signingOut: Boolean = false,
    val signInError: String = "",
    val savingSecret: Boolean = false,
    val secretError: String = "",
    val error: String = "",
)

data class AddGitHubAccountForm(
    val token: SecretDraft = SecretDraft(),
    val saving: Boolean = false,
    val error: String = "",
) {
    val ready: Boolean get() = token.input != null && !saving
}

data class GitHubAccountForm(
    val accountID: String,
    val botIDs: List<String>,
    val token: SecretDraft,
    val saving: Boolean = false,
    val error: String = "",
)

fun toggled(botIDs: List<String>, id: String, on: Boolean) = if (on) botIDs + id else botIDs - id

// MARK: Workspaces

enum class WorkspaceField(val title: String) {
    size("Workspace size"),
    image("Coding image"),
    grace("Question grace period"),
}

data class ServerSettingsForm(
    val field: WorkspaceField,
    val cpus: String,
    val memory: String,
    val image: String,
    val grace: String,
    val error: String = "",
) {
    /** What the sheet changes, or null while a number doesn't parse. */
    fun patch(): UpdateServerSettingsRequest? = when (field) {
        WorkspaceField.size -> {
            val parsedCpus = cpus.trim().toLongOrNull()
            val parsedMemory = memory.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
            if (parsedCpus == null || parsedMemory == null) {
                null
            } else {
                UpdateServerSettingsRequest(
                    workspaceCpus = OptionalField.Present(parsedCpus),
                    workspaceMemoryMib = OptionalField.Present(Math.round(parsedMemory * 1024)),
                )
            }
        }
        WorkspaceField.image -> UpdateServerSettingsRequest(codingImage = OptionalField.Present(image.trim().ifEmpty { null }))
        WorkspaceField.grace -> grace.trim().toLongOrNull()?.let { UpdateServerSettingsRequest(questionGraceMinutes = OptionalField.Present(it)) }
    }
}

fun serverSettingsForm(field: WorkspaceField, settings: ServerSettings) = ServerSettingsForm(
    field = field,
    cpus = settings.workspaceCpus.toString(),
    memory = (settings.workspaceMemoryMib / 1024.0).toString().removeSuffix(".0"),
    image = settings.codingImage.orEmpty(),
    grace = settings.questionGraceMinutes.toString(),
)

internal fun ServerSettings.patched(form: ServerSettingsForm): ServerSettings {
    val patch = form.patch() ?: return this
    return copy(
        workspaceCpus = (patch.workspaceCpus as? OptionalField.Present)?.value ?: workspaceCpus,
        workspaceMemoryMib = (patch.workspaceMemoryMib as? OptionalField.Present)?.value ?: workspaceMemoryMib,
        codingImage = if (form.field == WorkspaceField.image) form.image.trim().ifEmpty { null } else codingImage,
        questionGraceMinutes = (patch.questionGraceMinutes as? OptionalField.Present)?.value ?: questionGraceMinutes,
    )
}

fun workspaceRowTitle(field: WorkspaceField, settings: ServerSettings) = when (field) {
    WorkspaceField.size -> {
        val memory = (settings.workspaceMemoryMib / 1024.0).toString().removeSuffix(".0")
        "Workspace size (${settings.workspaceCpus} CPUs, $memory GB)"
    }
    WorkspaceField.image -> "Coding image (${settings.codingImage ?: "server default"})"
    WorkspaceField.grace -> "Question grace period (${settings.questionGraceMinutes} min)"
}

/** The setup notes for job kinds this server doesn't offer. */
fun workspaceHelp(coding: Boolean, desktop: Boolean) = listOfNotNull(
    "Coding jobs need CRUMBLE_WORKSPACE_IMAGE on the server.".takeIf { !coding },
    "Desktop jobs need CRUMBLE_WORKSPACE_DESKTOP_IMAGE on the server.".takeIf { !desktop },
)

// MARK: MCP servers

val MCPDraft.readsPasswordManager: Boolean
    get() = (token.changed && token.input is SecretInput.Reference) ||
        (clientID.isNotBlank() && clientSecret.changed && clientSecret.input is SecretInput.Reference)

fun matchesMCP(server: MCPServer, needle: String) =
    needle.isEmpty() || server.name.lowercase().contains(needle) || server.url.lowercase().contains(needle)

/** The web's `oauthClientPatch`: clearing the client ID removes its secret too, since it has no use alone. */
internal fun MCPDraft.oauthFields(old: MCPServer?): Map<String, JsonElement> = buildMap {
    val id = clientID.trim()
    if (id.isNotEmpty() || old?.oauthClientId != null) {
        put("oauthClientId", if (id.isNotEmpty()) JsonPrimitive(id) else JsonNull)
    }
    when {
        id.isNotEmpty() -> clientSecret.takeIf { it.changed }?.input?.let { put("oauthClientSecret", it.json()) }
        old?.oauthClientSecret?.configured == true -> put("oauthClientSecret", JsonNull)
    }
}
