package dev.crumble.android.model

import dev.crumble.android.generated.DesktopControl
import kotlinx.serialization.json.JsonPrimitive

const val DESKTOP_OWNER = "owner"
const val DESKTOP_BOT = "bot"
const val DESKTOP_UNAVAILABLE = "The bot desktop is unavailable"
const val DESKTOP_HAND_BACK_FAILED = "Couldn't hand the bot desktop back. Open it and hand back again."

/** A viewer link works once, so losing the page means asking for a fresh one. */
data class DesktopLink(
    val url: String? = null,
    val connecting: Boolean = false,
    val error: String = "",
) {
    val idle: Boolean
        get() = url == null && !connecting && error.isEmpty()
}

/** The open bot desktop. `taking` is the holder asked for and not yet confirmed. */
data class BotDesktop(
    val agentID: String,
    val control: DesktopControl? = null,
    val taking: String? = null,
    val takeError: String = "",
    val viewer: DesktopLink = DesktopLink(),
) {
    val owner: Boolean
        get() = control?.holder == DESKTOP_OWNER
}

/** The watch-only tile. `running` is null until the first status read. */
data class DesktopPreview(
    val agentID: String,
    val running: Boolean? = null,
    val asked: Boolean = false,
    val viewer: DesktopLink = DesktopLink(),
) {
    val awake: Boolean
        get() = asked || running == true
}

/** Responses and events can arrive out of order; the latest change wins. */
fun newerControl(current: DesktopControl?, next: DesktopControl): DesktopControl =
    if (current != null && current.changes > next.changes) current else next

enum class DesktopKey(val label: String, val key: String, val code: String) {
    Escape("Esc", "Escape", "Escape"),
    Tab("Tab", "Tab", "Tab"),
    Left("←", "ArrowLeft", "ArrowLeft"),
    Up("↑", "ArrowUp", "ArrowUp"),
    Down("↓", "ArrowDown", "ArrowDown"),
    Right("→", "ArrowRight", "ArrowRight"),
    Backspace("⌫", "Backspace", "Backspace"),
    Enter("⏎", "Enter", "Enter"),
}

data class DesktopPress(val key: String, val code: String = "")

fun DesktopKey.press() = DesktopPress(key, code)

/** noVNC turns a key with no `code` into a press and release of its character; it can't send surrogate pairs. */
fun desktopPresses(text: String): List<DesktopPress> =
    text.replace("\r\n", "\n").filterNot { it == '\r' || it.isSurrogate() }.map {
        when (it) {
            '\n' -> DesktopKey.Enter.press()
            '\t' -> DesktopKey.Tab.press()
            else -> DesktopPress(it.toString())
        }
    }

// noVNC's core has no soft keyboard, and the viewer page keeps its RFB object private; its canvas takes key events.
/** Key events for the viewer page's canvas. The script's result says whether the canvas was there. */
fun desktopKeyScript(presses: List<DesktopPress>, ctrl: Boolean = false): String = buildString {
    fun quoted(text: String) = JsonPrimitive(text).toString()
    append("(function(){var c=document.querySelector('#screen canvas');if(!c)return false;")
    append("function k(t,key,code){c.dispatchEvent(new KeyboardEvent(t,{key:key,code:code,bubbles:true,cancelable:true}))}")
    if (ctrl) append("k('keydown','Control','ControlLeft');")
    for (press in presses) {
        val arguments = "${quoted(press.key)},${quoted(press.code)}"
        append("k('keydown',$arguments);k('keyup',$arguments);")
    }
    if (ctrl) append("k('keyup','Control','ControlLeft');")
    append("return true})()")
}
