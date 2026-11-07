package dev.crumble.android.model

import dev.crumble.android.generated.GroupActivity
import dev.crumble.android.generated.GroupChat
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

const val MIN_GROUP_MEMBERS = 2
const val MAX_GROUP_MEMBERS = 6
const val GROUP_NAME_LIMIT = 80

/** One read of a group chat: its newest page of messages and what its bots are doing. */
data class GroupSnapshot(
    val messages: List<Message>,
    val hasMore: Boolean,
    val oldestEntryId: String?,
    val groupChat: GroupChat,
    val activity: GroupActivity,
)

/** The form for a new group chat, or for editing the one named by `groupID`. */
data class GroupDraft(
    val id: UUID = UUID.randomUUID(),
    val groupID: String = "",
    val name: String = "",
    val memberIDs: List<String> = emptyList(),
)

/** What the owner is writing to one chat; it survives leaving the chat. */
data class ChatComposer(
    val draft: String = "",
    val attachments: List<PendingAttachment> = emptyList(),
    val replyTo: Message? = null,
)

/** The open group chat. `group` stays null until the first snapshot arrives. */
data class GroupConversation(
    val groupID: String,
    val group: GroupChat? = null,
    val activity: GroupActivity? = null,
    val messages: List<Message> = emptyList(),
    val hasMore: Boolean = false,
    val oldestEntryID: String? = null,
    val olderLoaded: Boolean = false,
    val loadingOlder: Boolean = false,
    val loadError: String? = null,
    val sendError: String? = null,
    val streamStatus: String = "",
) {
    /** Keeps the rows and cursor of older pages; snapshots only carry the newest page. */
    fun merging(incoming: GroupSnapshot): GroupConversation {
        val known = messages.mapTo(HashSet()) { it.id }
        // After a long disconnect no cursor reaches the rows between the old and new pages.
        val gap = incoming.hasMore && known.isNotEmpty() && incoming.messages.none { it.id in known }
        val keepCursor = olderLoaded && !gap
        return copy(
            group = incoming.groupChat,
            activity = incoming.activity,
            messages = if (gap) incoming.messages else mergeMessages(messages, incoming.messages),
            hasMore = if (keepCursor) hasMore else incoming.hasMore,
            oldestEntryID = if (keepCursor) oldestEntryID else incoming.oldestEntryId,
            olderLoaded = keepCursor,
            loadError = null,
            streamStatus = "",
        )
    }

    fun adding(page: ChatHistoryResponse) = copy(
        messages = mergeMessages(messages, page.messages.orEmpty()),
        hasMore = page.hasMore == true,
        oldestEntryID = page.oldestEntryId,
        olderLoaded = true,
        loadingOlder = false,
    )
}

fun isValidGroup(name: String, memberIDs: List<String>): Boolean =
    name.trim().isNotEmpty() && memberIDs.size in MIN_GROUP_MEMBERS..MAX_GROUP_MEMBERS

/** Group messages have no blocks; reactions and tool activity show elsewhere or not at all. */
fun shownInGroup(message: Message): Boolean =
    (message.role == "user" || message.role == "assistant") &&
        (message.text.isNotBlank() || !message.attachments.isNullOrEmpty())

/** A group's members in join order. A deleted bot has no row. */
fun membersOf(group: GroupChat, agents: List<Agent>): List<Agent> =
    group.memberIds.mapNotNull { id -> agents.firstOrNull { it.id == id } }

fun isGroupRunning(activity: GroupActivity?): Boolean =
    activity?.members.orEmpty().any { it.status == "running" || it.status == "queued" }

private val MEMBER_STATUS = mapOf(
    "running" to "is answering",
    "queued" to "answers next",
    "disabled" to "is disabled and skipped",
)

/** What each member is doing, or who is here when nobody is doing anything. */
fun groupStatusLine(members: List<Agent>, activity: GroupActivity?): String {
    val busy = members.mapNotNull { member ->
        val status = activity?.members?.firstOrNull { it.agentId == member.id }?.status
        MEMBER_STATUS[status]?.takeIf { status != "disabled" }?.let { "${member.name} $it" }
    }
    return if (busy.isNotEmpty()) busy.joinToString(" · ") else members.joinToString(", ") { it.name }
}

/** How an avatar reads to a screen reader, such as "Kev is answering". */
fun memberStatusLabel(member: Agent, status: String?): String =
    "${member.name} ${MEMBER_STATUS[status] ?: "is here"}"

/** Emoji reactions by the entry `targetOf` says they answer, each labeled with the bot that reacted. */
private fun reactionsOf(messages: List<Message>, targetOf: (Message, String?) -> String?): Map<String, List<String>> {
    val reactions = linkedMapOf<String, MutableList<String>>()
    var lastOwner: String? = null
    for (message in messages) {
        if (message.role == "user") message.entryId?.let { lastOwner = it }
        if (message.role != "toolResult" || message.toolName != "react" || message.isError == true) continue
        val emoji = ((message.toolDetails as? JsonObject)?.get("emoji") as? JsonPrimitive)?.contentOrNull ?: continue
        val target = targetOf(message, lastOwner) ?: continue
        reactions.getOrPut(target) { mutableListOf() }.add(message.origin?.name?.let { "$it $emoji" } ?: emoji)
    }
    return reactions
}

/** A group records what each reaction answers. */
fun groupReactions(messages: List<Message>) = reactionsOf(messages) { message, _ -> message.replyTo }

/** A bot reacts to the owner's last message. */
fun botReactions(messages: List<Message>) = reactionsOf(messages) { _, lastOwner -> lastOwner }

/** A group message's sources, which the server stores with the message. */
fun groupSourcesOf(message: Message): Sources? {
    val sources = message.sources.orEmpty().mapNotNull { source -> sourceKey(source.url)?.let { it to source.title } }
    return sources.toMap().takeIf { it.isNotEmpty() }
}

fun groupReplyLabel(message: Message): String =
    if (message.role == "user") "You" else message.origin?.name ?: "a bot"
