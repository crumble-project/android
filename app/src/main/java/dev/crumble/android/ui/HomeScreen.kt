package dev.crumble.android.ui

import androidx.compose.foundation.layout.navigationBarsPadding
import dev.crumble.android.model.MIN_GROUP_MEMBERS
import androidx.compose.material3.Surface
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import dev.crumble.android.model.Agent
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.Route
import dev.crumble.android.model.botStatusLine
import dev.crumble.android.model.membersOf

/** A conversation list, like Messages: a flat list on a rounded sheet, with New bot in reach. */
@Composable
fun HomeView(model: AppModel) {
    LaunchedEffect(model.agents.map { it.id }) { model.followBotStatuses() }
    if (model.agents.isEmpty()) {
        WelcomeView(model = model)
        return
    }

    val canGroup = model.agents.size >= MIN_GROUP_MEMBERS
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = sheetColor,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp)) {
                items(model.agents, key = { it.id }) { bot -> BotConversation(model, bot) }
                if (model.groupChats.isNotEmpty() || canGroup || model.groupsFailed) {
                    item { GroupLabel("Group chats") }
                }
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
                if (canGroup) {
                    item {
                        ConversationRow(
                            title = "New group chat",
                            onClick = { model.openNewGroup() },
                            leading = { IconTile(Icons.Default.GroupAdd, Tone.Lavender) },
                        )
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { model.createAgent() },
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("New bot") },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
        )
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
