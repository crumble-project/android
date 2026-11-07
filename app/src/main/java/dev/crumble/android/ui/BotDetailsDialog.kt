package dev.crumble.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.crumble.android.model.Agent
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.Route
import dev.crumble.android.model.Schedule
import dev.crumble.android.model.automationTiming
import dev.crumble.android.model.automationsOf
import dev.crumble.android.model.nilIfEmpty

@Composable
fun BotDetailsDialog(model: AppModel, bot: Agent) {
    val onClose = { model.showingBotDetails = false }
    JobDialog(
        onClose = onClose,
        title = {},
        actions = {
            IconButton(
                onClick = {
                    onClose()
                    model.openEditor(bot.id)
                },
            ) {
                Icon(Icons.Default.Edit, contentDescription = "Edit bot")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BotAvatarView(agent = bot, size = 84.dp, state = model.botState(bot), live = true)
                Text(bot.name, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
                Text(
                    text = bot.description?.nilIfEmpty ?: if (bot.enabled) "Ready" else "Paused",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if ("desktop" in bot.tools.orEmpty()) DesktopPreviewSection(model, bot.id)
            AutomationsSection(model, bot)
        }
    }
}

@Composable
private fun AutomationsSection(model: AppModel, bot: Agent) {
    val automations = automationsOf(bot.id, model.schedules)
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        GroupLabel("Automations")
        when {
            model.schedulesLoaded -> GroupedRows {
                for (schedule in automations) row { shape -> AutomationRow(model, schedule, shape) }
                if (automations.isEmpty()) note("No automations yet")
            }
            model.schedulesError.isNotEmpty() -> SheetErrorBanner(model.schedulesError)
            else -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun AutomationRow(model: AppModel, schedule: Schedule, shape: Shape) {
    GroupedItem(
        shape = shape,
        leading = { IconTile(routeIcon(Route.Tasks), Tone.Lavender) },
        trailing = {
            Switch(
                checked = schedule.enabled,
                enabled = !model.schedulePending(schedule.id),
                onCheckedChange = { model.toggleSchedule(schedule.id) },
                modifier = Modifier.semantics { contentDescription = "Run ${schedule.name}" },
            )
        },
    ) {
        RowTitle(schedule.name, maxLines = 1)
        RowSubtitle(automationTiming(schedule, model.eventSources))
    }
}
