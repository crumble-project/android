package dev.crumble.android.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.crumble.android.model.Agent
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.ChatRow
import dev.crumble.android.model.MentionCompletion
import dev.crumble.android.model.Message
import dev.crumble.android.model.MessageDeliveryState
import dev.crumble.android.model.PendingAttachment
import dev.crumble.android.model.loadAttachment
import dev.crumble.android.model.messageLabel
import dev.crumble.android.model.nilIfEmpty
import dev.crumble.android.model.preview
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest

@Composable
fun ChatView(
    model: AppModel,
    agent: Agent,
    modifier: Modifier = Modifier,
) {
    val rows = remember(model.messages, model.snapshot, model.hasMore) {
        ChatRow.rows(messages = model.messages, snapshot = model.snapshot, hasMore = model.hasMore)
    }
    // Keep each agent's scroll position isolated. Reverse layout means the
    // newest row is index 0 and is naturally anchored at the bottom.
    val listState = remember(agent.id) { LazyListState() }
    var wasNearBottom by remember(agent.id) { mutableStateOf(true) }
    var hasRenderedRows by remember(agent.id) { mutableStateOf(false) }

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset <= 48
        }
            .distinctUntilChanged()
            .collectLatest { wasNearBottom = it }
    }

    LaunchedEffect(rows.size, model.snapshot?.partial) {
        if (rows.isNotEmpty() && !hasRenderedRows) {
            hasRenderedRows = true
        } else if (rows.isNotEmpty() && wasNearBottom) {
            // With reverseLayout, index 0 is the newest row. This only moves
            // the viewport while the reader is already following the bottom.
            listState.animateScrollToItem(0)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (rows.isEmpty()) {
                EmptyChatView(
                    model = model,
                    agent = agent,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
                ) {
                    items(rows.asReversed(), key = { it.id }) { row ->
                        ChatRowView(model = model, agent = agent, row = row)
                    }
                }
            }
        }
        if (model.snapshot != null) {
            Composer(model = model, agent = agent)
        }
    }
}

@Composable
fun EmptyChatView(
    model: AppModel,
    agent: Agent,
    modifier: Modifier = Modifier,
) {
    if (model.snapshot == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        Column(
            modifier = modifier
                .padding(horizontal = 32.dp)
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BotAvatarView(
                agent = agent,
                size = 96.dp,
                state = model.botState(agent),
                live = true,
            )
            Text(
                text = agent.name,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = agent.description?.nilIfEmpty
                    ?: "Send a message to start. Your agent keeps its work and memory between visits.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun ChatRowView(
    model: AppModel,
    agent: Agent,
    row: ChatRow,
) {
    when (row) {
        is ChatRow.Older -> {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (model.loadingOlder) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = { model.loadOlder() }) {
                        Text("Load Earlier Messages")
                    }
                }
            }
        }
        is ChatRow.Time -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is ChatRow.MessageRow -> {
            MessageView(model = model, message = row.message)
        }
        is ChatRow.Partial -> {
            Row(modifier = Modifier.fillMaxWidth()) {
                ChatBubble(owner = false, modifier = Modifier.widthIn(max = 320.dp)) {
                    MarkdownText(text = row.text)
                }
                Spacer(modifier = Modifier.weight(1f))
            }
        }
        is ChatRow.Thinking -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = "${agent.name} is working…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is ChatRow.Failure -> {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "The agent couldn’t finish",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        text = row.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = { model.showingSettings = true }) {
                        Text("Provider Settings…")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageView(
    model: AppModel,
    message: Message,
) {
    val replyable = !message.entryId.isNullOrEmpty() && (message.role == "user" || message.role == "assistant")
    val owner = message.origin == null || message.origin.kind == "owner"
    var menuExpanded by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    when (message.role) {
        "event" -> {
            if (message.kind == "bot-message") {
                BotMessageView(message = message)
            } else {
                EventView(model = model, message = message)
            }
        }
        "user" -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.width(40.dp))
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (!owner) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(horizontal = 6.dp),
                        ) {
                            if (message.origin?.kind == "bot") {
                                Icon(
                                    imageVector = Icons.Default.Group,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = messageLabel(message),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    ReplyQuote(model = model, message = message)
                    SentAttachments(model = model, message = message)
                    if (message.text.isNotEmpty()) {
                        Box {
                            ChatBubble(
                                owner = owner,
                                modifier = Modifier
                                    .widthIn(max = 310.dp)
                                    .combinedClickable(
                                        onClick = {},
                                        onLongClick = { menuExpanded = true },
                                    ),
                            ) {
                                Text(
                                    text = message.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LocalContentColor.current,
                                )
                            }
                            MessageContextMenu(
                                expanded = menuExpanded,
                                onDismiss = { menuExpanded = false },
                                replyable = replyable,
                                onReply = {
                                    model.replyTo = message
                                    menuExpanded = false
                                },
                                onCopy = {
                                    clipboard.setText(AnnotatedString(message.text))
                                    menuExpanded = false
                                },
                            )
                        }
                    }
                    if (message.deliveryState != MessageDeliveryState.confirmed) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (message.deliveryState == MessageDeliveryState.sending) {
                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                Text(
                                    text = "Sending…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    text = "Not sent",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    text = "Retry",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { model.retryMessage(message) },
                                )
                            }
                        }
                    }
                }
            }
        }
        else -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    ChatBubble(
                        owner = false,
                        modifier = Modifier
                            .widthIn(max = 320.dp)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { menuExpanded = true },
                            ),
                    ) {
                        MarkdownText(text = message.text)
                    }
                    MessageContextMenu(
                        expanded = menuExpanded,
                        onDismiss = { menuExpanded = false },
                        replyable = replyable,
                        onReply = {
                            model.replyTo = message
                            menuExpanded = false
                        },
                        onCopy = {
                            clipboard.setText(AnnotatedString(message.text))
                            menuExpanded = false
                        },
                    )
                }
                if (replyable) {
                    IconButton(
                        onClick = { model.replyTo = message },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Reply,
                            contentDescription = "Reply",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(24.dp))
            }
        }
    }
}

