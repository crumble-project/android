package dev.crumble.android.model

import dev.crumble.android.generated.CallRequest

private val OUTCOME_LABELS = mapOf(
    "answered" to "Answered",
    "unanswered" to "Not answered",
    "busy" to "Busy",
    "failed" to "Failed",
)

/** The state a call request shows once the owner has decided; an unknown outcome reads as ended. */
fun callStateLabel(request: CallRequest): String = when {
    request.status == "declined" -> "Declined"
    request.status == "calling" -> "Calling"
    else -> OUTCOME_LABELS[request.outcome] ?: "Ended"
}

private val ENDED_CALL = Regex("^voice-call:([^:]+):ended$")
private val SWEPT_REQUEST = Regex("^call-request:([^:]+)$")

/** The call request an outside call's notice belongs to; an owner call has none. */
fun callRequestForNotice(message: Message, requests: List<CallRequest>): CallRequest? {
    if (message.role != "user") return null
    val kind = message.origin?.kind
    if (kind != "call" && kind != "call_request") return null
    val requestID = message.requestId.orEmpty()
    val callID = ENDED_CALL.matchEntire(requestID)?.groupValues?.get(1)
    val swept = SWEPT_REQUEST.matchEntire(requestID)?.groupValues?.get(1)
    return requests.firstOrNull { (callID != null && it.callId == callID) || (swept != null && it.id == swept) }
}

/** What the notice says after the callee's name, such as "ended after 2 minutes". */
fun callNoticeDetail(message: Message, request: CallRequest): String {
    val lead = "Call to ${request.callee} "
    return if (message.text.startsWith(lead)) message.text.removePrefix(lead) else message.text
}

/** One spoken line of a call: the callee's or the bot's. */
data class CallLine(val id: String, val fromCallee: Boolean, val text: String)

fun callLines(messages: List<Message>): List<CallLine> = messages.mapNotNull { message ->
    when {
        message.role == "user" -> CallLine(message.id, true, message.text)
        message.role == "assistant" && message.text.isNotBlank() -> CallLine(message.id, false, message.text)
        else -> null
    }
}

/** The open call's transcript for one bot; `loaded` stays false until the first read settles. */
data class CallTranscript(
    val agentID: String,
    val requestID: String,
    val lines: List<CallLine> = emptyList(),
    val loaded: Boolean = false,
    val failed: Boolean = false,
)
