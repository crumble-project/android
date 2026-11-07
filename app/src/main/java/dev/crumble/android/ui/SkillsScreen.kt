package dev.crumble.android.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.crumble.android.generated.SkillCandidate
import dev.crumble.android.generated.SkillListing
import dev.crumble.android.generated.SkillOrigin
import dev.crumble.android.generated.SkillPackage
import dev.crumble.android.model.Agent
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.BulkAction
import dev.crumble.android.model.InstalledSkillView
import dev.crumble.android.model.SKILLS_ALL
import dev.crumble.android.model.SKILLS_DEFAULT
import dev.crumble.android.model.SKILLS_SHARED
import dev.crumble.android.model.SelectableSkill
import dev.crumble.android.model.SharedSkill
import dev.crumble.android.model.SkillInstallForm
import dev.crumble.android.model.SkillUpdateView
import dev.crumble.android.model.installLabel
import dev.crumble.android.model.label
import dev.crumble.android.model.lineDiff
import dev.crumble.android.model.matchesSkill
import dev.crumble.android.model.selectionCatalog
import dev.crumble.android.model.skillGlimpse
import dev.crumble.android.model.skillLines
import dev.crumble.android.model.skillSummary
import dev.crumble.android.model.skippedNote
import dev.crumble.android.model.visibleIn

private fun SkillOrigin.folder() = "$repository${if (path.isEmpty()) "" else "/$path"}"

private val STATUS_LABELS = mapOf("installed" to "Installed", "update" to "Update available")

@Composable
private fun Muted(text: String, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun Pill(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(50)) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = true) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm()
                },
                colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BotPills(bots: List<Agent>, isOn: (Agent) -> Boolean, onToggle: (Agent, Boolean) -> Unit, enabled: (Agent) -> Boolean = { true }) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (bot in bots) {
            val on = isOn(bot)
            FilterChip(
                selected = on,
                enabled = enabled(bot),
                onClick = { onToggle(bot, !on) },
                label = { Text(bot.name) },
                leadingIcon = { BotAvatarView(agent = bot, size = 16.dp) },
            )
        }
    }
}

@Composable
private fun UsedByField(bots: List<Agent>, isOn: (Agent) -> Boolean, onToggle: (Agent, Boolean) -> Unit, enabled: (Agent) -> Boolean = { true }) {
    if (bots.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Used by", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
        BotPills(bots, isOn, onToggle, enabled)
    }
}

/** A form field choosing which bots use a shared skill. */
@Composable
fun SkillUsedByField(bots: List<Agent>, selected: Set<String>, onChange: (Set<String>) -> Unit) {
    UsedByField(bots, { it.id in selected }, { bot, on -> onChange(if (on) selected + bot.id else selected - bot.id) })
}

@Composable
private fun SelectionBox(model: AppModel, ids: List<String>, label: String, busy: Boolean, colors: CheckboxColors = CheckboxDefaults.colors()) {
    if (ids.isEmpty()) return
    val count = ids.count { it in model.selectedSkills }
    val state = when (count) {
        0 -> ToggleableState.Off
        ids.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    TriStateCheckbox(
        state = state,
        enabled = !busy,
        onClick = { model.toggleSkillSelection(ids, count != ids.size) },
        colors = colors,
        modifier = Modifier.semantics { contentDescription = label },
    )
}

@Composable
private fun SelectAll(model: AppModel, ids: List<String>, label: String, busy: Boolean) {
    if (ids.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        SelectionBox(model, ids, label, busy)
        Muted("Select all")
    }
}

@Composable
private fun RowMenu(content: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { content { open = false } }
    }
}

/** The checkbox takes the tile's place on a selectable row, so the name keeps its width on a phone. */
@Composable
private fun TileSelectionBox(model: AppModel, id: String, label: String, busy: Boolean) {
    val tone = Tone.Lavender
    SelectionBox(
        model,
        listOf(id),
        label,
        busy,
        CheckboxDefaults.colors(checkedColor = tone.content, uncheckedColor = tone.content, checkmarkColor = tone.container),
    )
}

