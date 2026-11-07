package dev.crumble.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.crumble.android.generated.GroupActivity
import dev.crumble.android.generated.GroupChat
import dev.crumble.android.model.Agent
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.ChatRow
import dev.crumble.android.model.GROUP_NAME_LIMIT
import dev.crumble.android.model.GroupConversation
import dev.crumble.android.model.GroupDraft
import dev.crumble.android.model.MAX_GROUP_MEMBERS
import dev.crumble.android.model.Message
import dev.crumble.android.model.groupReactions
import dev.crumble.android.model.groupReplyLabel
import dev.crumble.android.model.groupSourcesOf
import dev.crumble.android.model.groupStatusLine
import dev.crumble.android.model.isGroupRunning
import dev.crumble.android.model.isNotice
import dev.crumble.android.model.isValidGroup
import dev.crumble.android.model.memberStatusLabel
import dev.crumble.android.model.membersOf
import dev.crumble.android.model.shownInGroup
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun GroupChatView(
    model: AppModel,
    modifier: Modifier = Modifier,
) {
    val conversation = model.groupConversation
    val group = conversation?.group
    if (conversation == null || group == null) {
        Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            val error = conversation?.loadError
            if (error != null) SheetErrorBanner(error) else CircularProgressIndicator()
        }
        return
    }
    val outgoing = model.groupOutgoing[group.id].orEmpty()
    val composer = model.groupComposer(group.id)
    val transcript = conversation.messages
    val rows = remember(transcript, outgoing, conversation.hasMore) {
        ChatRow.rows(transcript + outgoing, null, conversation.hasMore && conversation.oldestEntryID != null, ::shownInGroup)
    }
    val reactions = remember(transcript) { groupReactions(transcript) }
    val members = remember(group, model.agents) { membersOf(group, model.agents) }
    val listState = remember(group.id) { LazyListState() }
    var wasNearBottom by remember(group.id) { mutableStateOf(true) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 48 }
            .distinctUntilChanged()
            .collectLatest { wasNearBottom = it }
    }
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty() && wasNearBottom) listState.animateScrollToItem(0)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (rows.none { it is ChatRow.MessageRow }) {
                GroupEmpty(modifier = Modifier.align(Alignment.Center))
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
            ) {
                groupActivityRows(conversation.activity, model.agents)
                items(rows.asReversed(), key = { it.id }) { row ->
                    when (row) {
                        is ChatRow.Older -> OlderRow(conversation.loadingOlder) { model.loadEarlierGroupMessages() }
                        is ChatRow.Time -> TimeRow(row.label)
                        is ChatRow.MessageRow -> if (isNotice(row.message)) {
                            NoticeRow(model, row.message)
                        } else {
                            GroupMessageView(
                                model = model,
                                conversation = conversation,
                                message = row.message,
                                reactions = reactions[row.message.entryId].orEmpty(),
                            )
                        }
                        else -> Unit
                    }
                }
            }
        }
        conversation.sendError?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        val reply = composer.replyTo
        ComposerBar(
            draft = composer.draft,
            onDraftChange = { text -> model.updateGroupComposer(group.id) { it.copy(draft = text) } },
            attachments = composer.attachments,
            onAddAttachment = { model.addGroupAttachment(group.id, it) },
            onRemoveAttachment = { id ->
                model.updateGroupComposer(group.id) { current -> current.copy(attachments = current.attachments.filterNot { it.id == id }) }
            },
            onAttachmentError = { model.errorText = it },
            replyTitle = reply?.let { "Replying to ${groupReplyLabel(it)}" },
            replyPreview = reply?.text.orEmpty(),
            onCancelReply = { model.updateGroupComposer(group.id) { it.copy(replyTo = null) } },
            mentionable = members.filter { it.enabled },
            enabled = true,
            placeholder = "Message ${group.name}",
            running = isGroupRunning(conversation.activity),
            stopEnabled = "group-stop:${group.id}" !in model.pendingMutations,
            onStop = { model.stopGroupChat() },
            onSend = { model.sendGroupMessage() },
        )
    }
}

