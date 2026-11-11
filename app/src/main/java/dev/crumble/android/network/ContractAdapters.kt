package dev.crumble.android.network

import dev.crumble.android.generated.*
import dev.crumble.android.model.*
import dev.crumble.android.push.PushInfo
import dev.crumble.android.push.PushRegistrationResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

private fun <T> T?.optional(): OptionalField<T> =
    if (this == null) OptionalField.Absent else OptionalField.Present(this)

internal fun BotAvatarDefinition?.wire(): CreateAgentRequestAvatar? =
    this?.let { CrumblePatchJson.decodeFromJsonElement(CrumblePatchJson.encodeToJsonElement(it)) }

internal fun BotRequestProposalAvatar?.domain(): BotAvatarDefinition? =
    this?.let { CrumblePatchJson.decodeFromJsonElement(CrumblePatchJson.encodeToJsonElement(it)) }

// The server replaces the proposal whole, so fields the editor doesn't show must travel with the edits.
internal fun BotRequestProposal.edited(input: dev.crumble.android.model.Agent): Map<String, JsonElement> =
    (CrumbleJson.encodeToJsonElement(this) as JsonObject) +
        (CrumblePatchJson.encodeToJsonElement(input.wire()) as JsonObject) +
        mapOf(
            "description" to JsonPrimitive(input.description.orEmpty()),
            "instructions" to JsonPrimitive(input.instructions.orEmpty()),
            "webSearchProvider" to JsonPrimitive(input.webSearchProvider),
            "desktopModel" to CrumblePatchJson.encodeToJsonElement(input.desktopModel),
        )

internal fun dev.crumble.android.model.Agent.wire() = CreateAgentRequest(
    name = name, avatar = avatar.wire().optional(), description = description.optional(),
    instructions = instructions.optional(), provider = provider.optional(), model = model.optional(),
    enabled = OptionalField.Present(enabled), tools = tools.optional(),
    webSearchProvider = webSearchProvider.optional(),
    desktopModel = desktopModel?.let { CreateAgentRequestDesktopModel(it.provider, it.model) }.optional(),
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
    webSearchProvider = webSearchProvider, desktopModel = desktopModel?.let { ModelChoice(it.provider, it.model) },
    tools = tools, delegates = delegates,
    skills = skills.map { dev.crumble.android.model.Skill(name = it.name, instructions = it.instructions) },
    sharedSkills = sharedSkills, sharedMemory = sharedMemory, mcpServers = mcpServers,
    inference = inference?.let { dev.crumble.android.model.Inference(it.thinkingLevel ?: "off", it.temperature, it.maxOutputTokens?.toInt()) },
    lastMessage = lastMessage,
)

internal fun dev.crumble.android.generated.Agent.domain() = dev.crumble.android.model.Agent(
    id = id, name = name, description = description, instructions = instructions,
    avatar = avatar, enabled = enabled, provider = provider, model = model,
    webSearchProvider = webSearchProvider, desktopModel = desktopModel?.let { ModelChoice(it.provider, it.model) },
    tools = tools, delegates = delegates,
    skills = skills.map { dev.crumble.android.model.Skill(name = it.name, instructions = it.instructions) },
    sharedSkills = sharedSkills, sharedMemory = sharedMemory, mcpServers = mcpServers,
    inference = inference?.let { dev.crumble.android.model.Inference(it.thinkingLevel ?: "off", it.temperature, it.maxOutputTokens?.toInt()) },
)

