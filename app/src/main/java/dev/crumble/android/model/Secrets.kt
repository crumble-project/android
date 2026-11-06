package dev.crumble.android.model

import dev.crumble.android.generated.Credential
import dev.crumble.android.generated.CredentialRequest
import dev.crumble.android.generated.CredentialSecret
import dev.crumble.android.generated.CredentialUse
import dev.crumble.android.generated.Credentials
import dev.crumble.android.generated.Login
import dev.crumble.android.generated.LoginFields
import dev.crumble.android.generated.LoginRequest
import dev.crumble.android.generated.LoginUse
import dev.crumble.android.generated.Logins
import dev.crumble.android.generated.PasswordManager
import dev.crumble.android.generated.Provider
import dev.crumble.android.generated.ProviderLoginSession
import dev.crumble.android.generated.SecretStatus
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val CREDENTIAL_NAME = Regex("^[A-Z_][A-Z0-9_]{0,63}$")
private val LOGIN_NAME = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
private val LOGIN_ITEM = Regex("^op://[^/]+/[^/]+$")
private val HOST_SEPARATOR = Regex("[\\s,]+")

const val SECRET_USES_PAGE = 50
const val ONE_PASSWORD = "1password"

/** Hosts as the owner types them: separated by commas, spaces, or lines. */
fun hostList(text: String): List<String> =
    text.split(HOST_SEPARATOR).map { it.trim().lowercase() }.filter { it.isNotEmpty() }

/** The body every secret endpoint takes. */
sealed class SecretInput {
    data class Value(val value: String) : SecretInput() {
        override fun toString() = "Value(redacted)"
    }

    data class Reference(val passwordManager: String, val reference: String) : SecretInput()
}

internal fun SecretInput.json(): JsonObject = when (this) {
    is SecretInput.Value -> JsonObject(mapOf("value" to JsonPrimitive(value)))
    is SecretInput.Reference -> JsonObject(mapOf("reference" to json(passwordManager, reference)))
}

private fun json(manager: String, reference: String) =
    JsonObject(mapOf("passwordManager" to JsonPrimitive(manager), "reference" to JsonPrimitive(reference)))

/**
 * A typed secret, or where a password manager holds it. Lives only in memory: never in saveable state or the
 * credential store. `toString` stays redacted so a log or crash report can't carry the value.
 */
data class SecretDraft(
    /** The password manager to read from, or null for a value stored in Crumble. */
    val manager: String? = null,
    val text: String = "",
    val saved: SecretInput.Reference? = null,
) {
    /** What to send, or null while the draft is empty. */
    val input: SecretInput?
        get() = when {
            manager != null -> text.trim().takeIf { it.isNotEmpty() }?.let { SecretInput.Reference(manager, it) }
            text.isNotEmpty() -> SecretInput.Value(text)
            else -> null
        }

    /** False for the saved reference, so saving other fields doesn't read it again. */
    val changed: Boolean get() = input.let { it != null && it != saved }

    fun source(manager: String?) = copy(manager = manager, text = "")

    override fun toString() = "SecretDraft(manager=$manager)"
}

// A value stored in Crumble is never shown, so only a saved reference starts the draft.
fun secretDraft(manager: String?, reference: String?): SecretDraft =
    if (manager != null && reference != null) {
        SecretDraft(manager, reference, SecretInput.Reference(manager, reference))
    } else {
        SecretDraft()
    }

fun secretDraft(saved: SecretStatus?): SecretDraft = secretDraft(saved?.passwordManager, saved?.reference)

private fun secretDraft(saved: CredentialSecret?): SecretDraft = secretDraft(saved?.passwordManager, saved?.reference)

private val SecretDraft.readsPasswordManager: Boolean get() = changed && input is SecretInput.Reference

private fun SecretDraft.body(): JsonElement? = if (changed) input?.json() else null

/** Editor for a credential, a bot's request to answer, or a new credential. */
data class CredentialForm(
    /** Null for a new credential from all bots: the sheet asks which bot it is for. */
    val botID: String?,
    val owner: String,
    val existing: Credential? = null,
    val request: CredentialRequest? = null,
    val name: String = "",
    val hosts: String = "",
    val secret: SecretDraft = SecretDraft(),
    val saving: Boolean = false,
    val error: String = "",
) {
    val locked: Boolean get() = existing != null || request != null
    val nameInvalid: Boolean get() = name.isNotEmpty() && !CREDENTIAL_NAME.matches(name)
    val ready: Boolean
        get() = CREDENTIAL_NAME.matches(name) && hostList(hosts).isNotEmpty() && (existing != null || secret.input != null) && !saving

    /** Crumble checks a new reference when it saves, so the outcome depends on the password manager. */
    val readsPasswordManager: Boolean get() = secret.readsPasswordManager
}