@Composable
private fun GroupEmpty(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Start the conversation",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
        Text(
            text = "Everyone here reads every message. Mention a bot with @ to ask only it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** In a reversed list the first items sit lowest, so these rows follow the newest message. */
private fun androidx.compose.foundation.lazy.LazyListScope.groupActivityRows(activity: GroupActivity?, agents: List<Agent>) {
    val members = activity?.members.orEmpty()
    if (activity?.paused == true) {
        item(key = "paused") {
            Text(
                text = "The bots are waiting for you. Your next message lets them continue.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    items(members.filter { it.error != null }, key = { "failure-${it.agentId}" }) { member ->
        GroupFailure(name = agents.firstOrNull { it.id == member.agentId }?.name ?: "A bot", error = member.error.orEmpty())
    }
    val working = members.filter { it.status == "running" }.mapNotNull { member -> agents.firstOrNull { it.id == member.agentId } }
    if (members.any { it.status == "running" }) {
        item(key = "working") {
            Row(
                modifier = Modifier.semantics { contentDescription = "Working…" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AvatarStack(working, size = 24.dp) { BotState.working }
                Text(
                    text = "Working…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GroupFailure(name: String, error: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "$name couldn't finish",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
internal fun AvatarStack(
    bots: List<Agent>,
    size: Dp,
    modifier: Modifier = Modifier,
    label: (Agent) -> String? = { null },
    state: (Agent) -> BotState,
) {
    val step = size * 3 / 4
    Box(modifier = modifier.width(size + step * maxOf(0, bots.size - 1))) {
        bots.forEachIndexed { index, bot ->
            val text = label(bot)
            BotAvatarView(
                agent = bot,
                size = size,
                state = state(bot),
                modifier = Modifier
                    .offset(x = step * index)
                    .then(if (text != null) Modifier.semantics { contentDescription = text } else Modifier),
            )
        }
    }
}

/** The top bar's title for a group chat: its members, its name, and who is doing what. */
@Composable
fun GroupTitle(model: AppModel, groupID: String) {
    val conversation = model.groupConversation?.takeIf { it.groupID == groupID }
    val group = conversation?.group ?: model.groupChats.firstOrNull { it.id == groupID }
    val activity = conversation?.activity
    val members = group?.let { membersOf(it, model.agents) }.orEmpty()
    fun status(id: String) = activity?.members?.firstOrNull { it.agentId == id }?.status
    Row(
        modifier = Modifier.clickable(enabled = group != null) { model.openGroupEditor(groupID) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AvatarStack(
            bots = members,
            size = 26.dp,
            label = { memberStatusLabel(it, status(it.id)) },
        ) {
            when (status(it.id)) {
                "running" -> BotState.working
                "disabled" -> BotState.sleeping
                else -> BotState.idle
            }
        }
        Column(modifier = Modifier.heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
            Text(
                text = group?.name ?: "Group chat",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = groupStatusLine(members, activity)
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun Reactions(labels: List<String>) {
    if (labels.isEmpty()) return
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (label in labels) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupMessageView(
    model: AppModel,
    conversation: GroupConversation,
    message: Message,
    reactions: List<String>,
) {
    val replyable = !message.entryId.isNullOrEmpty()
    val owner = message.role == "user"
    var menuExpanded by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val reply = { model.updateGroupComposer(conversation.groupID) { it.copy(replyTo = message) } }
    val bubble: @Composable () -> Unit = {
        Box {
            ChatBubble(
                owner = owner,
                modifier = Modifier
                    .widthIn(max = 310.dp)
                    .combinedClickable(onClick = {}, onLongClick = { menuExpanded = true }),
            ) {
                if (owner) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalContentColor.current,
                    )
                } else {
                    ReplyContent(message.text, groupSourcesOf(message))
                }
            }
            MessageContextMenu(
                expanded = menuExpanded,
                onDismiss = { menuExpanded = false },
                replyable = replyable,
                onReply = {
                    reply()
                    menuExpanded = false
                },
                onCopy = {
                    clipboard.setText(AnnotatedString(message.text))
                    menuExpanded = false
                },
            )
        }
    }

    if (owner) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Spacer(modifier = Modifier.width(40.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ReplyQuote(message = message, transcript = conversation.messages)
                SentAttachments(
                    message = message,
                    imageBytes = { model.groupAttachmentImageBytes(message, it) },
                    loadImage = { entryID, index -> model.loadGroupAttachmentImage(entryID, index) },
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (replyable) ReplyButton(onClick = { reply() })
                    if (message.text.isNotEmpty()) bubble()
                }
                Reactions(reactions)
                DeliveryStatus(message = message, onRetry = { model.retryGroupMessage(message) })
            }
        }
    } else {
        val bot = model.agents.firstOrNull { it.id == message.origin?.agentId }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = message.origin?.name ?: bot?.name ?: "Bot",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = bot?.avatar?.let { avatarColor(it.color) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                bubble()
                if (replyable) ReplyButton(onClick = { reply() })
            }
            Reactions(reactions)
        }
    }
}

@Composable
fun GroupFormDialog(
    model: AppModel,
    draft: GroupDraft,
) {
    val isNew = draft.groupID.isEmpty()
    val savedName = model.groupChats.firstOrNull { it.id == draft.groupID }?.name ?: draft.name
    val pending = "group:${draft.groupID}" in model.pendingMutations
    var confirmingDelete by remember(draft.id) { mutableStateOf(false) }
    val close = {
        model.errorText = ""
        model.groupDraft = null
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(if (isNew) "New group chat" else "Edit $savedName") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SheetErrorBanner(model.errorText)
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { model.groupDraft = draft.copy(name = it.take(GROUP_NAME_LIMIT)) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!isNew) {
                    GroupLabel("Members")
                    Text(
                        text = "Choose two to six bots. A bot added later gets a summary of the conversation so far.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    GroupedRows {
                        for (bot in model.agents.filterNot { it.id.startsWith("pending:") }) {
                            val on = bot.id in draft.memberIDs
                            row { shape ->
                                GroupedItem(
                                    shape = shape,
                                    leading = { BotAvatarView(agent = bot, size = 44.dp) },
                                    trailing = {
                                        Switch(
                                            checked = on,
                                            enabled = on || draft.memberIDs.size < MAX_GROUP_MEMBERS,
                                            onCheckedChange = { model.toggleGroupMember(bot.id, it) },
                                        )
                                    },
                                ) {
                                    RowTitle(bot.name, maxLines = 1)
                                    if (!bot.enabled) RowSubtitle("Disabled")
                                }
                            }
                        }
                    }
                    TextButton(enabled = !pending, onClick = { confirmingDelete = true }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !pending && isValidGroup(draft.name, draft.memberIDs),
                onClick = { model.saveGroup() },
            ) {
                Text(if (isNew) "Create group chat" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = close) {
                Text("Cancel")
            }
        },
    )
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete $savedName?") },
            text = { Text("The group chat and its messages will be removed. The bots stay. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { model.deleteGroup(draft.groupID) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}
