package dev.crumble.android.model

import dev.crumble.android.generated.GroupChat

private const val PENDING_PREFIX = "pending:"

sealed class PaletteAction {
    data class OpenBot(val id: String) : PaletteAction()
    data class OpenGroup(val id: String) : PaletteAction()
    data object NewBot : PaletteAction()
    data object NewGroup : PaletteAction()
    data class OpenPage(val route: Route) : PaletteAction()
    data class SetTheme(val theme: ThemeChoice) : PaletteAction()
}

/** `terms` are what a search matches, so bots with equal names stay distinct and match by name only. */
data class PaletteItem(
    val title: String,
    val action: PaletteAction,
    val terms: List<String> = listOf(title),
    val note: String? = null,
)

data class PaletteSection(val heading: String, val items: List<PaletteItem>)

fun paletteSections(
    agents: List<Agent>,
    groups: List<GroupChat>,
    theme: ThemeChoice,
): List<PaletteSection> {
    val bots = agents.filterNot { it.id.startsWith(PENDING_PREFIX) }.map {
        PaletteItem(it.name, PaletteAction.OpenBot(it.id), note = "Paused".takeUnless { _ -> it.enabled })
    }
    val chats = groups.filterNot { it.id.startsWith(PENDING_PREFIX) }.map {
        PaletteItem(it.name, PaletteAction.OpenGroup(it.id))
    }
    val create = listOfNotNull(
        PaletteItem("New bot", PaletteAction.NewBot),
        PaletteItem("New group chat", PaletteAction.NewGroup).takeIf { agents.size >= MIN_GROUP_MEMBERS },
    )
    val pages = WORKSPACE_PAGES.map { route ->
        val terms = if (route == Route.Tasks) listOf(route.title, "Automations") else listOf(route.title)
        PaletteItem(route.title, PaletteAction.OpenPage(route), terms)
    }
    val settings = SETTINGS_PAGES.map {
        PaletteItem(it.title, PaletteAction.OpenPage(it), listOf("Settings ${it.title}"))
    }
    val themes = ThemeChoice.entries.map {
        PaletteItem(
            title = "${it.label} theme",
            action = PaletteAction.SetTheme(it),
            terms = listOf("Theme ${it.label}", "${it.label} theme"),
            note = "Current".takeIf { _ -> it == theme },
        )
    }
    return listOf(
        PaletteSection("Bots", bots),
        PaletteSection("Group chats", chats),
        PaletteSection("Create", create),
        PaletteSection("Workspace", pages),
        PaletteSection("Settings", settings),
        PaletteSection("Theme", themes),
    ).filter { it.items.isNotEmpty() }
}

/** Letters in order count, like the website's search: "skl" finds "Skills". Null means no match. */
internal fun matchScore(term: String, query: String): Int? {
    val text = term.lowercase()
    val at = text.indexOf(query)
    if (at == 0) return 400
    if (at > 0) return if (text.contains(" $query")) 300 else 200
    var next = 0
    for (letter in query) {
        next = text.indexOf(letter, next) + 1
        if (next == 0) return null
    }
    return 100
}

/** Best matches first, and the section holding the best match leads, like the website's palette. */
fun searchPalette(sections: List<PaletteSection>, query: String): List<PaletteSection> {
    val search = query.trim().lowercase()
    if (search.isEmpty()) return sections
    return sections
        .mapNotNull { section ->
            val hits = section.items
                .mapNotNull { item -> item.terms.mapNotNull { matchScore(it, search) }.maxOrNull()?.let { item to it } }
                .sortedByDescending { it.second }
            hits.firstOrNull()?.let { (_, best) -> best to section.copy(items = hits.map { it.first }) }
        }
        .sortedByDescending { it.first }
        .map { it.second }
}