@Composable
private fun MessageContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    replyable: Boolean,
    onReply: () -> Unit,
    onCopy: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (replyable) {
            DropdownMenuItem(
                text = { Text("Reply") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null) },
                onClick = onReply,
            )
        }
        DropdownMenuItem(
            text = { Text("Copy") },
            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
            onClick = onCopy,
        )
    }
}

@Composable
fun BotMessageView(message: Message) {
    var expanded by remember { mutableStateOf(false) }
    val communication = message.communication
    val sender = communication?.fromName?.nilIfEmpty ?: "Unknown bot"
    val recipient = communication?.toName?.nilIfEmpty ?: "Unknown bot"
    val status = when (communication?.status) {
        "done" -> "Reply"
        "failed" -> "Failed"
        "cancelled" -> "Cancelled"
        else -> "Sent"
    }
    val statusColor = when (communication?.status) {
        "failed", "cancelled" -> MaterialTheme.colorScheme.error
        "done" -> Color(0xFF16A34A)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Bot communication: $sender → $recipient",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(modifier = Modifier.weight(1f))
        }
        AnimatedVisibility(visible = expanded) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = statusColor,
                    )
                    MarkdownText(text = message.text)
                }
            }
        }
    }
}

@Composable
fun EventView(
    model: AppModel,
    message: Message,
) {
    val open = model.expanded.contains(message.id)
    val label = if (message.kind == "reset") "Context reset" else "Earlier messages summarized"
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    model.expanded = if (open) model.expanded - message.id else model.expanded + message.id
                }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = open) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 22.dp, bottom = 6.dp),
            )
        }
    }
}