fun credentialForm(
    botID: String?,
    bots: List<Agent>,
    existing: Credential? = null,
    request: CredentialRequest? = null,
    secret: SecretDraft = secretDraft(existing?.secret),
) = CredentialForm(
    botID = botID,
    owner = botID ?: bots.firstOrNull()?.id.orEmpty(),
    existing = existing,
    request = request,
    name = existing?.name ?: request?.name.orEmpty(),
    hosts = (existing?.hosts ?: request?.hosts.orEmpty()).joinToString(", "),
    secret = secret,
)

fun credentialFields(form: CredentialForm): Map<String, JsonElement> = buildMap {
    put("hosts", JsonArray(hostList(form.hosts).map(::JsonPrimitive)))
    form.secret.body()?.let { put("secret", it) }
}

/** A value stored in Crumble is kept unless a new one is typed. */
fun keepPlaceholder(existing: Credential?): String? =
    existing?.secret?.takeIf { it.configured && it.passwordManager == null }?.let { "Keep the saved value" }

fun keepPlaceholder(field: SecretStatus?): String? =
    field?.takeIf { it.passwordManager == null }?.let { "Keep the saved value" }

enum class LoginMode { fields, item }

/** Editor for a login, a bot's request to answer, or a new login. */
data class LoginForm(
    val botID: String?,
    val owner: String,
    val existing: Login? = null,
    val request: LoginRequest? = null,
    val name: String = "",
    val hosts: String = "",
    val mode: LoginMode = LoginMode.fields,
    val item: String = "",
    val hasCode: Boolean = false,
    val username: SecretDraft = SecretDraft(),
    val password: SecretDraft = SecretDraft(),
    val code: SecretDraft = SecretDraft(),
    val saving: Boolean = false,
    val error: String = "",
) {
    val locked: Boolean get() = existing != null || request != null
    val nameInvalid: Boolean get() = name.isNotEmpty() && !LOGIN_NAME.matches(name)

    private val fieldsReady: Boolean
        get() = !(hasCode && existing?.fields?.one_time_code == null && code.input == null) &&
            (existing != null || (username.input != null && password.input != null))

    val ready: Boolean
        get() = LOGIN_NAME.matches(name) && hostList(hosts).isNotEmpty() && !saving &&
            if (mode == LoginMode.item) LOGIN_ITEM.matches(item.trim()) else fieldsReady

    val readsPasswordManager: Boolean
        get() = mode == LoginMode.item || username.readsPasswordManager || password.readsPasswordManager ||
            (hasCode && code.readsPasswordManager)
}

fun loginForm(botID: String?, bots: List<Agent>, existing: Login? = null, request: LoginRequest? = null) = LoginForm(
    botID = botID,
    owner = botID ?: bots.firstOrNull()?.id.orEmpty(),
    existing = existing,
    request = request,
    name = existing?.name ?: request?.name.orEmpty(),
    hosts = (existing?.hosts ?: request?.hosts.orEmpty()).joinToString(", "),
    hasCode = existing?.fields?.one_time_code != null || request?.oneTimeCode == true,
    username = secretDraft(existing?.fields?.username),
    password = secretDraft(existing?.fields?.password),
    code = secretDraft(existing?.fields?.one_time_code),
)

fun loginFields(form: LoginForm): Map<String, JsonElement> {
    val hosts = "hosts" to JsonArray(hostList(form.hosts).map(::JsonPrimitive))
    if (form.mode == LoginMode.item) return mapOf(hosts, "item" to json(ONE_PASSWORD, form.item.trim()))
    val fields = buildMap {
        form.username.body()?.let { put("username", it) }
        form.password.body()?.let { put("password", it) }
        when {
            form.hasCode -> form.code.body()?.let { put("one_time_code", it) }
            form.existing?.fields?.one_time_code != null -> put("one_time_code", JsonNull)
        }
    }
    return mapOf(hosts, "fields" to JsonObject(fields))
}

internal val STORED = SecretStatus(configured = true, passwordManager = null, reference = null)

/** A row to show until the server answers. Editing keeps everything the form doesn't touch. */
internal fun provisionalCredential(form: CredentialForm): Credential {
    val hosts = hostList(form.hosts)
    val secret = form.secret.takeIf { it.changed }?.let { CredentialSecret(true, null, null) }
    return form.existing?.let { it.copy(hosts = hosts, secret = secret ?: it.secret) } ?: Credential(
        name = form.name, hosts = hosts, secret = secret, createdAt = "", updatedAt = "",
        lastUsedAt = null, lastError = null, github = false,
    )
}

