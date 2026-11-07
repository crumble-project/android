package dev.crumble.android.model

enum class ThemeChoice(val value: String, val label: String) {
    System("system", "System"),
    Light("light", "Light"),
    Dark("dark", "Dark"),
    ;

    companion object {
        fun of(value: String?): ThemeChoice? = entries.firstOrNull { it.value == value }
    }
}

fun replyNotificationsDescription(enabled: Boolean, allowed: Boolean): String = when {
    !allowed -> "Blocked for Crumble in Android's settings."
    enabled -> "Replies from chats you don't have open show as notifications."
    else -> "Off on this device."
}
