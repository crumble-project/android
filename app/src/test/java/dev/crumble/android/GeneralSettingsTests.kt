package dev.crumble.android

import dev.crumble.android.generated.GroupChat
import dev.crumble.android.model.Agent
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.ChatSnapshot
import dev.crumble.android.model.NotificationSettings
import dev.crumble.android.model.PaletteAction
import dev.crumble.android.model.PaletteSection
import dev.crumble.android.model.Route
import dev.crumble.android.model.ThemeChoice
import dev.crumble.android.model.botStatusLine
import dev.crumble.android.model.greeting
import dev.crumble.android.model.paletteSections
import dev.crumble.android.model.replyNotificationsDescription
import dev.crumble.android.model.searchPalette
import dev.crumble.android.network.APIClient
import dev.crumble.android.network.HttpRequestData
import dev.crumble.android.network.HttpResponseData
import dev.crumble.android.network.HttpStreamResponse
import dev.crumble.android.network.HttpTransport
import dev.crumble.android.storage.InMemoryCredentialStore
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SERVER = "https://crumble.example"
private const val SAVE_THEME = "POST /api/auth/update-user"
private const val SESSION = "GET /api/auth/get-session"
private const val BOTS = "GET /v1/agents"

private fun session(theme: String) = """{"user":{"id":"me","role":"user","theme":"$theme"}}"""

private class SettingsTransport : HttpTransport {
    val requests = mutableListOf<HttpRequestData>()
    val responses = mutableMapOf(
        SESSION to (200 to """{"user":{"id":"me","role":"user"}}"""),
        BOTS to (200 to "[]"),
        SAVE_THEME to (200 to """{"status":true}"""),
        "GET /v1/group-chats" to (200 to """{"groups":[]}"""),
    )
    val held = mutableListOf<Pair<String, CompletableDeferred<Pair<Int, String>>>>()

    fun hold(key: String) = CompletableDeferred<Pair<Int, String>>().also { held.add(key to it) }

    override suspend fun execute(request: HttpRequestData): HttpResponseData {
        requests.add(request)
        val key = "${request.method} ${request.pathWithQuery}"
        val hold = held.firstOrNull { it.first == key }
        hold?.let { held.remove(it) }
        val (status, body) = hold?.second?.await() ?: responses[key] ?: (503 to """{"error":"unscripted $key"}""")
        return HttpResponseData(status, body.toByteArray(StandardCharsets.UTF_8))
    }

    override suspend fun openStream(request: HttpRequestData): HttpStreamResponse =
        throw UnsupportedOperationException("streams are not used here")

