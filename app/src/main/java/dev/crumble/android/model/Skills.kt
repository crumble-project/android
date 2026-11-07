package dev.crumble.android.model

import dev.crumble.android.generated.PendingSkillUpdate
import dev.crumble.android.generated.SkillCheck
import dev.crumble.android.generated.SkillListing
import dev.crumble.android.generated.SkillPackage
import dev.crumble.android.network.APIClient
import dev.crumble.android.network.friendlyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

const val SKILLS_ALL = "all"
const val SKILLS_SHARED = "shared"
const val SKILLS_DEFAULT = "default"

/** A skill the bulk actions can pick: shared, or a bot's own. Default skills are files on the server. */
data class SelectableSkill(
    val id: String,
    val name: String,
    val instructions: String,
    val botID: String?,
    val ownerName: String,
    val installed: Boolean,
)

enum class BulkAction { delete, update, assign, remove }

data class BulkMessage(val id: String, val text: String)

data class BulkResult(
    val failed: Set<String>,
    val messages: List<BulkMessage>,
    /** Skill and bot pairs whose assignment did not apply. */
    val failedAssignments: Set<Pair<String, String>>,
)

data class SkillInstallForm(
    val location: String = "",
    val listing: SkillListing? = null,
    val picked: Set<String> = emptySet(),
    val botIDs: Set<String> = emptySet(),
    val browsing: Boolean = false,
    val installing: Boolean = false,
    val error: String = "",
)

data class InstalledSkillView(
    val skillID: String,
    val full: SkillPackage? = null,
    val error: String = "",
)

data class SkillUpdateView(
    val skillID: String,
    val current: SkillPackage? = null,
    val pending: PendingSkillUpdate? = null,
    val applying: Boolean = false,
    val error: String = "",
)

val SelectableSkill.label: String get() = "$name ($ownerName)"

/** Whether a skill's name or instructions contain `needle`, already lowercased. */
fun matchesSkill(name: String, instructions: String, needle: String): Boolean =
    needle.isEmpty() || name.lowercase().contains(needle) || instructions.lowercase().contains(needle)

/** The first line of a skill's instructions, as a glimpse of what it does. */
fun skillGlimpse(instructions: String): String =
    instructions.lines().map { it.trimStart('#').trim() }.firstOrNull { it.isNotEmpty() } ?: "No instructions."

fun skillSummary(description: String?, instructions: String): String =
    description?.nilIfEmpty ?: skillGlimpse(instructions)

/** Selectable packages only; default server files never enter the selection catalog. */
fun selectionCatalog(shared: List<SharedSkill>, bots: List<Agent>, personal: Map<String, List<SkillPackage>>): List<SelectableSkill> =
    shared.filter { !it.isDefault }.map {
        SelectableSkill(it.id, it.name, it.instructions, null, "Shared", it.origin != null)
    } + bots.flatMap { bot ->
        personal[bot.id].orEmpty().map { SelectableSkill(it.id, it.name, it.instructions, bot.id, bot.name, false) }
    }

fun List<SelectableSkill>.visibleIn(scope: String, needle: String): List<SelectableSkill> = filter {
    (scope == SKILLS_ALL || scope == (it.botID ?: SKILLS_SHARED)) && matchesSkill(it.name, it.instructions, needle)
}

fun botsUsing(bots: List<Agent>, skillID: String): Set<String> =
    bots.filter { it.sharedSkills?.contains(skillID) == true }.map { it.id }.toSet()

/** Skipped files, the first few by name. */
fun skippedNote(skipped: List<String>): String? {
    if (skipped.isEmpty()) return null
    val shown = skipped.take(3).joinToString(", ")
    return if (skipped.size > 3) "Skips $shown and ${skipped.size - 3} more" else "Skips $shown"
}

fun newSkillPaths(listing: SkillListing): Set<String> =
    listing.skills.filter { it.status == "new" }.map { it.path }.toSet()

fun installLabel(count: Int): String = "Install $count ${if (count == 1) "skill" else "skills"}"

fun updateCheckFailures(check: SkillCheck): List<String> =
    check.failed.map { "Couldn't check ${it.repository} (${it.branch}): ${it.message}" }

internal fun SkillPackage.asInline() = Skill(id = id, name = name, instructions = instructions)

internal fun SkillPackage.asShared() = SharedSkill(
    id = id, name = name, instructions = instructions, source = "shared",
    description = description, origin = origin, update = null,
)

/** A row to show until the server answers. Editing keeps everything the form doesn't touch. */
internal fun provisionalSkill(id: String, agentID: String, name: String, instructions: String, old: SkillPackage?): SkillPackage =
    old?.copy(name = name, instructions = instructions) ?: SkillPackage(
        id = id, name = name, instructions = instructions, createdAt = "", updatedAt = "", agentId = agentID,
        description = "", documents = emptyList(), createdBy = "owner", sourceExecutionId = null, useCount = 0,
        lastUsedAt = null, state = "active", pinned = false,
    )

