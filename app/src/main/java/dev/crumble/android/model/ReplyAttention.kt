package dev.crumble.android.model

import dev.crumble.android.generated.TranscriptMessageReplyReferencesItemSurface
import dev.crumble.android.push.ChatAttentionChat

sealed interface DisplayedReplySurface {
    data object Text : DisplayedReplySurface
    data class ToolCard(val toolCallId: String) : DisplayedReplySurface
    data class HeldAction(val actionId: String) : DisplayedReplySurface
}

fun Route?.attentionChat(): ChatAttentionChat? = when (this) {
    is Route.AgentChat -> ChatAttentionChat("bot", id)
    is Route.GroupChat -> ChatAttentionChat("group", id)
    else -> null
}

fun Message.attentionEventIDs(surface: DisplayedReplySurface): List<String> = replyReferences.filter { reference ->
    when (val expected = reference.surface) {
        is TranscriptMessageReplyReferencesItemSurface.Variant1 -> surface == DisplayedReplySurface.Text
        is TranscriptMessageReplyReferencesItemSurface.Variant2 -> surface is DisplayedReplySurface.ToolCard && surface.toolCallId == expected.value.toolCallId
        is TranscriptMessageReplyReferencesItemSurface.Variant3 -> surface is DisplayedReplySurface.HeldAction && surface.actionId == expected.value.actionId
    }
}.map { it.eventId }.distinct()

fun AppModel.replyCardReady(message: Message): Boolean = when {
    message.heldAction != null -> heldActions.any { it.id == message.heldAction.id }
    message.botRequest != null -> botRequests.any { it.id == message.botRequest.id }
    message.callRequest != null -> callRequests.any { it.id == message.callRequest.id }
    message.credentialRequest != null -> {
        val ref = message.credentialRequest
        val list = botCredentials[selectedAgentID]
        secretRequestState(
            list?.requests?.firstOrNull { ref.answers(it.name, it.requestedAt) }, list != null,
            list?.credentials?.any { it.name == ref.name } == true,
            credentialReadKey(ref) in freshSecretRequests, decisionsFailed,
        ).isReady()
    }
    message.loginRequest != null -> {
        val ref = message.loginRequest
        val list = botLogins[selectedAgentID]
        secretRequestState(
            list?.requests?.firstOrNull { ref.answers(it.name, it.requestedAt) }, list != null,
            list?.logins?.any { it.name == ref.name } == true,
            loginReadKey(ref) in freshSecretRequests, decisionsFailed,
        ).isReady()
    }
    else -> false
}

private fun SecretRequestState<*>.isReady() = this is SecretRequestState.Waiting || this == SecretRequestState.Saved || this == SecretRequestState.Declined