@Composable
fun ReplyQuote(
    model: AppModel,
    message: Message,
) {
    val reply = message.replyTo
    if (!reply.isNullOrEmpty()) {
        val quoted = message.replyPreview?.nilIfEmpty
            ?: model.messages.firstOrNull { it.entryId == reply }?.text
            ?: ""
        if (quoted.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Row(
                    modifier = Modifier
                        .height(IntrinsicSize.Min)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                    Text(
                        text = preview(quoted),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun Composer(
    model: AppModel,
    agent: Agent,
) {
    val context = LocalContext.current
    val running = model.snapshot?.isRunning == true
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        for (uri in uris) {
            try {
                val attachment = loadAttachment(context.contentResolver, uri)
                model.addAttachment(attachment)
            } catch (e: Exception) {
                model.errorText = e.message ?: "Failed to attach file."
            }
        }
    }

    val permittedBots = remember(model.agents, agent) {
        model.agents.filter {
            it.enabled &&
                it.id != agent.id &&
                (agent.delegates ?: emptyList()).contains(it.id) &&
                (agent.tools ?: emptyList()).contains("delegate")
        }
    }
    val completion = remember(model.draft, permittedBots) {
        MentionCompletion.find(
            text = model.draft,
            selectionStart = model.draft.length,
            selectionEnd = model.draft.length,
            bots = permittedBots,
        )
    }

    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val reply = model.replyTo
            if (reply != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(28.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Replying to ${messageLabel(reply)}",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = preview(reply.text),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = { model.replyTo = null },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel Reply",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            if (completion != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (bot in completion.bots) {
                        AssistChip(
                            onClick = {
                                model.draft = model.draft.replaceRange(completion.range, "@${bot.name} ")
                            },
                            label = { Text("@${bot.name}") },
                            leadingIcon = {
                                BotAvatarView(agent = bot, size = 18.dp)
                            },
                        )
                    }
                }
            }

            if (model.attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (file in model.attachments) {
                        PendingAttachmentChip(
                            file = file,
                            onRemove = { model.removeAttachment(file.id) },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    onClick = {
                        filePicker.launch(arrayOf("image/*", "text/*", "application/json", "application/xml"))
                    },
                    enabled = agent.enabled,
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Attach Files",
                    )
                }

                TextField(
                    value = model.draft,
                    onValueChange = { model.draft = it },
                    modifier = Modifier.weight(1f),
                    enabled = agent.enabled,
                    placeholder = {
                        Text(
                            if (agent.enabled) "Message ${agent.name}"
                            else "${agent.name} is paused. Enable it in its profile.",
                        )
                    },
                    maxLines = 6,
                    shape = RoundedCornerShape(22.dp),
                    colors = TextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )

                if (running) {
                    FilledTonalIconButton(onClick = { model.abortChat() }) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                val canSend = agent.enabled && (model.draft.isNotBlank() || model.attachments.isNotEmpty())
                FilledIconButton(
                    onClick = { model.sendMessage() },
                    enabled = canSend,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                    )
                }
            }
        }
    }
}

@Composable
fun PendingAttachmentChip(
    file: PendingAttachment,
    onRemove: () -> Unit,
) {
    val bitmap = remember(file.id) {
        if (file.isImage) {
            BitmapFactory.decodeByteArray(file.data, 0, file.data.size)?.asImageBitmap()
        } else null
    }
    Box(contentAlignment = Alignment.TopEnd) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = file.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
        } else {
            FileLabel(name = file.name)
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(22.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Cancel,
                contentDescription = "Remove ${file.name}",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
fun SentAttachments(
    model: AppModel,
    message: Message,
) {
    val files = message.attachments ?: emptyList()
    if (files.isNotEmpty()) {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            files.forEachIndexed { index, file ->
                if (file.isImage) {
                    val bytes = model.attachmentImageBytes(message, index)
                    val bitmap = remember(bytes) {
                        bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                    }
                    LaunchedEffect(message.entryId, index) {
                        val entryID = message.entryId
                        if (!entryID.isNullOrEmpty()) {
                            model.loadAttachmentImage(entryID = entryID, index = index)
                        }
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = file.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .widthIn(max = 220.dp)
                                .heightIn(max = 220.dp)
                                .clip(RoundedCornerShape(14.dp)),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(width = 160.dp, height = 120.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    RoundedCornerShape(14.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                } else {
                    FileLabel(name = file.name)
                }
            }
        }
    }
}

@Composable
fun FileLabel(name: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 220.dp)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
    }
}

@Composable
fun ChatBubble(
    owner: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val bg = if (owner) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (owner) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = bg,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier,
    ) {
        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
            CompositionLocalProvider(LocalContentColor provides fg) {
                content()
            }
        }
    }
}
