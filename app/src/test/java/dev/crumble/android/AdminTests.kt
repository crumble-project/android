package dev.crumble.android

import dev.crumble.android.generated.AdminUser
import dev.crumble.android.generated.Invite
import dev.crumble.android.generated.ListOutdatedWorkspacesResponseWorkspacesItem
import dev.crumble.android.model.AccountMode
import dev.crumble.android.model.Agent
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.OutdatedWorkspaceRow
import dev.crumble.android.model.Route
import dev.crumble.android.model.WorkspaceField
import dev.crumble.android.model.canCreateAccount
import dev.crumble.android.model.dateOnly
import dev.crumble.android.model.inviteToken
import dev.crumble.android.model.outdatedWorkspaceRows
import dev.crumble.android.model.userRowTitle
import dev.crumble.android.network.APIClient
import dev.crumble.android.network.HttpRequestData
import dev.crumble.android.network.HttpResponseData
import dev.crumble.android.network.HttpStreamResponse
import dev.crumble.android.network.HttpTransport
import dev.crumble.android.network.INVITE_HEADER
import dev.crumble.android.network.friendlyError
import dev.crumble.android.storage.InMemoryCredentialStore
import java.nio.charset.StandardCharsets
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SERVER = "https://crumble.example"
private const val ME = "user-me"
private const val OTHER = "user-other"
private const val SIGN_UP = "POST /api/auth/sign-up/email"
private const val SIGN_IN = "POST /api/auth/sign-in/email"
private const val SESSION = "GET /api/auth/get-session"
private const val USERS = "GET /v1/admin/users"
private const val INVITES = "GET /v1/admin/invites"
private const val MINT = "POST /v1/admin/invites"
private const val SETTINGS = "GET /v1/admin/settings"
private const val PATCH_SETTINGS = "PATCH /v1/admin/settings"
private const val OUTDATED = "GET /v1/admin/workspaces/outdated"

private class AdminTransport : HttpTransport {
    val requests = mutableListOf<HttpRequestData>()
    val held = mutableMapOf<String, CompletableDeferred<Unit>>()
    val responses = mutableMapOf<String, Pair<Int, String>>()

    override suspend fun execute(request: HttpRequestData): HttpResponseData {
        requests.add(request)
        val key = "${request.method} ${request.pathWithQuery}"
        held[key]?.await()
        val (status, body) = responses[key] ?: (503 to """{"error":"unscripted $key"}""")
        return HttpResponseData(status, body.toByteArray(StandardCharsets.UTF_8))
    }

    override suspend fun openStream(request: HttpRequestData): HttpStreamResponse =
        throw UnsupportedOperationException("streams are not used here")

    fun sent(key: String) = requests.filter { "${it.method} ${it.pathWithQuery}" == key }
    fun hold(key: String) = CompletableDeferred<Unit>().also { held[key] = it }
    fun answer(key: String, status: Int = 200, body: String) {
        responses[key] = status to body
    }
}

private fun userJson(id: String, name: String = id, role: String = "user", createdAt: String = "2026-01-01T00:00:00.000Z") =
    """{"id":"$id","email":"$id@example.com","name":"$name","role":"$role","createdAt":"$createdAt"}"""

private fun inviteJson(id: String, createdAt: String = "2026-03-01T00:00:00.000Z") = """{"id":"$id","createdAt":"$createdAt"}"""

private fun settingsJson(open: Boolean = false, image: String = "img") =
    """{"signUpOpen":$open,"workspaceCpus":2,"workspaceMemoryMib":4096,"codingImage":"$image","questionGraceMinutes":30}"""

private fun sessionJson(role: String = "admin") = """{"session":{"id":"s","userId":"$ME"},"user":{"id":"$ME","role":"$role"}}"""