/** `current` with the records in `ids` back at their place in `original`. */
internal fun <T> restoring(current: List<T>, original: List<T>, ids: Set<String>, idOf: (T) -> String): List<T> {
    val list = current.toMutableList()
    original.forEachIndexed { index, item ->
        if (idOf(item) in ids && list.none { idOf(it) == idOf(item) }) list.add(minOf(index, list.size), item)
    }
    return list
}

/** `current` with one record as it was in `original`, or without it if it wasn't there. Other records stay. */
internal fun <T> recordRestored(current: List<T>, original: List<T>, id: String, idOf: (T) -> String): List<T> {
    val old = original.firstOrNull { idOf(it) == id } ?: return current.filterNot { idOf(it) == id }
    return if (current.any { idOf(it) == id }) current.map { if (idOf(it) == id) old else it } else restoring(current, original, setOf(id), idOf)
}

/**
 * `current` with one record replaced, added, or removed when `item` is null. A read can bring a created
 * record's real row before its save answers, so swapping in the saved record also drops that row.
 */
internal fun <T> upserted(current: List<T>, id: String, item: T?, idOf: (T) -> String): List<T> = when {
    item == null -> current.filterNot { idOf(it) == id }
    current.any { idOf(it) == id } -> current
        .filterNot { idOf(it) == idOf(item) && idOf(it) != id }
        .map { if (idOf(it) == id) item else it }
    else -> current + item
}

private class UpdateCheck(val catalog: List<SharedSkill>, val check: SkillCheck, val error: Throwable? = null)

/** The checker refreshes upstream metadata; only selected skills are applied. */
private suspend fun checkUpdates(api: APIClient): UpdateCheck = try {
    val check = api.checkSkillUpdates()
    UpdateCheck(api.sharedSkills(), check)
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    UpdateCheck(emptyList(), SkillCheck(emptyList()), e)
}

/** Returns why the skill was skipped, or null once it updated. */
private suspend fun updateSkill(api: APIClient, skill: SelectableSkill, updates: UpdateCheck): String? {
    if (!skill.installed) return "not installed from GitHub"
    updates.error?.let { throw it }
    val current = updates.catalog.firstOrNull { it.id == skill.id }
    val origin = current?.origin ?: throw IllegalStateException("Installed skill no longer exists.")
    updates.check.failed.firstOrNull { it.repository == origin.repository && it.branch == origin.branch }
        ?.let { throw IllegalStateException(it.message) }
    if (current.update == null) return "already up to date"
    api.applySkillUpdate(skill.id)
    return null
}

private class Assigned(val botID: String, val failed: Boolean, val message: BulkMessage?)

private suspend fun assignBot(api: APIClient, skill: SelectableSkill, bot: Agent, assigned: Boolean): Assigned {
    val label = "${skill.label} → ${bot.name}"
    return try {
        val skipped = api.assignSkill(skill.id, bot.id, assigned)
        val message = if (skipped) BulkMessage("skipped:${skill.id}:${bot.id}", "Skipped $label: already in the requested state.") else null
        Assigned(bot.id, false, message)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Assigned(bot.id, true, BulkMessage("failed:${skill.id}:${bot.id}", "Failed $label: ${friendlyError(e)}"))
    }
}

/** Different bots are independent, so at most four run at once. Skills run in order: two never race on one bot. */
private suspend fun assignBots(api: APIClient, skill: SelectableSkill, bots: List<Agent>, assigned: Boolean): List<Assigned> {
    val slots = Semaphore(4)
    return coroutineScope {
        bots.map { bot -> async { slots.withPermit { assignBot(api, skill, bot, assigned) } } }.awaitAll()
    }
}

/** Each request is independent: failures stay selected, successes never roll back. */
suspend fun applyBulkAction(api: APIClient, action: BulkAction, picked: List<SelectableSkill>, bots: List<Agent>): BulkResult {
    val failed = linkedSetOf<String>()
    val messages = mutableListOf<BulkMessage>()
    val failedAssignments = mutableSetOf<Pair<String, String>>()
    val updates = if (action == BulkAction.update) checkUpdates(api) else null

    for (skill in picked) {
        try {
            when (action) {
                BulkAction.delete -> {
                    val owner = skill.botID
                    if (owner != null) api.deleteBotSkill(owner, skill.id) else api.deleteSharedSkill(skill.id)
                }
                BulkAction.update -> updateSkill(api, skill, checkNotNull(updates))?.let {
                    messages.add(BulkMessage("skipped:${skill.id}", "Skipped ${skill.label}: $it."))
                }
                BulkAction.assign, BulkAction.remove -> {
                    val results = assignBots(api, skill, bots, action == BulkAction.assign)
                    messages.addAll(results.mapNotNull { it.message })
                    val bad = results.filter { it.failed }
                    if (bad.isNotEmpty()) failed.add(skill.id)
                    failedAssignments.addAll(bad.map { skill.id to it.botID })
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failed.add(skill.id)
            messages.add(BulkMessage("failed:${skill.id}", "Failed ${skill.label}: ${friendlyError(e)}"))
        }
    }

    val summary = "${picked.size - failed.size} of ${picked.size} skills processed" +
        (if (failed.isNotEmpty()) "; ${failed.size} failed and remain selected" else "") + "."
    return BulkResult(failed, listOf(BulkMessage("summary", summary)) + messages, failedAssignments)
}
