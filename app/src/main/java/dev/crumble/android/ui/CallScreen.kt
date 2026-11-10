package dev.crumble.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.crumble.android.generated.CallRequest
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.CallTranscript
import dev.crumble.android.model.callStateLabel

@Composable
private fun CallTitle(request: CallRequest?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            Text(
                request?.callee ?: "Call",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (request != null) {
                Text(
                    request.number,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CallLineView(speaker: String, mine: Boolean, text: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(speaker, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (mine) cardColor else MaterialTheme.colorScheme.surface,
            border = if (mine) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

/** A call request's call over the chat: its goal, then what was said, live while it goes on. */
@Composable
fun CallDialog(model: AppModel, transcript: CallTranscript) {
    val request = model.callRequests.firstOrNull { it.id == transcript.requestID }
    val botName = model.selectedAgent?.name ?: "Bot"
    val calling = request?.status == "calling"
    JobDialog(
        onClose = { model.closeCallTranscript() },
        title = { CallTitle(request) },
        actions = {
            if (request != null) {
                Text(
                    callStateLabel(request),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 16.dp),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (request == null) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                return@JobDialog
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                GroupLabel("Goal")
                Text(request.goal, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))
            }
            if (request.outcome == "failed" && !request.error.isNullOrEmpty()) {
                Text(request.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CallLineView(botName, mine = true, text = request.opening)
                for (line in transcript.lines) {
                    CallLineView(if (line.fromCallee) request.callee else botName, mine = !line.fromCallee, text = line.text)
                }
                if (!transcript.loaded) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                if (transcript.failed) {
                    Text("Couldn't load the transcript.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (transcript.loaded && !transcript.failed && !calling && transcript.lines.isEmpty()) {
                    Text("No transcript.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (calling) {
                    Text("On the call…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