private fun fieldStatus(draft: SecretDraft, saved: SecretStatus?) = if (draft.changed) STORED else saved

internal fun provisionalLogin(form: LoginForm): Login {
    val saved = form.existing?.fields
    val fields = LoginFields(
        username = fieldStatus(form.username, saved?.username),
        password = fieldStatus(form.password, saved?.password),
        one_time_code = if (form.hasCode) fieldStatus(form.code, saved?.one_time_code) else null,
    )
    val hosts = hostList(form.hosts)
    return form.existing?.copy(hosts = hosts, fields = fields) ?: Login(
        name = form.name, hosts = hosts, fields = fields, createdAt = "", updatedAt = "",
        lastUsedAt = null, lastError = null,
    )
}

internal fun Credentials.withCredential(item: Credential) = copy(
    credentials = upserted(credentials, item.name, item) { it.name },
    requests = requests.filterNot { it.name == item.name },
)

internal fun Logins.withLogin(item: Login) = copy(
    logins = upserted(logins, item.name, item) { it.name },
    requests = requests.filterNot { it.name == item.name },
)

// A failure restores only this record, in its old place, keeping newer changes to the others.
private fun <T> restored(current: List<T>, before: List<T>, name: String, nameOf: (T) -> String): List<T> {
    val without = current.filterNot { nameOf(it) == name }
    val index = before.indexOfFirst { nameOf(it) == name }
    if (index < 0) return without
    return without.take(index) + before[index] + without.drop(index)
}

internal fun Credentials.restored(before: Credentials, name: String) = Credentials(
    credentials = restored(credentials, before.credentials, name) { it.name },
    requests = restored(requests, before.requests, name) { it.name },
)

internal fun Logins.restored(before: Logins, name: String) = Logins(
    logins = restored(logins, before.logins, name) { it.name },
    requests = restored(requests, before.requests, name) { it.name },
)

/** A secret's row on the Credentials and Logins pages. */
data class SecretRow(
    val name: String,
    val hosts: List<String>,
    val lastUsedAt: String?,
    val lastError: String?,
    /** A GitHub token follows its App or machine account; the server refuses to change it here. */
    val managed: Boolean = false,
)

/** A bot's request for a secret, waiting for the owner. */
data class SecretAsk(val name: String, val hosts: List<String>, val reason: String)

fun matchesSecret(name: String, hosts: List<String>, needle: String): Boolean =
    needle.isEmpty() || name.lowercase().contains(needle) || hosts.any { it.contains(needle) }

/** The bots in scope; with every bot in scope, only those with matches or a failed read get a frame. */
fun secretBotsShown(
    botIDs: List<String>,
    scope: String,
    needle: String,
    rowsOf: (String) -> List<SecretRow>,
    failed: (String) -> Boolean,
): List<String> = botIDs.filter { id ->
    if (scope == SKILLS_ALL) failed(id) || rowsOf(id).any { matchesSecret(it.name, it.hosts, needle) } else id == scope
}

/** A `request_credential` or `request_login` result in the transcript; its live state comes from the bot's lists. */
@Serializable
data class SecretRequestRef(val name: String = "", val hosts: List<String> = emptyList(), val requestedAt: String? = null) {
    fun answers(requestName: String, at: String) = requestName == name && (requestedAt == null || requestedAt == at)
}

sealed class SecretRequestState<out R> {
    data object Loading : SecretRequestState<Nothing>()
    data object Unavailable : SecretRequestState<Nothing>()
    data object Saved : SecretRequestState<Nothing>()
    data object Declined : SecretRequestState<Nothing>()
    data class Waiting<R>(val request: R) : SecretRequestState<R>()
}

/** A list read before the request arrived can't say it was answered, so only a `fresh` read settles a card. */
fun <R : Any> secretRequestState(request: R?, listed: Boolean, saved: Boolean, fresh: Boolean, failed: Boolean): SecretRequestState<R> = when {
    !listed || (request == null && !fresh) -> if (failed) SecretRequestState.Unavailable else SecretRequestState.Loading
    request != null -> SecretRequestState.Waiting(request)
    saved -> SecretRequestState.Saved
    else -> SecretRequestState.Declined
}

enum class SecretKind { credential, login }

/** One line of a secret's use log. */
data class SecretUseRow(val id: String, val at: String, val detail: String, val note: String?, val failed: Boolean)

