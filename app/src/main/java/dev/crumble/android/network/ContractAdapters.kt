package dev.crumble.android.network

import dev.crumble.android.generated.*
import dev.crumble.android.model.*
import dev.crumble.android.push.PushInfo
import dev.crumble.android.push.PushRegistrationResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun <T> T?.optional(): OptionalField<T> =
    if (this == null) OptionalField.Absent else OptionalField.Present(this)

internal fun dev.crumble.android.model.Agent.wire() = CreateAgentRequest(
    name = name, avatar = avatar.optional(), description = description.optional(),
    instructions = instructions.optional(), provider = provider.optional(), model = model.optional(),
    enabled = OptionalField.Present(enabled), tools = tools.optional(),
    skills = skills?.map { CreateAgentRequestSkillsItem(it.name, it.instructions) }.optional(),
    sharedSkills = sharedSkills.optional(), sharedMemory = sharedMemory.optional(),
    mcpServers = mcpServers.optional(), delegates = delegates.optional(),
    inference = inference?.let {
        CreateAgentRequestInference(OptionalField.Present(it.thinkingLevel), it.temperature.optional(), it.maxOutputTokens?.toLong().optional())
    }.optional(),
)

internal fun dev.crumble.android.push.PushRegistration.wire() = PutPushDeviceRequest(
    platform, transport, token, environment, bundleId.optional(), enabled,
)

internal fun AgentPreview.domain() = dev.crumble.android.model.Agent(
    id = id, name = name, description = description, instructions = instructions,
    avatar = avatar, enabled = enabled, provider = provider, model = model,
    tools = tools, delegates = delegates,
    skills = skills.map { dev.crumble.android.model.Skill(name = it.name, instructions = it.instructions) },
    sharedSkills = sharedSkills, sharedMemory = sharedMemory, mcpServers = mcpServers,
    inference = inference?.let { dev.crumble.android.model.Inference(it.thinkingLevel ?: "off", it.temperature, it.maxOutputTokens?.toInt()) },
    lastMessage = lastMessage,
)

internal fun dev.crumble.android.generated.Agent.domain() = dev.crumble.android.model.Agent(
    id = id, name = name, description = description, instructions = instructions,
    avatar = avatar, enabled = enabled, provider = provider, model = model,
    tools = tools, delegates = delegates,
    skills = skills.map { dev.crumble.android.model.Skill(name = it.name, instructions = it.instructions) },
    sharedSkills = sharedSkills, sharedMemory = sharedMemory, mcpServers = mcpServers,
    inference = inference?.let { dev.crumble.android.model.Inference(it.thinkingLevel ?: "off", it.temperature, it.maxOutputTokens?.toInt()) },
)

internal fun TranscriptMessage.domain() = Message(
    id = id, entryId = entryId, role = role, content = JsonPrimitive(content), blocks = blocks,
    kind = kind, replyTo = replyTo, replyPreview = replyPreview,
    origin = origin?.let { dev.crumble.android.model.MessageOrigin(it.kind, it.name, it.agentId) },
    communication = communication?.let { dev.crumble.android.model.BotCommunication(it.executionId, it.fromAgentId, it.fromName, it.toAgentId, it.toName, it.status) },
    timestamp = timestamp?.toLong(), attachments = attachments?.map { dev.crumble.android.model.AttachmentInfo(it.name, it.mimeType, it.size.toInt(), it.kind) },
    requestId = requestId,
)

internal fun SnapshotEventPayloadMessagesItem.domain() = Message(
    id = id, entryId = entryId, role = role, content = JsonPrimitive(content), blocks = blocks,
    kind = kind, replyTo = replyTo, replyPreview = replyPreview,
    origin = origin?.let { dev.crumble.android.model.MessageOrigin(it.kind, it.name, it.agentId) },
    communication = communication?.let { dev.crumble.android.model.BotCommunication(it.executionId, it.fromAgentId, it.fromName, it.toAgentId, it.toName, it.status) },
    timestamp = timestamp?.toLong(), attachments = attachments?.map { dev.crumble.android.model.AttachmentInfo(it.name, it.mimeType, it.size.toInt(), it.kind) },
    requestId = requestId,
)

internal fun dev.crumble.android.generated.ChatSnapshot.domain() = dev.crumble.android.model.ChatSnapshot(
    messages = messages.map { it.domain() }, status = status, partial = partial, error = error,
    agentId = agentId, hasMore = hasMore, oldestEntryId = oldestEntryId,
)

internal fun SnapshotEventPayload.domain() = dev.crumble.android.model.ChatSnapshot(
    messages = messages.map { it.domain() }, status = status, partial = partial, error = error,
    agentId = agentId, hasMore = hasMore, oldestEntryId = oldestEntryId,
)

internal fun TranscriptPage.domain() = ChatHistoryResponse(messages.map { it.domain() }, hasMore, oldestEntryId)
internal fun dev.crumble.android.generated.Memory.domain() = dev.crumble.android.model.Memory(id, content, createdAt)
internal fun dev.crumble.android.generated.Skill.domain() = SharedSkill(id, name, instructions)
internal fun ListedSkill.domain(): SharedSkill = when (this) {
    is ListedSkill.Variant1 -> SharedSkill(value.id, value.name, value.instructions, value.source)
    is ListedSkill.Variant2 -> SharedSkill(value.id, value.name, value.instructions, value.source, value.path)
}
internal fun dev.crumble.android.generated.Schedule.domain() = dev.crumble.android.model.Schedule(
    id, agentId, name, cron, prompt, timezone, runAt, enabled, createdByAgentId,
)
internal fun dev.crumble.android.generated.Execution.domain() = dev.crumble.android.model.Execution(
    id, agentId, conversationId, status, prompt, error, createdAt, updatedAt,
)
internal fun ActivityFeed.domain() = ActivityFeedResponse(activities.map {
    ActivityRecord(it.id, it.agentId, it.agentName, it.entryId, it.timestamp?.toLong(), it.kind, JsonObject(it.details))
}, hasMore)
internal fun Provider.domain() = ProviderStatus(provider, name, configured)
internal fun Model.domain() = ModelSummary(provider, id, name, contextWindow.toInt(), reasoning)
internal fun McpServer.domain() = MCPServer(id, name, url, enabled, agentId)
internal fun McpServerStatus.domain() = MCPServer(id, name, url, enabled, agentId, authenticated, auth)
internal fun McpOAuthStart.domain(): OAuthStart = when (this) {
    is McpOAuthStart.Variant1 -> OAuthStart(authorizationUrl = value.authorizationUrl)
    is McpOAuthStart.Variant2 -> OAuthStart(authorized = value.authorized)
}
internal fun PushStatus.domain() = PushInfo(serverId, mapOf("apns" to transports.apns, "fcm" to transports.fcm))
internal fun PushDeviceRegistration.domain() = PushRegistrationResult(serverId, remoteDelivery)
internal fun ActivityEventPayload.domain(): dev.crumble.android.model.ActivityEvent = when (this) {
    is ActivityEventPayload.Variant1 -> dev.crumble.android.model.ActivityEvent(
        type = value.type, agentId = value.agentId, agentName = value.agentName,
        entryId = value.entryId, origin = value.origin, preview = value.preview,
        eventId = value.eventId, serverId = value.serverId, createdAt = value.createdAt,
    )
    is ActivityEventPayload.Variant2 -> dev.crumble.android.model.ActivityEvent(
        type = value.type, agentId = value.agentId, agentName = value.agentName, lastMessage = value.lastMessage,
    )
}