/** A skill row: its name and first line, and what follows on the right. */
@Composable
private fun SkillRow(
    name: String,
    line: String,
    onOpen: () -> Unit,
    shape: Shape,
    selection: (@Composable () -> Unit)? = null,
    badge: String? = null,
    usage: String? = null,
    actions: @Composable () -> Unit = {},
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    GroupedItem(
        shape = shape,
        onClick = onOpen,
        leading = {
            if (selection != null) ToneTile(Tone.Lavender) { selection() } else LetterTile(name)
        },
        trailing = { actions() },
        below = below,
    ) {
        RowTitle(name, maxLines = 1)
        RowSubtitle(line, maxLines = 1)
        if (usage != null) RowSubtitle(usage)
        if (badge != null) Pill(badge)
    }
}

private fun usedByLine(model: AppModel, skillID: String): String? =
    model.agents.count { it.sharedSkills?.contains(skillID) == true }.takeIf { it > 0 }?.let { "(used by $it)" }

@Composable
private fun SharedSkillRow(model: AppModel, skill: SharedSkill, busy: Boolean, shape: Shape) {
    var deleting by remember(skill.id) { mutableStateOf(false) }
    var expanded by remember(skill.id) { mutableStateOf(false) }
    val pending = skill.id.startsWith("pending-") || "skill:${skill.id}" in model.pendingMutations
    val origin = skill.origin
    val update = skill.update
    SkillRow(
        name = skill.name,
        line = skillSummary(skill.description, skill.instructions),
        shape = shape,
        selection = if (pending) null else {
            { TileSelectionBox(model, skill.id, "Select ${skill.name} (Shared)", busy) }
        },
        badge = update?.let { if (it.removed) "Removed upstream" else "Update available" },
        usage = usedByLine(model, skill.id),
        onOpen = { if (origin != null) model.openInstalledSkill(skill.id) else expanded = !expanded },
        actions = {
            RowMenu { close ->
                if (origin != null) {
                    DropdownMenuItem(text = { Text("View") }, onClick = { close(); model.openInstalledSkill(skill.id) })
                    if (update != null && !update.removed) {
                        DropdownMenuItem(text = { Text("Update") }, onClick = { close(); model.openSkillUpdate(skill.id) })
                    }
                    DropdownMenuItem(text = { Text("Make a copy") }, enabled = !pending, onClick = { close(); model.copySkill(skill.id) })
                } else {
                    DropdownMenuItem(text = { Text("Edit") }, enabled = !pending, onClick = { close(); model.openSkillForm(skill.id) })
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(if (origin != null) "Uninstall" else "Delete", color = MaterialTheme.colorScheme.error) },
                    enabled = !pending,
                    onClick = { close(); deleting = true },
                )
            }
        },
        below = if (!expanded) null else {
            {
                Text(skill.instructions, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Assign to bots",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                for (agent in model.agents) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(agent.name, style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = agent.sharedSkills?.contains(skill.id) == true,
                            enabled = "agent-shared-skills:${agent.id}" !in model.pendingMutations,
                            onCheckedChange = { model.setSharedSkillEnabled(agent.id, skill.id, it) },
                        )
                    }
                }
            }
        },
    )
    if (deleting) {
        ConfirmDialog(
            title = if (origin != null) "Uninstall ${skill.name}?" else "Delete ${skill.name}?",
            text = if (origin != null) "Every bot that uses it loses it. You can install it again from GitHub." else "Every bot that uses it loses it. This cannot be undone.",
            confirmLabel = if (origin != null) "Uninstall" else "Delete",
            onConfirm = { model.deleteSharedSkill(skill.id) },
            onDismiss = { deleting = false },
        )
    }
}