@OptIn(ExperimentalCoroutinesApi::class)
class AdminTests {
    private fun scripted() = AdminTransport().apply {
        answer(SESSION, body = sessionJson())
        answer(USERS, body = """{"users":[${userJson(ME, "Me", "admin")},${userJson(OTHER, "Kev", createdAt = "2026-02-01T00:00:00.000Z")},${userJson("user-third", "Ada", createdAt = "2026-02-02T00:00:00.000Z")}]}""")
        answer(INVITES, body = """{"invites":[${inviteJson("new", "2026-04-01T00:00:00.000Z")},${inviteJson("old")}]}""")
        answer(SETTINGS, body = settingsJson())
        answer("GET /v1/agents", body = "[]")
        answer("GET /v1/group-chats", body = """{"groups":[]}""")
        answer("GET /v1/password-managers", body = """{"passwordManagers":[]}""")
        answer("GET /v1/github-apps", body = """{"apps":[]}""")
        answer("GET /v1/github-accounts", body = """{"accounts":[]}""")
        answer(OUTDATED, body = """{"workspaces":[]}""")
    }

    private fun TestScope.usersPage(transport: AdminTransport): AppModel {
        val client = APIClient(baseURL = SERVER, token = "session", transport = transport)
        val model = AppModel(client = client, scope = this)
        model.navigate(Route.Users)
        runCurrent()
        return model
    }

    private fun TestScope.signedOut(transport: AdminTransport, store: InMemoryCredentialStore = InMemoryCredentialStore()): AppModel =
        // The activity stream retries forever once connected, so the model can't live in the test's own scope.
        AppModel(credentials = store, scope = backgroundScope, loadCredentials = false, transport = transport).also {
            it.serverURL = SERVER
            it.email = " new@example.com "
            it.password = "hunter2hunter2"
            it.accountName = " New Person "
            it.accountMode = AccountMode.SignUp
        }

    private fun AppModel.user(id: String) = adminUsers.value!!.first { it.id == id }

    // MARK: Creating an account

    @Test
    fun signUpSendsTheInviteHeaderAndWaitsForTheServer() = runTest {
        val transport = scripted()
        transport.answer(SIGN_UP, body = """{"token":"new-session","user":{"id":"u"}}""")
        val gate = transport.hold(SIGN_UP)
        val store = InMemoryCredentialStore()
        val model = signedOut(transport, store)
        model.inviteCode = "$SERVER/connect?invite=abc-_123"

        model.saveSettings()
        runCurrent()
        assertTrue(model.savingConnection)
        assertEquals("", model.token)
        assertEquals("", model.notice)

        gate.complete(Unit)
        runCurrent()
        val request = transport.sent(SIGN_UP).single()
        assertEquals("abc-_123", request.headers[INVITE_HEADER])
        assertFalse(request.headers.containsKey("Authorization"))
        assertEquals(
            """{"email":"new@example.com","password":"hunter2hunter2","name":"New Person"}""",
            request.body?.toString(StandardCharsets.UTF_8),
        )
        assertEquals("new-session", model.token)
        assertEquals("new-session", store.read("server-token"))
        assertEquals("", model.password)
        assertEquals(AccountMode.SignIn, model.accountMode)
        assertEquals("", model.inviteCode)
        assertEquals("", model.accountName)
        assertEquals("Connected", model.notice)
    }

    @Test
    fun signUpWithoutAnInviteOmitsTheHeader() = runTest {
        val transport = scripted()
        transport.answer(SIGN_UP, body = """{"token":"new-session"}""")
        val model = signedOut(transport)
        model.inviteCode = "   "

        model.saveSettings()
        runCurrent()
        assertFalse(transport.sent(SIGN_UP).single().headers.containsKey(INVITE_HEADER))
    }

    @Test
    fun closedSignUpShowsTheServersReasonAndKeepsTheForm() = runTest {
        val transport = scripted()
        transport.answer(SIGN_UP, 403, """{"code":"FORBIDDEN","message":"Sign-up is closed. Ask an admin for an invite link."}""")
        val model = signedOut(transport)
        model.inviteCode = "spent"

        model.saveSettings()
        runCurrent()
        assertEquals("Sign-up is closed. Ask an admin for an invite link.", model.errorText)
        assertEquals(AccountMode.SignUp, model.accountMode)
        assertEquals("hunter2hunter2", model.password)
        assertEquals("spent", model.inviteCode)
        assertEquals("", model.token)
        assertFalse(model.savingConnection)
    }

