package dev.crumble.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.crumble.android.model.Agent
import dev.crumble.android.model.AppModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AvatarBotField(bots: List<Agent>, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it && enabled }) {
        OutlinedTextField(
            value = bots.firstOrNull { it.id == selected }?.name ?: "Preview only",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Bot to edit") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Preview only") }, onClick = { open = false; onSelect("") })
            for (bot in bots) {
                DropdownMenuItem(text = { Text(bot.name) }, onClick = { open = false; onSelect(bot.id) })
            }
        }
    }
}

/** Try portable definitions and save one to a bot. */
@Composable
fun AvatarsPanel(model: AppModel) {
    val page = model.avatarPage
    val bots = model.agents.filterNot { it.id.startsWith("pending:") }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Choose a gallery shape, import a definition, or copy a prompt to design your own.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (state in BotState.entries) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BotAvatarView(avatar = page.value, size = 88.dp, state = state, live = true)
                    Text(state.name.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        AvatarBotField(bots, page.botID, enabled = !page.saving, onSelect = { model.pickAvatarBot(it) })
        // The editor keeps its color drafts, which start over for each bot.
        key(page.botID) {
            AvatarEditor(value = page.value, enabled = !page.saving, onChange = { model.changeAvatar(it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(enabled = page.botID.isNotEmpty() && !page.saving, onClick = { model.saveAvatar() }) { Text("Save avatar to bot") }
            if (page.saving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        if (page.saved) Text("Avatar saved.", style = MaterialTheme.typography.bodySmall)
    }
}