data class SecretUsesView(
    val kind: SecretKind,
    val botID: String,
    val name: String,
    val id: UUID = UUID.randomUUID(),
    val rows: List<SecretUseRow> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: String = "",
)

private val LOGIN_FIELD_LABELS = mapOf("username" to "Username", "password" to "Password", "one_time_code" to "One-time code")

internal fun CredentialUse.row() = SecretUseRow(id, at, "$method $host$path", status?.toString() ?: "failed", status == null)

internal fun LoginUse.row() = SecretUseRow(id, at, "${LOGIN_FIELD_LABELS[field] ?: field} on $host", error, error != null)

data class ManagerTokenForm(
    val manager: PasswordManager,
    val token: SecretDraft = SecretDraft(),
    val saving: Boolean = false,
    val error: String = "",
) {
    val ready: Boolean get() = token.text.isNotBlank() && !saving
}

data class ProviderKeyForm(
    val provider: Provider,
    val key: SecretDraft,
    val saving: Boolean = false,
    val error: String = "",
)

/** A provider's account sign-in; closing keeps it going on the server so it can be continued. */
data class ProviderLoginView(
    val provider: Provider,
    val sessionID: String? = null,
    val session: ProviderLoginSession? = null,
    val starting: Boolean = false,
    val cancelling: Boolean = false,
    val answering: Boolean = false,
    /** Answers can be secrets, so they go when the prompt changes or the dialog closes. */
    val answer: SecretDraft = SecretDraft(),
    val answerError: String = "",
    val gone: Boolean = false,
    val error: String = "",
)

fun providerKeyStatus(provider: Provider, managers: List<PasswordManager>): String {
    val id = provider.apiKey.passwordManager ?: return "Key saved"
    return "Key in ${managers.firstOrNull { it.id == id }?.name ?: "a password manager"}"
}

private val EXTERNAL_STATUS = mapOf(
    "signed_in" to "Signed in",
    "signed_out" to "Not signed in",
    "cli_missing" to "Not installed",
    "error" to "Unavailable",
)

fun providerStatus(provider: Provider, managers: List<PasswordManager>): String? {
    val external = provider.externalAuth
    return when {
        external != null -> EXTERNAL_STATUS[external.status]
        provider.credentialType == "oauth" -> "Signed in"
        provider.credentialType == "api_key" -> providerKeyStatus(provider, managers)
        provider.configured -> "From environment"
        provider.activeLoginId != null -> "Signing in…"
        else -> null
    }
}

fun Provider.signInAvailable(): Boolean = authMethods.any { it.type == "oauth" } && credentialType != "oauth"

fun Provider.keyAvailable(): Boolean = authMethods.any { it.type == "api_key" }

fun matchingProviders(providers: List<Provider>, needle: String): List<Provider> =
    providers.filter { needle.isEmpty() || it.name.lowercase().contains(needle) || it.provider.contains(needle) }
        .sortedBy { it.name.lowercase() }

internal fun Provider.withKey() = copy(configured = true, credentialType = "api_key", apiKey = STORED)

internal fun Provider.withoutCredential() = copy(configured = false, credentialType = null, apiKey = SecretStatus(false, null, null))

/** Sign-in links come from the provider through the server; only web pages may leave the app. */
fun isWebLink(url: String): Boolean = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

/** Whether the server URL sends the session in the clear beyond this device and its private network. */
fun isExposedHttp(url: String): Boolean {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
    val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
    return uri.scheme.equals("http", ignoreCase = true) && !isLocalHost(host)
}

private fun isLocalHost(host: String): Boolean {
    if (host == "localhost" || host.endsWith(".localhost")) return true
    val address = addressLiteral(host) ?: return false
    val uniqueLocal = address is Inet6Address && address.address[0].toInt() and 0xfe == 0xfc
    return address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress || uniqueLocal
}

// Literals only: InetAddress.getByName would look a name up in DNS, and this runs while the owner types.
private fun addressLiteral(host: String): InetAddress? {
    if (host.startsWith("[")) return runCatching { InetAddress.getByName(host.trim('[', ']')) }.getOrNull()
    val octets = host.split('.').map { it.toIntOrNull() }
    if (octets.size != 4 || octets.any { it == null || it !in 0..255 }) return null
    return InetAddress.getByAddress(octets.map { it!!.toByte() }.toByteArray())
}

fun secretKey(kind: String, botID: String, name: String) = "$kind:$botID:$name"

fun credentialReadKey(ref: SecretRequestRef) = "credential:${ref.name}:${ref.requestedAt.orEmpty()}"

fun loginReadKey(ref: SecretRequestRef) = "login:${ref.name}:${ref.requestedAt.orEmpty()}"