    @Test
    fun aRetryAfterACreatedAccountFailedToConnectSignsIn() = runTest {
        val transport = scripted()
        transport.answer(SIGN_UP, body = """{"token":"new-session"}""")
        transport.answer("GET /v1/agents", 500, """{"error":"Starting up"}""")
        val model = signedOut(transport)
        model.inviteCode = "once"

        model.saveSettings()
        runCurrent()
        assertEquals("Your account was created, but connecting failed: Starting up", model.errorText)
        assertEquals(AccountMode.SignIn, model.accountMode)
        assertEquals("hunter2hunter2", model.password)
        assertEquals("", model.token)

        transport.answer("GET /v1/agents", body = "[]")
        transport.answer(SIGN_IN, body = """{"token":"signed-in"}""")
        model.saveSettings()
        runCurrent()
        assertEquals(1, transport.sent(SIGN_UP).size)
        assertEquals("signed-in", model.token)
    }

    @Test
    fun anIncompleteSignUpStaysLocal() = runTest {
        val transport = scripted()
        val model = signedOut(transport)
        model.accountName = "  "

        model.saveSettings()
        runCurrent()
        assertEquals("Enter your name, email, and password.", model.errorText)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun switchingModesClearsTheError() {
        val model = AppModel(loadCredentials = false)
        model.errorText = "old"
        model.switchAccountMode()
        assertEquals(AccountMode.SignUp, model.accountMode)
        assertEquals("", model.errorText)
        model.switchAccountMode()
        assertEquals(AccountMode.SignIn, model.accountMode)
    }

    @Test
    fun inviteTokensComeFromLinksOrBareCodes() {
        assertEquals("tok-_en", inviteToken(" https://bots.example/connect?invite=tok-_en\n"))
        assertEquals("a b", inviteToken("https://bots.example/connect?x=1&invite=a%20b"))
        assertEquals("tok-_en", inviteToken("  tok-_en "))
        assertEquals("", inviteToken("   "))
        assertEquals("not a link", inviteToken("not a link"))
    }

    @Test
    fun anAccountNeedsANameEmailAndPassword() {
        assertTrue(canCreateAccount("A", "a@b", "pw"))
        assertFalse(canCreateAccount(" ", "a@b", "pw"))
        assertFalse(canCreateAccount("A", " ", "pw"))
        assertFalse(canCreateAccount("A", "a@b", ""))
    }

    // MARK: Session

    @Test
    fun anEmptySessionReadsAsSignedOut() = runTest {
        val transport = scripted()
        transport.answer(SESSION, body = "null")
        val client = APIClient(baseURL = SERVER, token = "stale", transport = transport)
        val failure = runCatching { client.accountSession() }.exceptionOrNull()
        assertEquals("Authentication failed. Sign in again in Settings.", friendlyError(failure!!))
    }

    @Test
    fun refreshingLearnsTheRoleForTheSidebar() = runTest {
        val transport = scripted()
        val model = AppModel(client = APIClient(SERVER, "session", transport), scope = this)
        model.refresh()
        runCurrent()
        assertEquals(true, model.isAdmin)
        assertEquals(ME, model.accountID)
        assertEquals("Bearer session", transport.sent(SESSION).single().headers["Authorization"])
        assertTrue(transport.sent(USERS).isEmpty())
    }

    @Test
    fun aRegularUserLoadsNoAdminData() = runTest {
        val transport = scripted()
        transport.answer(SESSION, body = sessionJson(role = "user"))
        val model = usersPage(transport)
        assertEquals(false, model.isAdmin)
        assertTrue(transport.sent(USERS).isEmpty())
        assertTrue(transport.sent(INVITES).isEmpty())
        assertTrue(transport.sent(SETTINGS).isEmpty())
    }

    @Test
    fun aFailedSessionReadShowsOnThePage() = runTest {
        val transport = scripted()
        transport.answer(SESSION, 500, """{"error":"Down"}""")
        val model = usersPage(transport)
        assertNull(model.isAdmin)
        assertEquals("Down", model.adminUsers.error)
    }

    // MARK: Users

    @Test
    fun theUsersPageLoadsUsersInvitesAndSettings() = runTest {
        val model = usersPage(scripted())
        assertEquals(listOf(ME, OTHER, "user-third"), model.adminUsers.value?.map { it.id })
        assertEquals(listOf("new", "old"), model.invites.value?.map { it.id })
        assertEquals(false, model.serverSettings.value?.signUpOpen)
        assertEquals(ME, model.accountID)
    }

    @Test
    fun aRoleChangeAppliesAtOnceAndSurvivesADelayedRefresh() = runTest {
        val transport = scripted()
        val model = usersPage(transport)
        val path = "PATCH /v1/admin/users/$OTHER"
        transport.answer(path, body = userJson(OTHER, "Kev Renamed", "admin"))
        val gate = transport.hold(path)

        model.setUserRole(model.user(OTHER), "admin")
        assertEquals("admin", model.user(OTHER).role)
        assertTrue("admin-user:$OTHER" in model.pendingMutations)

        model.navigate(Route.Users)
        runCurrent()
        assertEquals("admin", model.user(OTHER).role)
        assertEquals("""{"role":"admin"}""", transport.sent(path).single().body?.toString(StandardCharsets.UTF_8))

        gate.complete(Unit)
        runCurrent()
        assertEquals("Kev Renamed", model.user(OTHER).name)
        assertFalse("admin-user:$OTHER" in model.pendingMutations)
    }

    @Test
    fun aRefusedRoleChangeRollsBackAndSaysWhy() = runTest {
        val transport = scripted()
        val model = usersPage(transport)
        transport.answer("PATCH /v1/admin/users/$OTHER", 409, """{"error":"The server needs at least one admin"}""")

        model.setUserRole(model.user(OTHER), "admin")
        runCurrent()
        assertEquals("user", model.user(OTHER).role)
        assertEquals("The server needs at least one admin", model.adminUsers.failure)
    }

    @Test
    fun aRemovedAccountLeavesAtOnceAndReturnsInPlaceOnFailure() = runTest {
        val transport = scripted()
        val model = usersPage(transport)
        val path = "DELETE /v1/admin/users/$OTHER"
        transport.answer(path, 500, """{"error":"Busy"}""")
        val gate = transport.hold(path)

        model.removeUser(model.user(OTHER))
        assertEquals(listOf(ME, "user-third"), model.adminUsers.value?.map { it.id })
        runCurrent()

        transport.responses.remove(USERS)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(ME, OTHER, "user-third"), model.adminUsers.value?.map { it.id })
        assertEquals("Busy", model.adminUsers.failure)
    }