    fun themesSent() = requests.filter { "${it.method} ${it.pathWithQuery}" == SAVE_THEME }.map { String(it.body!!, StandardCharsets.UTF_8) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GeneralSettingsTests {
    private fun TestScope.model(
        transport: SettingsTransport = SettingsTransport(),
        store: InMemoryCredentialStore = InMemoryCredentialStore(),
        onChanged: () -> Unit = {},
    ) = AppModel(
        client = APIClient(SERVER, "session", transport),
        credentials = store,
        scope = this,
        onNotificationsChanged = onChanged,
    )

    @Test
    fun aThemeAppliesBeforeTheServerAnswers() = runTest {
        val transport = SettingsTransport()
        val store = InMemoryCredentialStore()
        val answer = transport.hold(SAVE_THEME)
        val model = model(transport, store)

        model.chooseTheme(ThemeChoice.Dark)
        runCurrent()

        assertEquals(ThemeChoice.Dark, model.theme)
        assertEquals("dark", store.read("theme"))
        assertEquals(listOf("""{"theme":"dark"}"""), transport.themesSent())

        answer.complete(200 to "{}")
        runCurrent()
        assertEquals(ThemeChoice.Dark, model.theme)
        assertEquals("", model.errorText)
    }

    @Test
    fun aFailedThemeSaveGoesBackAndSaysSo() = runTest {
        val transport = SettingsTransport()
        val store = InMemoryCredentialStore(mapOf("theme" to "light"))
        transport.responses[SAVE_THEME] = 500 to """{"message":"nope"}"""
        val model = model(transport, store)

        model.chooseTheme(ThemeChoice.Dark)
        runCurrent()

        assertEquals(ThemeChoice.Light, model.theme)
        assertEquals("light", store.read("theme"))
        assertTrue(model.errorText.isNotEmpty())
    }

    @Test
    fun aLateFailureKeepsTheNewerChoiceAndSavesIt() = runTest {
        val transport = SettingsTransport()
        val first = transport.hold(SAVE_THEME)
        val model = model(transport)

        model.chooseTheme(ThemeChoice.Dark)
        runCurrent()
        model.chooseTheme(ThemeChoice.Light)
        runCurrent()
        assertEquals(1, transport.themesSent().size)

        first.complete(500 to "{}")
        runCurrent()

        assertEquals(ThemeChoice.Light, model.theme)
        assertEquals("", model.errorText)
        assertEquals("""{"theme":"light"}""", transport.themesSent().last())
    }

    @Test
    fun theAccountsThemeArrivesWithTheSession() = runTest {
        val transport = SettingsTransport()
        val store = InMemoryCredentialStore()
        transport.responses[SESSION] = 200 to session("dark")
        val model = model(transport, store)

        model.refresh()
        runCurrent()

        assertEquals(ThemeChoice.Dark, model.theme)
        assertEquals("dark", store.read("theme"))
    }

    @Test
    fun aSessionReadStartedBeforeAChoiceCannotUndoIt() = runTest {
        val transport = SettingsTransport()
        val read = transport.hold(SESSION)
        val model = model(transport)

        model.refresh()
        runCurrent()
        model.chooseTheme(ThemeChoice.Dark)
        runCurrent()
        read.complete(200 to session("light"))
        runCurrent()

        assertEquals(ThemeChoice.Dark, model.theme)
    }

    @Test
    fun anUnknownAccountThemeIsIgnored() = runTest {
        val transport = SettingsTransport()
        transport.responses[SESSION] = 200 to session("sepia")
        val model = model(transport, InMemoryCredentialStore(mapOf("theme" to "dark")))

        model.refresh()
        runCurrent()

        assertEquals(ThemeChoice.Dark, model.theme)
    }

    @Test
    fun signedOutThemesStayOnTheDevice() = runTest {
        val transport = SettingsTransport()
        val store = InMemoryCredentialStore()
        val model = AppModel(client = APIClient(SERVER, "", transport), credentials = store, scope = this)

        model.chooseTheme(ThemeChoice.Light)
        runCurrent()

        assertEquals(ThemeChoice.Light, model.theme)
        assertEquals("light", store.read("theme"))
        assertTrue(transport.themesSent().isEmpty())
    }

    @Test
    fun aSaveInterruptedBySignOutDoesNotBlockTheNextOne() = runTest {
        val transport = SettingsTransport()
        transport.responses["POST /api/auth/sign-in/email"] = 200 to """{"token":"next"}"""
        transport.hold(SAVE_THEME)
        val model = AppModel(
            client = APIClient(SERVER, "session", transport),
            scope = backgroundScope,
            transport = transport,
        )
        model.serverURL = SERVER

        model.chooseTheme(ThemeChoice.Dark)
        runCurrent()
        model.signOut()
        runCurrent()
        model.email = "me@example.com"
        model.password = "secret"
        model.saveSettings()
        runCurrent()
        model.chooseTheme(ThemeChoice.Light)
        runCurrent()

        assertEquals("""{"theme":"light"}""", transport.themesSent().last())
    }

    @Test
    fun notificationChoicesDefaultOnPersistAndReachTheActivity() = runTest {
        val store = InMemoryCredentialStore()
        var changes = 0
        val model = model(store = store, onChanged = { changes += 1 })

        assertTrue(model.replyNotifications)
        assertTrue(model.notificationSound)

        model.changeReplyNotifications(false)
        model.changeNotificationSound(false)

        assertEquals(1, changes)
        assertFalse(NotificationSettings(store).replies)
        assertFalse(NotificationSettings(store).sound)
        assertFalse(model(store = store).replyNotifications)
        assertFalse(model(store = store).notificationSound)
    }

    @Test
    fun theReplyRowExplainsWhyNothingShows() {
        assertEquals("Replies from chats you don't have open show as notifications.", replyNotificationsDescription(true, true))
        assertEquals("Off on this device.", replyNotificationsDescription(false, true))
        assertEquals("Blocked for Crumble in Android's settings.", replyNotificationsDescription(true, false))
        assertEquals("Blocked for Crumble in Android's settings.", replyNotificationsDescription(false, false))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeTests {
    private fun TestScope.model(transport: SettingsTransport) =
        AppModel(client = APIClient(SERVER, "session", transport), scope = backgroundScope)

    @Test
    fun greetingsFollowTheHour() {
        assertEquals(
            listOf("Up late", "Good morning", "Good morning", "Good afternoon", "Good afternoon", "Good evening", "Good evening"),
            listOf(4, 5, 11, 12, 17, 18, 23).map(::greeting),
        )
    }

    @Test
    fun theStatusLineLeadsWithWork() {
        val bot = Agent(id = "a", enabled = true, description = "Reads the mail")
        assertEquals("Working…", botStatusLine(bot, true))
        assertEquals("Reads the mail", botStatusLine(bot, false))
        assertEquals("Ready", botStatusLine(bot.copy(description = ""), false))
        assertEquals("Paused", botStatusLine(bot.copy(enabled = false), false))
    }

    @Test
    fun startingUpStaysHomeWithoutOpeningAChat() = runTest {
        val transport = SettingsTransport()
        transport.responses[BOTS] = 200 to """[${agentFixture("a1")},${agentFixture("a2")}]"""
        val model = model(transport)

        model.refresh()
        runCurrent()

        assertNull(model.route)
        assertEquals(listOf("a1", "a2"), model.agents.map { it.id })
        assertTrue(transport.requests.none { it.pathWithQuery.contains("/chat") })
    }

    @Test
    fun aDeletedBotLeavesNoStaleSelectionOnHome() = runTest {
        val transport = SettingsTransport()
        transport.responses[BOTS] = 200 to """[${agentFixture("a2")}]"""
        val model = model(transport)
        model.agents = listOf(Agent(id = "a1"), Agent(id = "a2"))
        model.selectAgent("a1")
        model.navigate(null)

        model.refresh()
        runCurrent()

        assertEquals("a2", model.selectedAgent?.id)
        assertNull(model.route)
    }

    @Test
    fun aRefreshKeepsAnOpenChatOpen() = runTest {
        val transport = SettingsTransport()
        transport.responses[BOTS] = 200 to """[${agentFixture("a1")}]"""
        val model = model(transport)
        model.agents = listOf(Agent(id = "a1"))
        model.selectAgent("a1")

        model.refresh()
        runCurrent()

        assertEquals(Route.AgentChat("a1"), model.route)
    }

    @Test
    fun goingHomeLeavesThePage() = runTest {
        val model = model(SettingsTransport())
        model.route = Route.Memory

        model.navigate(null)

        assertNull(model.route)
    }

    @Test
    fun homeReadsEveryBotsLiveStatus() = runTest {
        val transport = SettingsTransport()
        transport.responses["GET /v1/agents/a1/chat?limit=1"] =
            200 to snapshotFixture(ChatSnapshot(agentId = "a1", status = "running"))
        transport.responses["GET /v1/agents/a2/chat?limit=1"] =
            200 to snapshotFixture(ChatSnapshot(agentId = "a2", status = "idle"))
        val model = model(transport)
        model.agents = listOf(Agent(id = "a1", enabled = true), Agent(id = "a2", enabled = true))

        model.refreshBotStatuses()

        assertTrue(model.isWorking("a1"))
        assertFalse(model.isWorking("a2"))
    }
}

class CommandPaletteTests {
    private val bots = listOf(
        Agent(id = "a1", name = "Atlas", enabled = true),
        Agent(id = "a2", name = "Atlas", enabled = false),
        Agent(id = "pending:1", name = "Saving", enabled = true),
    )
    private val groups = listOf(
        GroupChat("g1", "Family", listOf("a1", "a2"), "t", "t"),
        GroupChat("pending:2", "Unsaved", listOf("a1", "a2"), "t", "t"),
    )

    private fun sections(agents: List<Agent> = bots, admin: Boolean = false, theme: ThemeChoice = ThemeChoice.System) =
        paletteSections(agents, groups, admin, theme)

    private fun titles(list: List<PaletteSection>, heading: String) =
        list.firstOrNull { it.heading == heading }?.items?.map { it.title }.orEmpty()

    @Test
    fun itOffersWhatTheWebsiteOffers() {
        val all = sections()
        assertEquals(listOf("Bots", "Group chats", "Create", "Workspace", "Settings", "Theme"), all.map { it.heading })
        assertEquals(listOf("Family"), titles(all, "Group chats"))
        assertEquals(listOf("New bot", "New group chat"), titles(all, "Create"))
        assertEquals("General", titles(all, "Settings").first())
        assertEquals(listOf("System theme", "Light theme", "Dark theme"), titles(all, "Theme"))
    }

    @Test
    fun unsavedRecordsStayOut() {
        assertEquals(listOf("a1", "a2"), sections().first().items.map { (it.action as PaletteAction.OpenBot).id })
    }

    @Test
    fun pausedBotsAndTheCurrentThemeAreMarked() {
        val all = sections(theme = ThemeChoice.Dark)
        assertEquals(listOf(null, "Paused"), all.first().items.map { it.note })
        assertEquals(listOf(null, null, "Current"), all.last().items.map { it.note })
    }

    @Test
    fun usersIsForAdmins() {
        assertFalse("Users" in titles(sections(), "Settings"))
        assertTrue("Users" in titles(sections(admin = true), "Settings"))
    }

    @Test
    fun aGroupNeedsTwoBots() {
        assertEquals(listOf("New bot"), titles(sections(agents = bots.take(1)), "Create"))
    }

    @Test
    fun equalNamesBothMatchAndCommandWordsDoNotFindBots() {
        assertEquals(2, searchPalette(sections(), "atlas").single().items.size)
        assertEquals(emptyList<String>(), titles(searchPalette(sections(), "bot"), "Bots"))
    }

    @Test
    fun aBlankSearchKeepsEverything() {
        assertEquals(sections(), searchPalette(sections(), "  "))
    }

    @Test
    fun lettersInOrderMatchIgnoringCase() {
        assertEquals(listOf("Providers"), titles(searchPalette(sections(), "PRVD"), "Settings"))
        assertTrue(searchPalette(sections(), "zzzz").isEmpty())
    }

    @Test
    fun theBestMatchLeads() {
        val found = searchPalette(sections(), "memory")
        assertEquals("Memory", found.first().items.first().title)
        val ranked = searchPalette(sections(), "web")
        assertEquals(listOf("Settings"), ranked.map { it.heading }.take(1))
        assertEquals("Web search", ranked.first().items.first().title)
    }

    @Test
    fun theWebsitesWordsFindTheirAndroidPages() {
        assertEquals(listOf("Automations"), titles(searchPalette(sections(), "automations"), "Workspace"))
        assertEquals(listOf("Dark theme"), titles(searchPalette(sections(), "theme dark"), "Theme"))
        assertEquals(listOf("Dark theme"), titles(searchPalette(sections(), "dark theme"), "Theme"))
        assertEquals("Providers", titles(searchPalette(sections(), "settings prov"), "Settings").first())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CommandPaletteActionTests {
    private fun TestScope.model(): AppModel {
        val model = AppModel(client = APIClient(SERVER, "session", SettingsTransport()), scope = backgroundScope)
        model.showingPalette = true
        return model
    }

    @Test
    fun aPageOpensAndThePaletteCloses() = runTest {
        val model = model()
        model.runPalette(PaletteAction.OpenPage(Route.Activity))
        assertEquals(Route.Activity, model.route)
        assertFalse(model.showingPalette)
    }

    @Test
    fun generalOpensItsSettingsPage() = runTest {
        val model = model()
        model.runPalette(PaletteAction.OpenPage(Route.General))
        assertEquals(Route.General, model.route)
    }

    @Test
    fun creatingOpensTheForms() = runTest {
        val model = model()
        model.runPalette(PaletteAction.NewBot)
        assertTrue(model.agentDraft?.isNew == true)
        model.runPalette(PaletteAction.NewGroup)
        assertTrue(model.groupDraft != null)
    }

    @Test
    fun aThemeChangesInPlace() = runTest {
        val model = model()
        model.runPalette(PaletteAction.SetTheme(ThemeChoice.Dark))
        assertEquals(ThemeChoice.Dark, model.theme)
        assertFalse(model.showingPalette)
    }
}
