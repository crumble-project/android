package dev.crumble.android.ui

import androidx.compose.foundation.layout.navigationBarsPadding
import dev.crumble.android.model.MIN_GROUP_MEMBERS
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.crumble.android.model.MAX_GROUP_MEMBERS
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import dev.crumble.android.model.Agent
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.Route
import dev.crumble.android.model.botStatusLine
import dev.crumble.android.model.membersOf

/** A conversation list, like Messages: a flat list on a rounded sheet, with New chat in reach. */
@Composable
fun HomeView(model: AppModel) {
    LaunchedEffect(model.agents.map { it.id }) { model.followBotStatuses() }
    if (model.agents.isEmpty()) {
        WelcomeView(model = model)
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ConversationSheet {
            items(model.agents, key = { it.id }) { bot -> BotConversation(model, bot) }
            items(model.groupChats, key = { it.id }) { group ->
                val members = membersOf(group, model.agents)
                ConversationRow(
                    title = group.name,
                    preview = members.joinToString(", ") { it.name },
                    enabled = !group.id.startsWith("pending:"),
                    onClick = { model.navigate(Route.GroupChat(group.id)) },
                    leading = { AvatarStack(members.take(2), size = 32.dp) { BotState.idle } },
                )
            }
            if (model.groupsFailed) {
                item {
                    ConversationRow(
                        title = "Couldn't load group chats",
                        onClick = { model.loadGroupChats() },
                        leading = { IconTile(Icons.Default.Refresh, Tone.Orange) },
                    )
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { model.navigate(Route.NewChat) },
            icon = { Icon(Icons.AutoMirrored.Filled.Message, contentDescription = null) },
            text = { Text("New chat") },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
        )
    }
}

/** Starts a chat: a new bot, a group, or a bot that already exists. */
@Composable
fun NewChatPanel(model: AppModel) {
    val bots = model.agents.filterNot { it.id.startsWith("pending:") }
    ConversationSheet {
        item {
            ConversationRow(
                title = "New bot",
                onClick = { model.createAgent() },
                leading = { IconTile(Icons.Default.Add, Tone.Blue) },
            )
        }
        if (bots.size >= MIN_GROUP_MEMBERS) {
            item {
                ConversationRow(
                    title = "New group chat",
                    onClick = { model.navigate(Route.NewGroup) },
                    leading = { IconTile(Icons.Default.GroupAdd, Tone.Lavender) },
                )
            }
        }
        if (bots.isNotEmpty()) item { GroupLabel("Bots") }
        items(bots, key = { it.id }) { bot -> BotConversation(model, bot) }
    }
}

/** Picks a group's bots; the form that follows names it. */
@Composable
fun NewGroupPanel(model: AppModel) {
    var members by remember { mutableStateOf(emptyList<String>()) }
    val bots = model.agents.filterNot { it.id.startsWith("pending:") }
    Box(modifier = Modifier.fillMaxSize()) {
        ConversationSheet {
            item { GroupLabel("Choose two to six bots") }
            items(bots, key = { it.id }) { bot ->
                val on = bot.id in members
                ConversationRow(
                    title = bot.name,
                    preview = "Disabled".takeIf { !bot.enabled },
                    enabled = on || members.size < MAX_GROUP_MEMBERS,
                    onClick = { members = if (on) members - bot.id else members + bot.id },
                    trailing = { Checkbox(checked = on, onCheckedChange = null) },
                    leading = { BotAvatarView(agent = bot, size = 52.dp, live = false) },
                )
            }
        }
        if (members.size >= MIN_GROUP_MEMBERS) {
            ExtendedFloatingActionButton(
                onClick = { model.openNewGroup(members) },
                icon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                text = { Text("Next") },
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
            )
        }
    }
}

@Composable
private fun BotConversation(model: AppModel, agent: Agent) {
    val working = model.isWorking(agent.id)
    ConversationRow(
        title = agent.name,
        preview = agent.lastMessage?.takeIf { !working && it.isNotBlank() } ?: botStatusLine(agent, working),
        enabled = !model.pendingMutations.contains("agent:${agent.id}"),
        onClick = { model.navigate(Route.AgentChat(agent.id)) },
        leading = {
            Box(contentAlignment = Alignment.BottomEnd) {
                BotAvatarView(agent = agent, size = 52.dp, state = model.botState(agent), live = false)
                if (working) WorkingDot()
            }
        },
    )
}

@Composable
private fun WorkingDot() {
    val pulse by rememberInfiniteTransition(label = "working").animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "working dot",
    )
    Box(
        modifier = Modifier
            .size(10.dp)
            .alpha(pulse)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
    )
}
