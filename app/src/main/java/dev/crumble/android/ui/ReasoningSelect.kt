package dev.crumble.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.crumble.android.model.Agent
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.THINKING_LEVELS
import dev.crumble.android.model.clampLevel

@Composable
fun ReasoningSelect(
    levels: List<String>,
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val items = THINKING_LEVELS.filter { it.value in levels }
    val label = items.firstOrNull { it.value == value }?.label ?: value
    Box {
        TextButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "Reasoning: $label" },
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (level in items) {
                DropdownMenuItem(
                    text = { Text(level.label) },
                    onClick = {
                        open = false
                        if (level.value != value) onChange(level.value)
                    },
                )
            }
        }
    }
}

/** The composer's level picker; hidden for models that don't reason. */
@Composable
fun BotReasoning(model: AppModel, agent: Agent) {
    LaunchedEffect(agent.provider) { model.loadReasoningModels(agent.provider.orEmpty()) }
    val levels = model.reasoningLevels(agent)
    if (levels.size < 2) return
    ReasoningSelect(
        levels = levels,
        value = clampLevel(agent.inference?.thinkingLevel ?: "off", levels),
        enabled = "agent:${agent.id}" !in model.pendingMutations,
        onChange = { model.setReasoning(agent, it) },
    )
}