    @Test
    fun aConfirmedRemovalStaysGone() = runTest {
        val transport = scripted()
        val model = usersPage(transport)
        transport.answer("DELETE /v1/admin/users/$OTHER", 204, "")

        model.removeUser(model.user(OTHER))
        runCurrent()
        assertEquals(listOf(ME, "user-third"), model.adminUsers.value?.map { it.id })
        assertEquals("", model.adminUsers.failure)
    }

    @Test
    fun usersShowTheirNameOrEmailAndRole() {
        assertEquals("Kev (admin)", userRowTitle(AdminUser("1", "k@x", "Kev", "admin", "t")))
        assertEquals("k@x", userRowTitle(AdminUser("1", "k@x", "", "user", "t")))
    }

    // MARK: Invites

    @Test
    fun anInviteIsMintedOnceAndShownWhenTheServerConfirms() = runTest {
        val transport = scripted()
        val model = usersPage(transport)
        transport.answer(MINT, body = """{"id":"i1","createdAt":"t","url":"$SERVER/connect?invite=tok"}""")
        val gate = transport.hold(MINT)

        model.createInvite()
        assertNotNull(model.inviteSheet)
        assertNull(model.inviteSheet?.url)
        runCurrent()
        assertNull(model.inviteSheet?.url)

        gate.complete(Unit)
        runCurrent()
        assertEquals("$SERVER/connect?invite=tok", model.inviteSheet?.url)
        assertEquals(1, transport.sent(MINT).size)

        model.closeInviteSheet()
        runCurrent()
        assertNull(model.inviteSheet)
        assertEquals(1, transport.sent(MINT).size)
    }