private fun botRequestRef(toolName: String?, isError: Boolean?, details: JsonElement?): BotRequestRef? {
    if (toolName != "request_bot" || isError == true) return null
    val request = details as? JsonObject ?: return null
    val id = (request["id"] as? JsonPrimitive)?.contentOrNull ?: return null
    val name = ((request["proposal"] as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull ?: return null
    return BotRequestRef(id, name)
}

private fun callRequestRef(toolName: String?, isError: Boolean?, details: JsonElement?): CallRequestRef? {
    if (toolName != "request_call" || isError == true) return null
    val request = details as? JsonObject ?: return null
    val id = (request["id"] as? JsonPrimitive)?.contentOrNull ?: return null
    val callee = (request["callee"] as? JsonPrimitive)?.contentOrNull ?: return null
    return CallRequestRef(id, callee)
}

private fun secretRequestRef(tool: String, toolName: String?, isError: Boolean?, details: JsonElement?): SecretRequestRef? {
    if (toolName != tool || isError == true) return null
    val request = details as? JsonObject ?: return null
    val name = (request["name"] as? JsonPrimitive)?.contentOrNull?.nilIfEmpty ?: return null
    val hosts = (request["hosts"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    return SecretRequestRef(name, hosts, (request["requestedAt"] as? JsonPrimitive)?.contentOrNull?.nilIfEmpty)
}

internal fun TranscriptMessage.domain() = Message(
    id = id, entryId = entryId, role = role, content = JsonPrimitive(content), blocks = blocks,
    kind = kind, replyTo = replyTo, replyPreview = replyPreview,
    origin = origin?.let { dev.crumble.android.model.MessageOrigin(it.kind, it.name, it.agentId, it.source, it.via) },
    communication = communication?.let { dev.crumble.android.model.BotCommunication(it.executionId, it.fromAgentId, it.fromName, it.toAgentId, it.toName, it.status) },
    timestamp = timestamp?.toLong(), attachments = attachments?.map { dev.crumble.android.model.AttachmentInfo(it.name, it.mimeType, it.size.toInt(), it.kind) },
    requestId = requestId,
    heldAction = heldAction?.let { HeldActionRef(it.id, it.job) },
    jobQuestion = jobQuestion?.let { JobQuestionRef(it.id, it.job) },
    call = call?.let { MessageCall(it.callId, it.direction, it.callee) },
    botRequest = botRequestRef(toolName, isError, toolDetails),
    callRequest = callRequestRef(toolName, isError, toolDetails),
    credentialRequest = secretRequestRef("request_credential", toolName, isError, toolDetails),
    loginRequest = secretRequestRef("request_login", toolName, isError, toolDetails),
    toolName = toolName, toolCallId = toolCallId, isError = isError, toolDetails = toolDetails,
    presentation = presentation,
)

internal fun SnapshotEventPayloadMessagesItem.domain() = Message(
    id = id, entryId = entryId, role = role, content = JsonPrimitive(content), blocks = blocks,
    kind = kind, replyTo = replyTo, replyPreview = replyPreview,
    origin = origin?.let { dev.crumble.android.model.MessageOrigin(it.kind, it.name, it.agentId, it.source, it.via) },
    communication = communication?.let { dev.crumble.android.model.BotCommunication(it.executionId, it.fromAgentId, it.fromName, it.toAgentId, it.toName, it.status) },
    timestamp = timestamp?.toLong(), attachments = attachments?.map { dev.crumble.android.model.AttachmentInfo(it.name, it.mimeType, it.size.toInt(), it.kind) },
    requestId = requestId,
    heldAction = heldAction?.let { HeldActionRef(it.id, it.job) },
    jobQuestion = jobQuestion?.let { JobQuestionRef(it.id, it.job) },
    call = call?.let { MessageCall(it.callId, it.direction, it.callee) },
    botRequest = botRequestRef(toolName, isError, toolDetails),
    callRequest = callRequestRef(toolName, isError, toolDetails),
    credentialRequest = secretRequestRef("request_credential", toolName, isError, toolDetails),
    loginRequest = secretRequestRef("request_login", toolName, isError, toolDetails),
    toolName = toolName, toolCallId = toolCallId, isError = isError, toolDetails = toolDetails,
    presentation = presentation,
)

internal fun dev.crumble.android.generated.ChatSnapshot.domain() = dev.crumble.android.model.ChatSnapshot(
    messages = messages.map { it.domain() }, status = status, working = working, partial = partial, error = error,
    agentId = agentId, hasMore = hasMore, oldestEntryId = oldestEntryId,
)

internal fun SnapshotEventPayload.domain() = dev.crumble.android.model.ChatSnapshot(
    messages = messages.map { it.domain() }, status = status, working = working, partial = partial, error = error,
    agentId = agentId, hasMore = hasMore, oldestEntryId = oldestEntryId,
)

internal fun GroupChatSnapshot.domain() = GroupSnapshot(messages.map { it.domain() }, hasMore, oldestEntryId, groupChat, activity)

internal fun TranscriptPage.domain() = ChatHistoryResponse(messages.map { it.domain() }, hasMore, oldestEntryId)
internal fun dev.crumble.android.generated.JobHistoryPage.domain() = dev.crumble.android.model.JobHistoryPage(
    messages.map { it.domain() }, items, hasMore, oldestEntryId,
)
internal fun dev.crumble.android.generated.Memory.domain() = dev.crumble.android.model.Memory(id, content, createdAt, agentId, source, updatedAt)
internal fun dev.crumble.android.generated.Skill.domain() = SharedSkill(id, name, instructions)
internal fun ListedSkill.domain(): SharedSkill = when (this) {
    is ListedSkill.Variant1 -> SharedSkill(value.id, value.name, value.instructions, value.source, description = value.description, origin = value.origin, update = value.update)
    is ListedSkill.Variant2 -> SharedSkill(value.id, value.name, value.instructions, value.source, value.path, value.description)
}
internal fun dev.crumble.android.generated.Schedule.domain() = dev.crumble.android.model.Schedule(
    id, agentId, name, cron, prompt, timezone, runAt, enabled, createdByAgentId,
    kind, command, timeoutSeconds, notify, eventSourceId, nextRunAt, nextRunLabel, description, timing,
)
internal fun dev.crumble.android.generated.Execution.domain() = dev.crumble.android.model.Execution(
    id, agentId, conversationId, status, prompt, error, createdAt, updatedAt, scheduleId,
)
internal fun ActivityFeed.domain() = ActivityFeedResponse(activities.map {
    when (it) {
        is ActivityFeedActivitiesItem.Variant1 -> it.value.run { ActivityRecord(id, agentId, agentName, entryId, timestamp?.toLong(), kind, JsonObject(details)) }
        is ActivityFeedActivitiesItem.Variant2 -> it.value.run {
            ActivityRecord(id, agentId.orEmpty(), agentName.orEmpty(), null, timestamp.toLong(), kind, CrumbleJson.encodeToJsonElement(LearnedChange.serializer(), change), change)
        }
    }
}, hasMore, nextCursor)
internal fun Provider.domain() = ProviderStatus(provider, name, configured)
internal fun Model.domain() = ModelSummary(provider, id, name, contextWindow.toInt(), reasoning, thinkingLevels)
internal fun CodingJob.domain() = JobSummary(JobKind.coding, name, status, model?.model, usage, question)
internal fun DesktopJob.domain() = JobSummary(
    JobKind.desktop, name, status, model?.model, usage,
    question?.let { CodingJobQuestion(it.id, it.text, it.askedAt, it.options, it.multiple) },
)
internal fun McpServerStatus.domain() = MCPServer(id, name, url, enabled, agentId)
internal fun PushStatus.domain() = PushInfo(serverId, mapOf("apns" to transports.apns, "fcm" to transports.fcm))
internal fun PushDeviceRegistration.domain() = PushRegistrationResult(serverId, remoteDelivery)
internal fun ActivityEventPayload.domain(): dev.crumble.android.model.ActivityEvent = when (this) {
    is ActivityEventPayload.Variant1 -> dev.crumble.android.model.ActivityEvent(
        type = value.type, agentId = value.agentId, agentName = value.agentName,
        entryId = value.entryId, origin = value.origin, preview = value.preview,
        eventId = value.eventId, serverId = value.serverId, createdAt = value.createdAt,
        groupId = value.groupId, groupName = value.groupName,
    )
    is ActivityEventPayload.Variant2 -> dev.crumble.android.model.ActivityEvent(
        type = value.type, agentId = value.agentId, agentName = value.agentName, lastMessage = value.lastMessage,
    )
}