@Composable
private fun PersonalSkillRow(model: AppModel, agent: Agent, skill: SkillPackage, busy: Boolean, shape: Shape) {
    var deleting by remember(skill.id) { mutableStateOf(false) }
    val pending = skill.id.startsWith("pending-") || "agent-skill:${skill.id}" in model.pendingMutations
    SkillRow(
        name = skill.name,
        line = skillGlimpse(skill.instructions),
        shape = shape,
        selection = if (pending) null else {
            { TileSelectionBox(model, skill.id, "Select ${skill.name} (${agent.name})", busy) }
        },
        badge = if (skill.pinned) "Pinned" else null,
        onOpen = { if (!pending) model.openSkillForm(skill.id, agent.id) },
        actions = {
            RowMenu { close ->
                DropdownMenuItem(text = { Text("Edit") }, enabled = !pending, onClick = { close(); model.openSkillForm(skill.id, agent.id) })
                DropdownMenuItem(text = { Text(if (skill.pinned) "Unpin" else "Pin") }, enabled = !pending, onClick = { close(); model.toggleSkillPin(agent.id, skill.id) })
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    enabled = !pending,
                    onClick = { close(); deleting = true },
                )
            }
        },
    )
    if (deleting) {
        ConfirmDialog(
            title = "Delete ${skill.name}?",
            text = "${agent.name} loses this skill. This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = { model.deleteAgentSkill(agent.id, skill.id) },
            onDismiss = { deleting = false },
        )
    }
}

