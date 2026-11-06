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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Key
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.crumble.android.model.Agent
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.Route
import dev.crumble.android.model.ChatRow
import dev.crumble.android.model.MentionCompletion
import dev.crumble.android.model.Message
import dev.crumble.android.model.MessageDeliveryState
import dev.crumble.android.model.PendingAttachment
import dev.crumble.android.model.isNotice
import dev.crumble.android.model.isWebLink
import dev.crumble.android.model.ATTACHMENT_PICKER_TYPES
import dev.crumble.android.model.botReactions
import dev.crumble.android.model.displayOf
import dev.crumble.android.model.isDesktopScreenshot
import dev.crumble.android.model.loadAttachment
import dev.crumble.android.model.mentionableBots
import dev.crumble.android.model.messageLabel
import dev.crumble.android.model.nilIfEmpty
import dev.crumble.android.model.noticeTitle
import dev.crumble.android.model.preview
import androidx.compose.ui.platform.LocalUriHandler
import dev.crumble.android.model.Sources
import dev.crumble.android.model.SplitReply
import dev.crumble.android.model.jobOf
import dev.crumble.android.model.siteOf
import dev.crumble.android.model.sourcesOf
import dev.crumble.android.model.splitSources
import androidx.compose.runtime.snapshotFlow
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChatView(
    model: AppModel,
    agent: Agent,
    modifier: Modifier = Modifier,
) {
    val rows = remember(model.messages, model.snapshot, model.hasMore) {
        ChatRow.rows(messages = model.messages, snapshot = model.snapshot, hasMore = model.hasMore)
    }
    val sources = remember(model.messages) { sourcesOf(model.messages) }
    val reactions = remember(model.messages) { botReactions(model.messages) }
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
                        ChatRowView(model = model, agent = agent, row = row, sources = sources, reactions = reactions)
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
    sources: Map<String, Sources> = emptyMap(),
    reactions: Map<String, List<String>> = emptyMap(),
) {
    when (row) {
        is ChatRow.Older -> OlderRow(loading = model.loadingOlder, onLoad = { model.loadOlder() })
        is ChatRow.Time -> TimeRow(row.label)
        is ChatRow.MessageRow -> {
            MessageView(
                model = model,
                message = row.message,
                sources = sources[row.message.id],
                reactions = reactions[row.message.entryId].orEmpty(),
            )
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
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Working…" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BotAvatarView(
                    agent = agent,
                    size = 24.dp,
                    state = BotState.working,
                    live = true,
                )
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
                    OutlinedButton(onClick = { model.navigate(Route.Providers) }) {
                        Text("Provider Settings…")
                    }
                }
            }
        }
    }
}

@Composable
internal fun OlderRow(loading: Boolean, onLoad: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = onLoad) {
                Text("Load Earlier Messages")
            }
        }
    }
}

@Composable
internal fun TimeRow(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageView(
    model: AppModel,
    message: Message,
    sources: Sources? = null,
    reactions: List<String> = emptyList(),
) {
    val replyable = !message.entryId.isNullOrEmpty() && (message.role == "user" || message.role == "assistant")
    var menuExpanded by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    if (isNotice(message)) {
        NoticeRow(model, message)
        return
    }
    when (message.role) {
        "event" -> {
            when {
                message.heldAction != null -> HeldActionCard(model, message.heldAction, message.text)
                message.jobQuestion != null -> JobQuestionCard(model, message.jobQuestion, message.text)
                message.kind == "bot-message" -> BotMessageView(message = message)
                else -> EventView(model = model, message = message)
            }
        }
        "toolResult" -> {
            val job = jobOf(message)
            val display = remember(message.toolDetails) { displayOf(message) }
            when {
                display != null -> DisplayCardView(display)
                isDesktopScreenshot(message) -> DesktopScreenshotCard(model, message)
                message.botRequest != null -> BotRequestCard(model, message.botRequest)
                message.credentialRequest != null -> CredentialRequestCard(model, message.credentialRequest)
                message.loginRequest != null -> LoginRequestCard(model, message.loginRequest)
                job != null -> JobCard(model, job)
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
                    ReplyQuote(message = message, transcript = model.messages)
                    SentAttachments(
                        message = message,
                        imageBytes = { model.attachmentImageBytes(message, it) },
                        loadImage = { entryID, index -> model.loadAttachmentImage(entryID = entryID, index = index) },
                    )
                    if (message.text.isEmpty() && message.attachments.orEmpty().isNotEmpty() && replyable) {
                        ReplyButton(onClick = { model.replyTo = message })
                    }
                    if (message.text.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (replyable) {
                                ReplyButton(onClick = { model.replyTo = message })
                            }
                            Box {
                                ChatBubble(
                                    owner = true,
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
                    }
                    Reactions(reactions)
                    DeliveryStatus(message = message, onRetry = { model.retryMessage(message) })
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
                        ReplyContent(message.text, sources)
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
                if (replyable) ReplyButton(onClick = { model.replyTo = message })
                Spacer(modifier = Modifier.width(24.dp))
            }
        }
    }
}

@Composable
internal fun DeliveryStatus(message: Message, onRetry: () -> Unit) {
    if (message.deliveryState == MessageDeliveryState.confirmed) return
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
                modifier = Modifier.clickable(onClick = onRetry),
            )
        }
    }
}

