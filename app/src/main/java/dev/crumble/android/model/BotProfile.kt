package dev.crumble.android.model

import dev.crumble.android.generated.Capabilities

data class BotCapability(val tool: String, val label: String, val help: String)

private val BOT_CAPABILITIES = listOf(
    BotCapability("memory", "Memory", "Remembers facts across chats."),
    BotCapability("web", "Web search", "Searches the web and reads pages."),
    BotCapability("delegate", "Message other bots", "Asks the other bots for help."),
    BotCapability("coding", "Coding", "Works in its own workspace and runs coding jobs."),
    BotCapability("desktop", "Bot desktop", "Runs desktop jobs on its own desktop."),
    BotCapability("create_bots", "Create bots", "Asks you to approve new bots."),
)

/** Coding and desktop need server support; a bot that already has one keeps its toggle so it can be turned off. */
fun shownCapabilities(server: Capabilities?, initialTools: Set<String>): List<BotCapability> =
    BOT_CAPABILITIES.filter { capability ->
        when (capability.tool) {
            "coding" -> server?.coding == true || "coding" in initialTools
            "desktop" -> server?.desktop == true || "desktop" in initialTools
            else -> true
        }
    }

const val WEB_SEARCH_ACCOUNT_DEFAULT = "default"

val WEB_SEARCH_OPTIONS = listOf(ChoiceOption(WEB_SEARCH_ACCOUNT_DEFAULT, "Account default")) + WEB_SEARCH_PROVIDERS

/** Set-up providers, plus the current one when its key is gone, so the owner sees why. */
private fun providerOptions(providers: List<ProviderStatus>, current: String): List<ChoiceOption> {
    val ready = providers.filter { it.configured }.map { ChoiceOption(it.provider, it.name ?: it.provider) }
    val gone = current.takeIf { it.isNotEmpty() && ready.none { option -> option.value == it } }?.let { id ->
        ChoiceOption(id, "${providers.firstOrNull { it.provider == id }?.name ?: id} (not set up)")
    }
    return listOfNotNull(gone) + ready
}

fun desktopProviderOptions(providers: List<ProviderStatus>, current: String): List<ChoiceOption> =
    listOf(ChoiceOption("", "Same as the bot")) + providerOptions(providers, current)

fun botProviderOptions(providers: List<ProviderStatus>, current: String): List<ChoiceOption> =
    listOfNotNull(ChoiceOption("", "Choose a provider").takeIf { current.isEmpty() }) + providerOptions(providers, current)

fun desktopModelHint(providers: List<ProviderStatus>, provider: String, modelCount: Int): String = when {
    providers.none { it.provider == provider && it.configured } ->
        "Add an API key or sign in for this provider in Settings to load its models."
    modelCount > 0 -> "$modelCount models available."
    else -> "Type a model ID."
}

/** What the bot list says under a bot's name. */
fun botListLine(agent: Agent, working: Boolean): String =
    if (working) "Working…" else preview(agent.lastMessage ?: "").nilIfEmpty ?: "No messages yet"
