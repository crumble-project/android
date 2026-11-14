package dev.crumble.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.DisplayedReplySurface
import dev.crumble.android.model.Message
import dev.crumble.android.model.attentionChat
import dev.crumble.android.model.attentionEventIDs
import dev.crumble.android.network.APIClient
import dev.crumble.android.push.ChatAttentionChat

private class AttentionContext(val api: APIClient, val viewId: String, val chat: ChatAttentionChat) {
    var viewport by mutableStateOf(Rect.Zero)
}

internal val LocalAttentionWindowID = staticCompositionLocalOf<String?> { null }
private val LocalAttention = staticCompositionLocalOf<AttentionContext?> { null }

@Composable
internal fun AttentionChat(model: AppModel, chat: ChatAttentionChat, ready: Boolean, content: @Composable () -> Unit) {
    val api = model.client
    val windowID = LocalAttentionWindowID.current
    var context by remember(model, api, chat, ready, windowID) { mutableStateOf<AttentionContext?>(null) }
    DisposableEffect(model, api, chat, ready, windowID) {
        val owner = windowID != null && model.attentionWindowID == windowID
        if (owner) model.updateChatAttention()
        val attached = if (owner && ready && api != null && api.token.isNotEmpty() && model.route.attentionChat() == chat) {
            AttentionContext(api, model.chatAttention.viewId, chat).also {
                model.chatAttention.setDisplayedChat(chat)
                context = it
            }
        } else null
        onDispose {
            if (model.client === api && model.attentionWindowID == windowID && attached?.viewId == model.chatAttention.viewId) {
                model.updateChatAttention()
                if (model.route.attentionChat() == chat) model.chatAttention.setDisplayedChat(null)
            }
        }
    }
    CompositionLocalProvider(LocalAttention provides context, content = content)
}

@Composable
internal fun attentionViewport(): Modifier {
    val context = LocalAttention.current ?: return Modifier
    return Modifier.onGloballyPositioned { context.viewport = it.boundsInWindow() }
}

internal fun replySurfaceVisible(ready: Boolean, bounds: Rect, viewport: Rect): Boolean =
    ready && bounds.width > 0 && bounds.height > 0 && viewport.width > 0 && viewport.height > 0 && bounds.overlaps(viewport)

@Composable
internal fun ReplyAttention(
    model: AppModel,
    message: Message,
    surface: DisplayedReplySurface,
    ready: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(replyAttentionModifier(model, message, surface, ready)) { content() }
}

@Composable
internal fun replyAttentionModifier(model: AppModel, message: Message, surface: DisplayedReplySurface, ready: Boolean = true): Modifier {
    val context = LocalAttention.current ?: return Modifier
    val ids = message.attentionEventIDs(surface)
    if (ids.isEmpty()) return Modifier
    var bounds by remember(context, message.id, surface) { mutableStateOf(Rect.Zero) }
    val viewport = context.viewport
    LaunchedEffect(context, ids, ready, bounds, viewport, model.appActive, model.route, model.client) {
        if (model.appActive && model.client === context.api && model.route.attentionChat() == context.chat && replySurfaceVisible(ready, bounds, viewport)) {
            model.chatAttention.displayed(context.api, context.viewId, context.chat, ids)
        }
    }
    return Modifier.onGloballyPositioned { bounds = it.boundsInWindow() }
}