/** A bot's Markdown; citations of pages its turn read show as chips after it. */
@Composable
internal fun ReplyContent(text: String, sources: Sources?) {
    val reply = remember(text, sources) {
        if (sources != null) splitSources(text, sources) else SplitReply(text, emptyList())
    }
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MarkdownText(text = reply.text)
        if (reply.chips.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (chip in reply.chips) {
                    AssistChip(
                        onClick = { if (isWebLink(chip.href)) runCatching { uriHandler.openUri(chip.href) } },
                        label = { Text(siteOf(chip.href), maxLines = 1) },
                        modifier = Modifier.semantics { contentDescription = chip.title.ifEmpty { chip.href } },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ReplyButton(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
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

@Composable
internal fun MessageContextMenu(
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

/** A message from an automation, a credential, or another bot, collapsed like the website's. */
@Composable
fun NoticeRow(model: AppModel, message: Message) {
    val open = message.id in model.expanded
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { model.expanded = if (open) model.expanded - message.id else model.expanded + message.id }
                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        ) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(14.dp).rotate(if (open) 90f else 0f),
                tint = tint,
            )
            noticeIcon(message)?.let { icon ->
                Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = tint)
            }
            Text(
                text = noticeTitle(message).orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        AnimatedVisibility(visible = open) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                MarkdownText(text = message.text, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

private fun noticeIcon(message: Message): ImageVector? = when (message.origin?.kind) {
    "credential" -> Icons.Default.Key
    "bot" -> Icons.Default.Group
    else -> null
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
    message: Message,
    transcript: List<Message>,
) {
    val reply = message.replyTo
    if (!reply.isNullOrEmpty()) {
        val quoted = message.replyPreview?.nilIfEmpty
            ?: transcript.firstOrNull { it.entryId == reply }?.text
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
    val permittedBots = remember(model.agents, agent) { mentionableBots(agent, model.agents) }
    val reply = model.replyTo
    ComposerBar(
        draft = model.draft,
        onDraftChange = { model.draft = it },
        attachments = model.attachments,
        // Files load in the background; a late one must not land in another bot's draft.
        onAddAttachment = { if (model.selectedAgentID == agent.id) model.addAttachment(it) },
        onRemoveAttachment = model::removeAttachment,
        onAttachmentError = { model.errorText = it },
        replyTitle = reply?.let { "Replying to ${messageLabel(it)}" },
        replyPreview = reply?.text.orEmpty(),
        onCancelReply = { model.replyTo = null },
        mentionable = permittedBots,
        enabled = agent.enabled,
        placeholder = if (agent.enabled) "Message ${agent.name}" else "${agent.name} is paused. Enable it in its profile.",
        running = model.snapshot?.isRunning == true,
        onStop = { model.abortChat() },
        onSend = { model.sendMessage() },
        footer = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                BotReasoning(model = model, agent = agent)
            }
        },
    )
}

/** The message field with its reply preview, mention suggestions, attachments, and send and stop buttons. */
@Composable
fun ComposerBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    attachments: List<PendingAttachment>,
    onAddAttachment: (PendingAttachment) -> Unit,
    onRemoveAttachment: (UUID) -> Unit,
    onAttachmentError: (String) -> Unit,
    replyTitle: String?,
    replyPreview: String,
    onCancelReply: () -> Unit,
    mentionable: List<Agent>,
    enabled: Boolean,
    placeholder: String,
    running: Boolean,
    onStop: () -> Unit,
    onSend: () -> Unit,
    stopEnabled: Boolean = true,
    footer: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val attachmentScope = rememberCoroutineScope()
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val resolver = context.contentResolver
        val attach = onAddAttachment
        val fail = onAttachmentError
        attachmentScope.launch {
            for (uri in uris) {
                try {
                    attach(withContext(Dispatchers.IO) { loadAttachment(resolver, uri) })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(e.message ?: "Failed to attach file.")
                }
            }
        }
    }

    val completion = remember(draft, mentionable) {
        MentionCompletion.find(
            text = draft,
            selectionStart = draft.length,
            selectionEnd = draft.length,
            bots = mentionable,
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
            if (replyTitle != null) {
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
                                text = replyTitle,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = preview(replyPreview),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = onCancelReply,
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
                                onDraftChange(draft.replaceRange(completion.range, "@${bot.name} "))
                            },
                            label = { Text("@${bot.name}") },
                            leadingIcon = {
                                BotAvatarView(agent = bot, size = 18.dp)
                            },
                        )
                    }
                }
            }

            if (attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (file in attachments) {
                        PendingAttachmentChip(
                            file = file,
                            onRemove = { onRemoveAttachment(file.id) },
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
                        filePicker.launch(ATTACHMENT_PICKER_TYPES)
                    },
                    enabled = enabled,
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Attach Files",
                    )
                }

                TextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    placeholder = { Text(placeholder) },
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
                    FilledTonalIconButton(onClick = onStop, enabled = stopEnabled) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                val canSend = enabled && (draft.isNotBlank() || attachments.isNotEmpty())
                FilledIconButton(
                    onClick = onSend,
                    enabled = canSend,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                    )
                }
            }

            footer()
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
    message: Message,
    imageBytes: (Int) -> ByteArray?,
    loadImage: suspend (String, Int) -> Unit,
) {
    val files = message.attachments ?: emptyList()
    if (files.isNotEmpty()) {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            files.forEachIndexed { index, file ->
                if (file.isImage) {
                    val bytes = imageBytes(index)
                    val bitmap = remember(bytes) {
                        bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                    }
                    LaunchedEffect(message.entryId, index) {
                        val entryID = message.entryId
                        if (!entryID.isNullOrEmpty()) {
                            loadImage(entryID, index)
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
