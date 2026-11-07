package dev.crumble.android.model

import dev.crumble.android.generated.AdminUser
import dev.crumble.android.generated.ListOutdatedWorkspacesResponseWorkspacesItem
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

enum class AccountMode { SignIn, SignUp }

/** The new invite's link, shown once, or why minting it failed. Neither means it's still on its way. */
data class InviteSheet(val url: String? = null, val error: String = "")

data class OutdatedWorkspaceRow(val bot: String, val owner: String)

fun canCreateAccount(name: String, email: String, password: String) =
    name.isNotBlank() && email.isNotBlank() && password.isNotEmpty()

/** The token in a pasted invite link, or the text itself when it's the bare code. */
fun inviteToken(text: String): String {
    val trimmed = text.trim()
    val query = runCatching { URI(trimmed).rawQuery }.getOrNull().orEmpty()
    val token = query.split("&").firstOrNull { it.startsWith("invite=") }?.removePrefix("invite=")
    return token?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) } ?: trimmed
}

fun userRowTitle(user: AdminUser): String =
    user.name.ifEmpty { user.email } + if (user.role == "admin") " (admin)" else ""

fun dateOnly(iso: String, zone: ZoneId = ZoneId.systemDefault()): String {
    val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return iso
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withZone(zone).format(instant)
}

/** Other accounts' bots aren't listed for an admin, so those rows fall back to the bot's ID. */
fun outdatedWorkspaceRows(
    workspaces: List<ListOutdatedWorkspacesResponseWorkspacesItem>,
    bots: List<Agent>,
    users: List<AdminUser>,
): List<OutdatedWorkspaceRow> = workspaces.map { workspace ->
    OutdatedWorkspaceRow(
        bot = bots.firstOrNull { it.id == workspace.agentId }?.name ?: workspace.agentId,
        owner = users.firstOrNull { it.id == workspace.userId }?.let { it.name.ifEmpty { it.email } } ?: workspace.userId,
    )
}