@Composable
private fun BundledRow(skill: SharedSkill, shape: Shape) {
    var expanded by remember(skill.id) { mutableStateOf(false) }
    GroupedItem(
        shape = shape,
        onClick = { expanded = !expanded },
        leading = { LetterTile(skill.name) },
        trailing = { Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null) },
        below = if (!expanded) null else {
            {
                Text(skill.instructions.ifEmpty { "No instructions." }, style = MaterialTheme.typography.bodyMedium)
                skill.path?.let { Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
    ) {
        RowTitle(skill.name, maxLines = 1)
        RowSubtitle(skillSummary(skill.description, skill.instructions), maxLines = 1)
    }
}

@Composable
private fun ScopeChips(model: AppModel, scope: String, onScope: (String) -> Unit) {
    val shared = model.sharedSkills.count { !it.isDefault }
    val bundled = model.sharedSkills.count { it.isDefault }
    val own = model.agents.sumOf { model.agentSkills[it.id].orEmpty().size }
    val partial = model.agentSkillErrors.isNotEmpty()
    fun counted(label: String, count: Int?) = if (count == null) label else "$label · $count"
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        FilterChip(scope == SKILLS_ALL, { onScope(SKILLS_ALL) }, { Text(counted("All", if (partial) null else shared + own + bundled)) })
        FilterChip(scope == SKILLS_SHARED, { onScope(SKILLS_SHARED) }, { Text(counted("Shared", shared)) })
        for (bot in model.agents) {
            val count = if (bot.id in model.agentSkillErrors) null else model.agentSkills[bot.id].orEmpty().size
            FilterChip(
                selected = scope == bot.id,
                onClick = { onScope(bot.id) },
                label = { Text(counted(bot.name, count)) },
                leadingIcon = { BotAvatarView(agent = bot, size = 18.dp) },
            )
        }
        FilterChip(scope == SKILLS_DEFAULT, { onScope(SKILLS_DEFAULT) }, { Text(counted("Default", bundled)) })
    }
}

@Composable
private fun ErrorLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
fun SkillsPanel(model: AppModel) {
    var search by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(SKILLS_ALL) }
    var dialog by remember { mutableStateOf<BulkAction?>(null) }
    val needle = search.trim().lowercase()
    val catalog = selectionCatalog(model.sharedSkills, model.agents, model.agentSkills)
    val picked = catalog.filter { it.id in model.selectedSkills }
    val busy = "skills:bulk" in model.pendingMutations
    val shared = model.sharedSkills.filter { !it.isDefault && matchesSkill(it.name, it.instructions, needle) }
    val bundled = model.sharedSkills.filter { it.isDefault && matchesSkill(it.name, it.instructions, needle) }
    val ownScope = scope != SKILLS_SHARED && scope != SKILLS_DEFAULT
    val owners = model.agents.filter { it.id !in model.agentSkillErrors }.filter { bot ->
        if (scope == SKILLS_ALL) model.agentSkills[bot.id].orEmpty().any { matchesSkill(it.name, it.instructions, needle) } else bot.id == scope
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        label = { Text("Search skills") },
                        shape = CircleShape,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        if (model.sharedSkills.any { it.origin != null }) {
                            OutlinedButton(enabled = !model.checkingSkillUpdates, onClick = { model.checkSkillUpdates() }) {
                                if (model.checkingSkillUpdates) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.size(6.dp))
                                }
                                Text("Check for updates")
                            }
                        }
                        OutlinedButton(onClick = { model.openSkillInstall() }) { Text("Install from GitHub") }
                    }
                    ScopeChips(model, scope) { scope = it }
                }
            }
            if (model.skillCheckFailures.isNotEmpty()) {
                item { ErrorLine(model.skillCheckFailures.joinToString(" ")) }
            }
            if (model.bulkReport.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (line in model.bulkReport) Text(line.text, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(model.agents.filter { it.id in model.agentSkillErrors }, key = { "error-${it.id}" }) { bot ->
                ErrorLine("Could not load ${bot.name}'s skills: ${model.agentSkillErrors[bot.id]}")
            }
            item { SelectAll(model, catalog.visibleIn(scope, needle).map { it.id }, "Select all results", busy) }
            if (scope == SKILLS_ALL || scope == SKILLS_SHARED) {
                item {
                    SectionFrame(title = "Shared", footer = "Turn a shared skill on for the bots that should have it.") {
                        SelectAll(model, shared.filterNot { it.id.startsWith("pending-") }.map { it.id }, "Select all shared skills", busy)
                        GroupedRows {
                            if (shared.isEmpty()) note("No shared skills yet.")
                            for (skill in shared) row { shape -> SharedSkillRow(model, skill, busy, shape) }
                            add("Add shared skill") { model.openSkillForm("") }
                        }
                    }
                }
            }
            if (ownScope) {
                items(owners, key = { it.id }) { agent ->
                    val skills = model.agentSkills[agent.id].orEmpty()
                    val shown = skills.filter { matchesSkill(it.name, it.instructions, needle) }
                    SectionFrame(title = agent.name, footer = "Personal skills only ${agent.name} follows.") {
                        SelectAll(model, shown.filterNot { it.id.startsWith("pending-") }.map { it.id }, "Select all ${agent.name} skills", busy)
                        GroupedRows {
                            if (shown.isEmpty()) note(if (skills.isEmpty()) "No personal skills yet." else "No skills match your search.")
                            for (skill in shown) row { shape -> PersonalSkillRow(model, agent, skill, busy, shape) }
                            add("Add personal skill") { model.openSkillForm("", agent.id) }
                        }
                    }
                }
            }
            if (scope == SKILLS_ALL || scope == SKILLS_DEFAULT) {
                item {
                    RowsSection(title = "Default", footer = "Every bot has these. They're files on the server: edit them there and restart it.") {
                        if (bundled.isEmpty()) note("No default skills.")
                        for (skill in bundled) row { shape -> BundledRow(skill, shape) }
                    }
                }
            }
        }
        if (picked.isNotEmpty()) {
            BulkBar(model, picked, busy) { action -> if (action == BulkAction.update) model.runBulkAction(action) else dialog = action }
        }
    }

    when (val action = dialog) {
        BulkAction.delete -> ConfirmDialog(
            title = "Delete ${picked.size} skills?",
            text = "Delete: ${picked.joinToString(", ") { it.label }}. Shared skills are removed from every bot. Existing bot-specific copies are kept. This cannot be undone here.",
            confirmLabel = "Delete",
            onConfirm = { model.runBulkAction(BulkAction.delete) },
            onDismiss = { dialog = null },
        )
        BulkAction.assign, BulkAction.remove -> BotTargetsDialog(
            action = action,
            bots = model.agents,
            picked = picked,
            onSubmit = { targets ->
                dialog = null
                model.runBulkAction(action, targets)
            },
            onClose = { dialog = null },
        )
        BulkAction.update, null -> Unit
    }
}

private val BULK_ACTIONS = listOf(
    BulkAction.update to "Update",
    BulkAction.assign to "Assign to bots",
    BulkAction.remove to "Remove from bots",
    BulkAction.delete to "Delete",
)

