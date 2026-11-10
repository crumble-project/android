package dev.crumble.android.model

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

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

/** The web app's settings, or one of its pages; the web app serves them from the server's origin. */
fun webSettingsURL(baseURL: String, page: String? = null): String? {
    val uri = runCatching { URI(baseURL.trim()) }.getOrNull() ?: return null
    if (uri.scheme == null || uri.host == null) return null
    val origin = "${uri.scheme}://${uri.host}" + if (uri.port > 0) ":${uri.port}" else ""
    return "$origin/settings" + page?.let { "/$it" }.orEmpty()
}

enum class AccountMode { SignIn, SignUp }

fun canCreateAccount(name: String, email: String, password: String) =
    name.isNotBlank() && email.isNotBlank() && password.isNotEmpty()

/** The token in a pasted invite link, or the text itself when it's the bare code. */
fun inviteToken(text: String): String {
    val trimmed = text.trim()
    val query = runCatching { URI(trimmed).rawQuery }.getOrNull().orEmpty()
    val token = query.split("&").firstOrNull { it.startsWith("invite=") }?.removePrefix("invite=")
    return token?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) } ?: trimmed
}
