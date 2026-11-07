package dev.crumble.android.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.BotDesktop
import dev.crumble.android.model.DESKTOP_BOT
import dev.crumble.android.model.DESKTOP_OWNER
import dev.crumble.android.model.DesktopKey
import dev.crumble.android.model.DesktopLink
import dev.crumble.android.model.DesktopPress
import dev.crumble.android.model.desktopKeyScript
import dev.crumble.android.model.desktopPresses
import dev.crumble.android.model.press

private const val DISCONNECTED_TITLE = "Bot desktop (disconnected)"

@Composable
fun BotDesktopDialog(model: AppModel, desktop: BotDesktop) {
    val bot = model.agents.firstOrNull { it.id == desktop.agentID }
    var view by remember { mutableStateOf<WebView?>(null) }
    JobDialog(
        onClose = { model.closeBotDesktop() },
        title = {
            Column {
                Text(
                    text = bot?.let { "${it.name}'s desktop" } ?: "Bot desktop",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                desktop.control?.let {
                    Text(
                        text = if (desktop.owner) "You have control" else "The bot has control",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        actions = { DesktopControls(model, desktop) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (desktop.takeError.isNotEmpty()) {
                Text(
                    text = desktop.takeError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
                ViewerPane(model, desktop.viewer) { view = it }
            }
            if (desktop.owner) DesktopKeyboard(view)
        }
    }
}

@Composable
private fun DesktopControls(model: AppModel, desktop: BotDesktop) {
    if (desktop.taking != null) {
        CircularProgressIndicator(modifier = Modifier.padding(horizontal = 8.dp).size(20.dp), strokeWidth = 2.dp)
    }
    IconButton(onClick = { model.reconnectDesktopViewer() }, enabled = !desktop.viewer.connecting) {
        Icon(Icons.Default.Refresh, contentDescription = "Reconnect")
    }
    val enabled = desktop.control != null && desktop.taking == null
    if (desktop.owner) {
        OutlinedButton(onClick = { model.setDesktopHolder(DESKTOP_BOT) }, enabled = enabled) { Text("Hand back") }
    } else {
        Button(onClick = { model.setDesktopHolder(DESKTOP_OWNER) }, enabled = enabled) { Text("Take control") }
    }
}

@Composable
private fun ViewerPane(model: AppModel, viewer: DesktopLink, onView: (WebView?) -> Unit) {
    when {
        viewer.url != null -> DesktopPage(viewer.url, Modifier.fillMaxSize(), model::desktopViewerLost, onView)
        viewer.connecting -> Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
            Text("Connecting", style = MaterialTheme.typography.bodySmall, color = Color.White)
        }
        viewer.error.isNotEmpty() -> Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Can't show the bot desktop", style = MaterialTheme.typography.titleSmall)
                Text(viewer.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** noVNC draws on a canvas, so soft keyboard text comes from a native field and reaches the canvas as key events. */
@Composable
private fun DesktopKeyboard(view: WebView?) {
    var text by remember { mutableStateOf("") }
    var ctrl by remember { mutableStateOf(false) }
    fun send(presses: List<DesktopPress>, onSent: () -> Unit = {}) {
        view?.evaluateJavascript(desktopKeyScript(presses, ctrl)) { result ->
            if (result == "true") {
                ctrl = false
                onSent()
            }
        }
    }
    fun sendText() {
        if (text.isNotEmpty()) send(desktopPresses(text)) { text = "" }
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(selected = ctrl, onClick = { ctrl = !ctrl }, label = { Text("Ctrl") })
            for (key in DesktopKey.entries) {
                OutlinedButton(onClick = { send(listOf(key.press())) }, enabled = view != null) { Text(key.label) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Type on the desktop") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendText() }),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { sendText() }, enabled = view != null && text.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send to the desktop")
            }
        }
        Text(
            text = "The bot's desktop job waits while you have control. Hand back when you're done.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Tap to click, touch and hold to right-click, and drag two fingers to scroll.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Shows the bot's machine state and a live view, which opens the full desktop. */
@Composable
fun DesktopPreviewSection(model: AppModel, agentID: String) {
    DisposableEffect(agentID) {
        model.watchDesktopPreview(agentID)
        onDispose { model.stopDesktopPreview() }
    }
    val preview = model.desktopPreview?.takeIf { it.agentID == agentID } ?: return
    Column(modifier = Modifier.padding(horizontal = 16.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GroupLabel("Desktop")
        val tile = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black, RoundedCornerShape(12.dp))
        when {
            preview.running == null -> Box(tile, contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
            }
            !preview.awake -> Column(
                modifier = tile.clickable { model.wakeDesktop() },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                Icon(Icons.Default.Bedtime, contentDescription = null, tint = Color.White.copy(alpha = 0.6f))
                Text("Sleeping", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
                Text("Tap to wake", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.4f))
            }
            else -> Box(tile) {
                val url = preview.viewer.url
                if (url != null) DesktopPage(url, Modifier.fillMaxSize(), model::desktopViewerLost)
                if (preview.viewer.connecting) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(20.dp), strokeWidth = 2.dp, color = Color.White)
                }
                if (preview.viewer.error.isNotEmpty()) {
                    Text(
                        text = preview.viewer.error,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(12.dp),
                    )
                }
                // Sits over the page, whose touches would otherwise reach the desktop while the owner holds control.
                Box(Modifier.matchParentSize().clickable(onClickLabel = "Open the bot desktop") { model.openBotDesktop(agentID) })
            }
        }
    }
}

// The viewer is the server's own noVNC page and needs scripts. It gets no JavaScript interfaces and no file access.
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DesktopPage(url: String, modifier: Modifier, onLost: (String) -> Unit, onView: (WebView?) -> Unit = {}) {
    val lost by rememberUpdatedState(onLost)
    val attach by rememberUpdatedState(onView)
    // A new link needs a new page: reloading would spend a link that already ran.
    key(url) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(AndroidColor.BLACK)
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            request.url.toString() != url

                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                            if (request.isForMainFrame) lost(url)
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) lost(url)
                        }

                        // Unhandled, a dead renderer takes the whole app down.
                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            lost(url)
                            return true
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        // The page's only signal that its connection ended.
                        override fun onReceivedTitle(view: WebView, title: String?) {
                            if (title == DISCONNECTED_TITLE) lost(url)
                        }
                    }
                    attach(this)
                    loadUrl(url)
                }
            },
            onRelease = {
                attach(null)
                it.destroy()
            },
        )
    }
}