@Composable
private fun BulkBar(model: AppModel, picked: List<SelectableSkill>, busy: Boolean, onAction: (BulkAction) -> Unit) {
    val notInstalled = picked.filter { !it.installed }
    Surface(
        color = cardColor,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
                IconButton(enabled = !busy, onClick = { model.selectedSkills = emptySet() }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear selection")
                }
                Text("${picked.size} selected", style = MaterialTheme.typography.labelLarge)
                for ((action, label) in BULK_ACTIONS) {
                    val disabled = when (action) {
                        BulkAction.update -> notInstalled.size == picked.size
                        BulkAction.assign, BulkAction.remove -> model.agents.isEmpty()
                        BulkAction.delete -> false
                    }
                    TextButton(enabled = !busy && !disabled, onClick = { onAction(action) }) {
                        Text(label, color = if (action == BulkAction.delete && !busy) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
                    }
                }
            }
            if (notInstalled.isNotEmpty()) {
                Muted("Update skips skills not installed from GitHub: ${notInstalled.joinToString(", ") { it.label }}.")
            }
            if (busy) Muted("Applying action…")
        }
    }
}

@Composable
private fun BotTargetsDialog(action: BulkAction, bots: List<Agent>, picked: List<SelectableSkill>, onSubmit: (Set<String>) -> Unit, onClose: () -> Unit) {
    val assign = action == BulkAction.assign
    var targets by remember { mutableStateOf(emptySet<String>()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (assign) "Assign to bots" else "Remove from bots") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (assign) {
                        "Shared skills are enabled. Bot-specific skills are copied; existing linked copies and their edits are kept."
                    } else {
                        "Shared skills are disabled. Only linked bot-specific copies are deleted, including edited copies; same-name skills and the original are kept."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(picked.joinToString(", ") { it.label }, style = MaterialTheme.typography.bodyMedium)
                BotPills(bots, { it.id in targets }, { bot, on -> targets = if (on) targets + bot.id else targets - bot.id })
            }
        },
        confirmButton = { Button(enabled = targets.isNotEmpty(), onClick = { onSubmit(targets) }) { Text(if (assign) "Assign" else "Remove") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun CandidateRow(candidate: SkillCandidate, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val selectable = candidate.status == "new"
    val note = candidate.problem ?: skippedNote(candidate.skipped)
    Row(verticalAlignment = Alignment.Top) {
        Checkbox(checked = checked, enabled = selectable && enabled, onCheckedChange = onChange)
        Column(modifier = Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(candidate.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                STATUS_LABELS[candidate.status]?.let { Pill(it) }
            }
            if (candidate.description.isNotEmpty()) Muted(candidate.description)
            if (note != null) {
                if (candidate.problem != null) ErrorLine(note) else Muted(note)
            }
        }
    }
}

@Composable
private fun CandidateList(listing: SkillListing, form: SkillInstallForm, onToggle: (String, Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Skills in ${listing.repository} (${listing.branch})", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
        if (listing.skills.isEmpty()) Muted("No folder here has a SKILL.md.")
        for (candidate in listing.skills) {
            CandidateRow(candidate, candidate.path in form.picked, !form.installing) { onToggle(candidate.path, it) }
        }
        if (listing.omitted > 0) Muted("${listing.omitted} more aren't listed. Link to a folder to see them.")
    }
}

/** Finds the skills in a GitHub repository and installs the checked ones as shared skills. */
@Composable
fun InstallSkillsDialog(model: AppModel, form: SkillInstallForm) {
    val listing = form.listing
    val idle = !form.browsing && !form.installing
    AlertDialog(
        onDismissRequest = { if (!form.installing) model.closeSkillInstall() },
        title = { Text("Install from GitHub") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Muted("Installed skills are shared and read-only. You can update them when the repository changes.")
                SheetErrorBanner(form.error)
                OutlinedTextField(
                    value = form.location,
                    onValueChange = { model.changeSkillLocation(it) },
                    enabled = !form.installing,
                    label = { Text("Repository") },
                    placeholder = { Text("owner/repo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Muted("A public repository, or a github.com link to a folder in one.")
                if (form.browsing) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                if (listing != null) {
                    OutlinedButton(enabled = idle, onClick = { model.findSkills() }) { Text("Find") }
                    CandidateList(listing, form) { path, on -> model.toggleSkillCandidate(path, on) }
                    UsedByField(model.agents, { it.id in form.botIDs }, { bot, on -> model.toggleInstallBot(bot.id, on) }, { !form.installing })
                }
                if (form.installing) Muted("Installing…")
            }
        },
        confirmButton = {
            if (listing != null) {
                Button(enabled = idle && form.picked.isNotEmpty(), onClick = { model.installSkills() }) { Text(installLabel(form.picked.size)) }
            } else {
                Button(enabled = idle && form.location.isNotBlank(), onClick = { model.findSkills() }) { Text("Find skills") }
            }
        },
        dismissButton = { TextButton(enabled = !form.installing, onClick = { model.closeSkillInstall() }) { Text("Cancel") } },
    )
}

@Composable
private fun SkillContent(skill: SkillPackage) {
    if (skill.description.isNotEmpty()) Text(skill.description, style = MaterialTheme.typography.bodyMedium)
    Surface(color = sheetColor, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(skill.instructions, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(10.dp))
    }
    if (skill.documents.isNotEmpty()) {
        Text("Reference documents", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
        for (document in skill.documents) {
            Text(document.path, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** An installed skill, read-only: its source, who uses it, its content, and a pending update. */
@Composable
fun InstalledSkillDialog(model: AppModel, view: InstalledSkillView) {
    val skill = model.sharedSkills.firstOrNull { it.id == view.skillID } ?: return
    val close = { model.installedSkill = null }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(skill.name) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Muted("Installed skills are read-only. Make a copy to change one.")
                SheetErrorBanner(view.error)
                skill.origin?.let { origin ->
                    Muted("From ${origin.folder()} on ${origin.branch}, commit ${origin.commit.take(7)}.")
                }
                UsedByField(
                    bots = model.agents,
                    isOn = { it.sharedSkills?.contains(skill.id) == true },
                    onToggle = { bot, on -> model.setSharedSkillEnabled(bot.id, skill.id, on) },
                    enabled = { "agent-shared-skills:${it.id}" !in model.pendingMutations },
                )
                skill.update?.let { update ->
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (update.removed) "This skill is no longer in the repository." else "A newer version is available.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            if (!update.removed) Button(onClick = { model.openSkillUpdate(skill.id) }) { Text("Review update") }
                        }
                    }
                }
                val full = view.full
                if (full != null) {
                    SkillContent(full)
                } else if (view.error.isEmpty()) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        },
        confirmButton = { Button(onClick = close) { Text("Done") } },
        dismissButton = {
            OutlinedButton(onClick = {
                model.copySkill(skill.id)
                close()
            }) { Text("Make a copy") }
        },
    )
}

/** The changes an update brings, and applying it. */
@Composable
fun SkillUpdateDialog(model: AppModel, view: SkillUpdateView) {
    val skill = model.sharedSkills.firstOrNull { it.id == view.skillID }
    val current = view.current
    val pending = view.pending
    AlertDialog(
        onDismissRequest = { if (!view.applying) model.skillUpdate = null },
        title = { Text("Update ${skill?.name.orEmpty()}") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Muted("The update is recorded in the change log, so you can revert it.")
                SheetErrorBanner(view.error)
                if (current != null && pending != null) {
                    val lines = remember(current, pending) {
                        lineDiff(skillLines(current), skillLines(pending.name, pending.description, pending.instructions, pending.documents))
                    }
                    DiffView(lines)
                    skippedNote(pending.skipped)?.let { Muted(it) }
                } else if (view.error.isEmpty()) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                if (view.applying) Muted("Updating…")
            }
        },
        confirmButton = {
            Button(enabled = pending != null && !view.applying, onClick = { model.applySkillUpdate() }) { Text("Update") }
        },
        dismissButton = { TextButton(enabled = !view.applying, onClick = { model.skillUpdate = null }) { Text("Cancel") } },
    )
}
