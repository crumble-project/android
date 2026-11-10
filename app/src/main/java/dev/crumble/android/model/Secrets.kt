package dev.crumble.android.model

import dev.crumble.android.generated.Credential
import dev.crumble.android.generated.CredentialRequest
import dev.crumble.android.generated.CredentialSecret
import dev.crumble.android.generated.Credentials
import dev.crumble.android.generated.Login
import dev.crumble.android.generated.LoginFields
import dev.crumble.android.generated.LoginRequest
import dev.crumble.android.generated.Logins
import dev.crumble.android.generated.SecretStatus
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val CREDENTIAL_NAME = Regex("^[A-Z_][A-Z0-9_]{0,63}$")
private val LOGIN_NAME = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
private val LOGIN_ITEM = Regex("^op://[^/]+/[^/]+$")
private val HOST_SEPARATOR = Regex("[\\s,]+")

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
) {
    /** What to send, or null while the draft is empty. */
    val input: SecretInput?
        get() = when {
            manager != null -> text.trim().takeIf { it.isNotEmpty() }?.let { SecretInput.Reference(manager, it) }
            text.isNotEmpty() -> SecretInput.Value(text)
            else -> null
        }

    fun source(manager: String?) = copy(manager = manager, text = "")

    override fun toString() = "SecretDraft(manager=$manager)"
}

private val SecretDraft.readsPasswordManager: Boolean get() = input is SecretInput.Reference

private fun SecretDraft.body(): JsonElement? = input?.json()

/** The answer to a bot's request for a credential. */
data class CredentialForm(
    val owner: String,
    val request: CredentialRequest,
    val name: String = "",
    val hosts: String = "",
    val secret: SecretDraft = SecretDraft(),
    val saving: Boolean = false,
    val error: String = "",
) {
    val nameInvalid: Boolean get() = name.isNotEmpty() && !CREDENTIAL_NAME.matches(name)
    val ready: Boolean
        get() = CREDENTIAL_NAME.matches(name) && hostList(hosts).isNotEmpty() && secret.input != null && !saving

    /** Crumble checks a new reference when it saves, so the outcome depends on the password manager. */
    val readsPasswordManager: Boolean get() = secret.readsPasswordManager
}

fun credentialForm(botID: String, request: CredentialRequest) = CredentialForm(
    owner = botID,
    request = request,
    name = request.name,
    hosts = request.hosts.joinToString(", "),
)

fun credentialFields(form: CredentialForm): Map<String, JsonElement> = buildMap {
    put("hosts", JsonArray(hostList(form.hosts).map(::JsonPrimitive)))
    form.secret.body()?.let { put("secret", it) }
}

enum class LoginMode { fields, item }

/** The answer to a bot's request for a login. */
data class LoginForm(
    val owner: String,
    val request: LoginRequest,
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
    val nameInvalid: Boolean get() = name.isNotEmpty() && !LOGIN_NAME.matches(name)

    private val fieldsReady: Boolean
        get() = !(hasCode && code.input == null) && username.input != null && password.input != null

    val ready: Boolean
        get() = LOGIN_NAME.matches(name) && hostList(hosts).isNotEmpty() && !saving &&
            if (mode == LoginMode.item) LOGIN_ITEM.matches(item.trim()) else fieldsReady

    val readsPasswordManager: Boolean
        get() = mode == LoginMode.item || username.readsPasswordManager || password.readsPasswordManager ||
            (hasCode && code.readsPasswordManager)
}

fun loginForm(botID: String, request: LoginRequest) = LoginForm(
    owner = botID,
    request = request,
    name = request.name,
    hosts = request.hosts.joinToString(", "),
    hasCode = request.oneTimeCode,
)

fun loginFields(form: LoginForm): Map<String, JsonElement> {
    val hosts = "hosts" to JsonArray(hostList(form.hosts).map(::JsonPrimitive))
    if (form.mode == LoginMode.item) return mapOf(hosts, "item" to json(ONE_PASSWORD, form.item.trim()))
    val fields = buildMap {
        form.username.body()?.let { put("username", it) }
        form.password.body()?.let { put("password", it) }
        if (form.hasCode) form.code.body()?.let { put("one_time_code", it) }
    }
    return mapOf(hosts, "fields" to JsonObject(fields))
}

private val STORED = SecretStatus(configured = true, passwordManager = null, reference = null)

/** A row to show until the server answers. */
internal fun provisionalCredential(form: CredentialForm): Credential = Credential(
    name = form.name, hosts = hostList(form.hosts), secret = CredentialSecret(true, null, null), createdAt = "", updatedAt = "",
    lastUsedAt = null, lastError = null, github = false,
)

internal fun provisionalLogin(form: LoginForm): Login = Login(
    name = form.name,
    hosts = hostList(form.hosts),
    fields = LoginFields(username = STORED, password = STORED, one_time_code = STORED.takeIf { form.hasCode }),
    createdAt = "", updatedAt = "", lastUsedAt = null, lastError = null,
)

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