    @Test
    fun aFailedInviteOffersARetry() = runTest {
        val transport = scripted()
        val model = usersPage(transport)
        transport.answer(MINT, 500, """{"error":"Busy"}""")

        model.createInvite()
        runCurrent()
        assertEquals("Busy", model.inviteSheet?.error)
        assertNull(model.inviteSheet?.url)

        transport.answer(MINT, body = """{"id":"i1","createdAt":"t","url":"link"}""")
        model.createInvite()
        assertEquals("", model.inviteSheet?.error)
        runCurrent()
        assertEquals("link", model.inviteSheet?.url)
    }

    @Test
    fun aLateInviteNeitherReopensTheSheetNorLeavesTheListStale() = runTest {
        val transport = scripted()
        val model = usersPage(transport)
        transport.answer(MINT, body = """{"id":"late","createdAt":"t","url":"link"}""")
        val gate = transport.hold(MINT)

        model.createInvite()
        runCurrent()
        model.closeInviteSheet()
        runCurrent()
        transport.answer(INVITES, body = """{"invites":[${inviteJson("late", "2026-05-01T00:00:00.000Z")}]}""")

        gate.complete(Unit)
        runCurrent()
        assertNull(model.inviteSheet)
        assertEquals(listOf("late"), model.invites.value?.map { it.id })
    }

    @Test
    fun anEarlierInviteCannotFillANewerSheet() = runTest {
        val transport = scripted()
        val model = usersPage(transport)
        transport.answer(MINT, body = """{"id":"first","createdAt":"t","url":"first-link"}""")
        val first = transport.hold(MINT)

        model.createInvite()
        runCurrent()
        model.closeInviteSheet()
        val second = CompletableDeferred<Unit>()
        transport.held[MINT] = second
        model.createInvite()
        runCurrent()

        first.complete(Unit)
        runCurrent()
        assertNull(model.inviteSheet?.url)

        transport.answer(MINT, body = """{"id":"second","createdAt":"t","url":"second-link"}""")
        second.complete(Unit)
        runCurrent()
        assertEquals("second-link", model.inviteSheet?.url)
    }

    @Test
    fun leavingThePageDropsTheShownLink() = runTest {
        val transport = scripted()
        val model = usersPage(transport)
        transport.answer(MINT, body = """{"id":"i1","createdAt":"t","url":"link"}""")
        model.createInvite()
        runCurrent()
        assertEquals("link", model.inviteSheet?.url)

        model.navigate(Route.Coding)
        assertNull(model.inviteSheet)
    }

