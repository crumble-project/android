package dev.crumble.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.crumble.android.generated.BotRequest
import dev.crumble.android.generated.CodingJobQuestion
import dev.crumble.android.generated.CredentialRequest
import dev.crumble.android.generated.HeldAction
import dev.crumble.android.generated.LoginRequest
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.BotRequestRef
import dev.crumble.android.model.HeldActionRef
import dev.crumble.android.model.JobQuestionRef
import dev.crumble.android.model.SecretRequestRef
import dev.crumble.android.model.SecretRequestState
import dev.crumble.android.model.credentialReadKey
import dev.crumble.android.model.loginReadKey
import dev.crumble.android.model.secretKey
import dev.crumble.android.model.secretRequestState

private val HELD_OUTCOMES = mapOf("approved" to "Approved", "declined" to "Declined", "expired" to "Expired")

@Composable
private fun DecisionSurface(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            content()
        }
    }
}

@Composable
private fun DecisionHeader(icon: ImageVector, caption: String, title: String, detail: String? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
            if (!detail.isNullOrEmpty()) {
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SettledCard(icon: ImageVector, title: String, outcome: String, action: (@Composable () -> Unit)? = null) {
    DecisionSurface {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(outcome, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            action?.invoke()
        }
    }
}

@Composable
private fun DecisionError(model: AppModel, id: String) {
    val error = model.decisionErrors[id] ?: return
    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun DecisionPending(model: AppModel, id: String) {
    if (id in model.decidingIDs) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
    }
}

private fun AppModel.unresolvedOutcome() = if (decisionsLoaded || decisionsFailed) "Unavailable" else "Loading…"

@Composable
fun HeldActionCard(model: AppModel, held: HeldActionRef, title: String) {
    val action = model.heldActions.firstOrNull { it.id == held.id }
    when {
        action == null -> SettledCard(Icons.Default.PanTool, title, model.unresolvedOutcome())
        action.status != "pending" -> SettledCard(Icons.Default.PanTool, title, HELD_OUTCOMES[action.status] ?: action.status)
        else -> HeldActionPending(model, action)
    }
}

@Composable
private fun HeldActionPending(model: AppModel, action: HeldAction) {
    var declining by remember(action.id) { mutableStateOf(false) }
    var reason by remember(action.id) { mutableStateOf("") }
    var checked by remember(action.id) { mutableStateOf(false) }
    val busy = action.id in model.decidingIDs
    DecisionSurface {
        DecisionHeader(Icons.Default.PanTool, "${action.job} is waiting for your approval", action.action, action.page)
        Text(action.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = if (checked) "Hide your messages" else "Checked against your messages",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { checked = !checked },
        )
        if (checked) {
            if (action.ownerMessages.isEmpty()) {
                Text("None: the job had no message from you.", style = MaterialTheme.typography.bodySmall)
            }
            for (message in action.ownerMessages) {
                Text(
                    text = (if (message.from == "automation") "Automation: " else "") + message.text,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        DecisionError(model, action.id)
        if (declining) {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text("Reason (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            DecisionPending(model, action.id)
            TextButton(
                enabled = !busy,
                onClick = { if (declining) model.decideHeldAction(action.id, approve = false, reason = reason) else declining = true },
            ) { Text("Decline") }
            if (!declining) {
                Button(enabled = !busy, onClick = { model.decideHeldAction(action.id, approve = true) }) { Text("Approve") }
            }
        }
    }
}

@Composable
fun JobQuestionCard(model: AppModel, ref: JobQuestionRef, title: String) {
    val waiting = model.codingJobs.firstOrNull { it.question?.id == ref.id }?.question
    if (waiting != null) {
        JobQuestionAnswer(model, ref.job, waiting)
    } else {
        val outcome = if (model.decisionsFailed) "Unavailable" else if (model.decisionsLoaded) "Answered" else "Loading…"
        SettledCard(Icons.Default.QuestionAnswer, title, outcome)
    }
}

@Composable
fun JobQuestionAnswer(model: AppModel, job: String, question: CodingJobQuestion) {
    var text by remember(question.id) { mutableStateOf("") }
    var picked by remember(question.id) { mutableStateOf<Set<String>>(emptySet()) }
    val multiple = question.multiple == true
    val options = question.options.orEmpty()
    val busy = question.id in model.decidingIDs
    // A multiple-choice answer lists the picked options, then anything written.
    val reply = listOf(options.filter { it in picked }.joinToString(", "), text.trim()).filter { it.isNotEmpty() }.joinToString("\n")
    DecisionSurface {
        DecisionHeader(Icons.Default.QuestionAnswer, "$job asks" + if (multiple) " · pick any" else "", question.text)
        for (option in options) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !busy) {
                        when {
                            !multiple -> model.answerJobQuestion(job, question.id, option)
                            option in picked -> picked = picked - option
                            else -> picked = picked + option
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (multiple) {
                    Checkbox(checked = option in picked, onCheckedChange = null, modifier = Modifier.padding(10.dp))
                } else {
                    RadioButton(selected = false, onClick = null, modifier = Modifier.padding(10.dp))
                }
                Text(option, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                enabled = !busy,
                label = { Text(if (options.isEmpty()) "Your answer…" else "Something else…") },
                modifier = Modifier.weight(1f),
            )
            if (busy) {
                DecisionPending(model, question.id)
            } else {
                IconButton(enabled = reply.isNotEmpty(), onClick = { model.answerJobQuestion(job, question.id, reply) }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Answer")
                }
            }
        }
        DecisionError(model, question.id)
    }
}

@Composable
fun BotRequestCard(model: AppModel, ref: BotRequestRef) {
    val request = model.botRequests.firstOrNull { it.id == ref.id }
    when {
        request == null -> SettledCard(Icons.Default.Group, ref.name, model.unresolvedOutcome())
        request.status == "pending" -> BotRequestPending(model, request)
        else -> SettledCard(
            icon = Icons.Default.Group,
            title = request.proposal.name,
            outcome = if (request.status == "approved") "Approved" else "Declined",
            action = request.createdAgentId?.let { created ->
                { OutlinedButton(onClick = { model.selectAgent(created) }) { Text("Open Chat") } }
            },
        )
    }
}

@Composable
private fun BotRequestPending(model: AppModel, request: BotRequest) {
    val busy = request.id in model.decidingIDs
    val proposal = request.proposal
    var open by remember(request.id) { mutableStateOf(false) }
    DecisionSurface {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                DecisionHeader(Icons.Default.Group, "Wants to create a bot", proposal.name, proposal.description)
            }
            IconButton(enabled = !busy, onClick = { model.openBotRequestEditor(request.id) }) {
                Icon(Icons.Default.Edit, contentDescription = "Edit")
            }
        }
        Text(request.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = if (open) "Hide details" else "Show details",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { open = !open },
        )
        if (open) {
            val summary = listOfNotNull(proposal.provider, proposal.model).joinToString(" · ")
            if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.bodySmall)
            if (!proposal.tools.isNullOrEmpty()) Text("Tools: " + proposal.tools.joinToString(", "), style = MaterialTheme.typography.bodySmall)
            if (!proposal.instructions.isNullOrEmpty()) Text(proposal.instructions, style = MaterialTheme.typography.bodySmall)
        }
        DecisionError(model, request.id)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            DecisionPending(model, request.id)
            TextButton(enabled = !busy, onClick = { model.decideBotRequest(request.id, approve = false) }) { Text("Decline") }
            Button(enabled = !busy, onClick = { model.decideBotRequest(request.id, approve = true) }) { Text("Approve") }
        }
    }
}

@Composable
private fun RequestHosts(hosts: List<String>) {
    var expanded by remember(hosts) { mutableStateOf(false) }
    val more = hosts.size - 1
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = (if (expanded) hosts else hosts.take(1)).joinToString(" "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (more > 0) {
            Text(
                text = if (expanded) "Show less" else "$more more",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { expanded = !expanded },
            )
        }
    }
}

@Composable
private fun SecretRequestSettled(icon: ImageVector, title: String, state: SecretRequestState<*>) {
    val outcome = when (state) {
        SecretRequestState.Unavailable -> "Unavailable"
        SecretRequestState.Saved -> "Saved"
        SecretRequestState.Declined -> "Declined"
        else -> "Loading…"
    }
    SettledCard(icon, title, outcome)
}

@Composable
fun CredentialRequestCard(model: AppModel, ref: SecretRequestRef) {
    val botID = model.selectedAgentID
    val list = model.botCredentials[botID]
    val request = list?.requests?.firstOrNull { ref.answers(it.name, it.requestedAt) }
    val state = secretRequestState(
        request = request,
        listed = list != null,
        saved = list?.credentials?.any { it.name == ref.name } == true,
        fresh = credentialReadKey(ref) in model.freshSecretRequests,
        failed = model.decisionsFailed,
    )
    if (state is SecretRequestState.Waiting) {
        CredentialRequestPending(model, botID, state.request)
    } else {
        SecretRequestSettled(Icons.Default.Key, ref.name, state)
    }
}

@Composable
private fun CredentialRequestPending(model: AppModel, botID: String, request: CredentialRequest) {
    SecretRequestPending(
        model = model,
        key = secretKey("credential", botID, request.name),
        icon = Icons.Default.Key,
        caption = "Credential",
        name = request.name,
        hosts = request.hosts,
        addLabel = "Add credential",
        onDecline = { model.declineCredentialRequest(botID, request.name) },
        onAdd = { model.openCredentialForm(botID, request = request) },
    )
}

@Composable
fun LoginRequestCard(model: AppModel, ref: SecretRequestRef) {
    val botID = model.selectedAgentID
    val list = model.botLogins[botID]
    val request = list?.requests?.firstOrNull { ref.answers(it.name, it.requestedAt) }
    val state = secretRequestState(
        request = request,
        listed = list != null,
        saved = list?.logins?.any { it.name == ref.name } == true,
        fresh = loginReadKey(ref) in model.freshSecretRequests,
        failed = model.decisionsFailed,
    )
    if (state is SecretRequestState.Waiting) {
        LoginRequestPending(model, botID, state.request)
    } else {
        SecretRequestSettled(Icons.AutoMirrored.Filled.Login, ref.name, state)
    }
}

@Composable
private fun LoginRequestPending(model: AppModel, botID: String, request: LoginRequest) {
    SecretRequestPending(
        model = model,
        key = secretKey("login", botID, request.name),
        icon = Icons.AutoMirrored.Filled.Login,
        caption = "Login",
        name = request.name,
        hosts = request.hosts,
        addLabel = "Add login",
        onDecline = { model.declineLoginRequest(botID, request.name) },
        onAdd = { model.openLoginForm(botID, request = request) },
    )
}

@Composable
private fun SecretRequestPending(
    model: AppModel,
    key: String,
    icon: ImageVector,
    caption: String,
    name: String,
    hosts: List<String>,
    addLabel: String,
    onDecline: () -> Unit,
    onAdd: () -> Unit,
) {
    val busy = key in model.pendingMutations
    DecisionSurface {
        DecisionHeader(icon, caption, name)
        RequestHosts(hosts)
        DecisionError(model, key)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            TextButton(enabled = !busy, onClick = onDecline) { Text("Decline") }
            Button(enabled = !busy, onClick = onAdd) { Text(addLabel) }
        }
    }
}