    @Test
    fun revokingAnInviteAppliesAtOnceAndRollsBackInPlace() = runTest {
        val transport = scripted()
        val model = usersPage(transport)
        val path = "DELETE /v1/admin/invites/new"
        transport.answer(path, 404, """{"error":"No such invite"}""")
        val gate = transport.hold(path)
        val invite: Invite = model.invites.value!!.first()

        model.revokeInvite(invite)
        assertEquals(listOf("old"), model.invites.value?.map { it.id })
        runCurrent()

        transport.responses.remove(INVITES)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("new", "old"), model.invites.value?.map { it.id })
        assertEquals("No such invite", model.invites.failure)
    }

    @Test
    fun invitesShowTheirDate() {
        assertTrue(dateOnly("2026-03-01T12:00:00.000Z", ZoneOffset.UTC).contains("26"))
        assertEquals("not a date", dateOnly("not a date", ZoneOffset.UTC))
    }

    // MARK: Server settings

    @Test
    fun openingSignUpAppliesAtOnceAndRollsBackOnFailure() = runTest {
        val transport = scripted()
        val model = usersPage(transport)
        transport.answer(PATCH_SETTINGS, 500, """{"error":"Busy"}""")
        val gate = transport.hold(PATCH_SETTINGS)

        model.setSignUpOpen(true)
        assertEquals(true, model.serverSettings.value?.signUpOpen)
        runCurrent()
        assertEquals("""{"signUpOpen":true}""", transport.sent(PATCH_SETTINGS).single().body?.toString(StandardCharsets.UTF_8))

        gate.complete(Unit)
        runCurrent()
        assertEquals(false, model.serverSettings.value?.signUpOpen)
        assertEquals("Busy", model.serverSettings.failure)
    }

    @Test
    fun aSlowSignUpChangeIsNotOverwrittenByARefresh() = runTest {
        val transport = scripted()
        val model = usersPage(transport)
        transport.answer(PATCH_SETTINGS, body = settingsJson(open = true))
        val gate = transport.hold(PATCH_SETTINGS)

        model.setSignUpOpen(true)
        model.navigate(Route.Users)
        runCurrent()
        assertEquals(true, model.serverSettings.value?.signUpOpen)

        model.setSignUpOpen(false)
        runCurrent()
        assertEquals(1, transport.sent(PATCH_SETTINGS).size)

        gate.complete(Unit)
        runCurrent()
        assertEquals(true, model.serverSettings.value?.signUpOpen)
    }

    @Test
    fun workspaceAndSignUpChangesWaitForEachOther() = runTest {
        val transport = scripted()
        val model = usersPage(transport)
        transport.answer(PATCH_SETTINGS, body = settingsJson(open = true))
        val gate = transport.hold(PATCH_SETTINGS)
        model.setSignUpOpen(true)
        model.openServerSettings(WorkspaceField.grace)
        model.serverSettingsForm = model.serverSettingsForm?.copy(grace = "5")

        model.saveServerSettings()
        runCurrent()
        assertEquals(1, transport.sent(PATCH_SETTINGS).size)
        assertNotNull(model.serverSettingsForm)
        gate.complete(Unit)
    }

    // MARK: Outdated workspaces

    @Test
    fun theCodingPageNamesOutdatedWorkspaces() = runTest {
        val transport = scripted()
        transport.answer(OUTDATED, body = """{"workspaces":[{"userId":"$OTHER","agentId":"bot-1"}]}""")
        val model = AppModel(client = APIClient(SERVER, "session", transport), scope = this)
        model.navigate(Route.Coding)
        runCurrent()
        assertEquals(listOf("bot-1"), model.outdatedWorkspaces.value?.map { it.agentId })
        assertEquals("Kev", model.adminUsers.value?.first { it.id == OTHER }?.name)
    }

    @Test
    fun aRegularUserNeverAsksForOutdatedWorkspaces() = runTest {
        val transport = scripted()
        transport.answer(SETTINGS, 403, """{"error":"Only admins can do this"}""")
        val model = AppModel(client = APIClient(SERVER, "session", transport), scope = this)
        model.navigate(Route.Coding)
        runCurrent()
        assertEquals(false, model.isAdmin)
        assertTrue(transport.sent(OUTDATED).isEmpty())
    }

    @Test
    fun savingAnImageRechecksOutdatedWorkspaces() = runTest {
        val transport = scripted()
        val model = AppModel(client = APIClient(SERVER, "session", transport), scope = this)
        model.navigate(Route.Coding)
        runCurrent()
        assertEquals(emptyList<Any>(), model.outdatedWorkspaces.value)
        transport.answer(PATCH_SETTINGS, body = settingsJson(image = "next"))
        transport.answer(OUTDATED, body = """{"workspaces":[{"userId":"$ME","agentId":"bot-1"}]}""")

        model.openServerSettings(WorkspaceField.image)
        model.serverSettingsForm = model.serverSettingsForm?.copy(image = "next")
        model.saveServerSettings()
        runCurrent()
        assertEquals(1, model.outdatedWorkspaces.value?.size)
    }

    @Test
    fun outdatedRowsFallBackToIDsForOtherAccounts() {
        val workspaces = listOf(
            ListOutdatedWorkspacesResponseWorkspacesItem(userId = ME, agentId = "mine"),
            ListOutdatedWorkspacesResponseWorkspacesItem(userId = "gone", agentId = "theirs"),
        )
        val rows = outdatedWorkspaceRows(
            workspaces,
            listOf(Agent(id = "mine", name = "Atlas")),
            listOf(AdminUser(ME, "me@x", "", "admin", "t")),
        )
        assertEquals(listOf(OutdatedWorkspaceRow("Atlas", "me@x"), OutdatedWorkspaceRow("theirs", "gone")), rows)
    }

    @Test
    fun disconnectedScreensDoNotProbeTheSession() {
        val model = AppModel(loadCredentials = false)
        model.refresh()
        assertNull(model.isAdmin)
    }
}
