package dev.crumble.android

import dev.crumble.android.generated.BotAvatarDefinition
import dev.crumble.android.generated.LearnedChange
import dev.crumble.android.generated.MemorySource
import dev.crumble.android.model.ActivityRecord
import dev.crumble.android.model.Agent
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.BulkAction
import dev.crumble.android.model.CrumbleJson
import dev.crumble.android.model.DiffLine
import dev.crumble.android.model.Memory
import dev.crumble.android.model.Route
import dev.crumble.android.model.SKILLS_SHARED
import dev.crumble.android.model.SharedSkill
import dev.crumble.android.model.SkillDraft
import dev.crumble.android.model.SkillInstallForm
import dev.crumble.android.model.applyBulkAction
import dev.crumble.android.model.diff
import dev.crumble.android.model.lineDiff
import dev.crumble.android.model.memoryAge
import dev.crumble.android.model.memoryLine
import dev.crumble.android.model.memorySourceLabel
import dev.crumble.android.model.revertPrompt
import dev.crumble.android.model.selectionCatalog
import dev.crumble.android.model.skillGlimpse
import dev.crumble.android.model.skippedNote
import dev.crumble.android.model.visibleIn
import dev.crumble.android.network.APIClient
import dev.crumble.android.network.HttpRequestData
import dev.crumble.android.network.HttpResponseData
import dev.crumble.android.network.HttpStreamResponse
import dev.crumble.android.network.HttpTransport
import dev.crumble.android.ui.placeholderAvatar
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TIME = "2026-10-18T10:00:00Z"

private fun packageJson(
    id: String,
    agentId: String? = "a1",
    name: String = "Triage",
    instructions: String = "Sort things",
    pinned: Boolean = false,
    origin: String? = null,
) = """{"id":"$id","name":"$name","instructions":"$instructions","createdAt":"$TIME","updatedAt":"$TIME","agentId":${agentId?.let { "\"$it\"" } ?: "null"},"description":"","documents":[{"path":"ref.md","content":"Doc"}],"createdBy":"owner","sourceExecutionId":null,"useCount":0,"lastUsedAt":null,"state":"active","pinned":$pinned${origin?.let { ",\"origin\":$it" } ?: ""}}"""

private const val ORIGIN = """{"repository":"owner/repo","branch":"main","path":"skills/triage","commit":"abcdef123456","digest":"d"}"""

private fun sharedRow(id: String, update: String = "null", origin: String = ORIGIN) =
    """{"id":"$id","name":"$id","instructions":"Do it","createdAt":"$TIME","updatedAt":"$TIME","source":"shared","description":"Short","origin":$origin,"update":$update}"""

private fun skillJson(id: String, name: String = "Saved") =
    """{"id":"$id","name":"$name","instructions":"Do it","createdAt":"$TIME","updatedAt":"$TIME"}"""

private fun memoryJson(id: String, content: String, agentId: String? = null, source: String = "null") =
    """{"id":"$id","agentId":${agentId?.let { "\"$it\"" } ?: "null"},"content":"$content","source":$source,"createdAt":"$TIME","updatedAt":"$TIME"}"""

private fun changeJson(id: String, before: String = "null", after: String = "null", target: String = "memory", targetId: String = "m1", agentId: String? = null, action: String = "update") =
    """{"id":"$id","agentId":${agentId?.let { "\"$it\"" } ?: "null"},"actor":"bot","target":"$target","targetId":"$targetId","action":"$action","before":$before,"after":$after,"source":null,"revertsId":null,"createdAt":"$TIME"}"""

private fun listing(vararg rows: String) =
    """{"repository":"owner/repo","branch":"main","commit":"c1","path":"","skills":[${rows.joinToString(",")}],"omitted":0}"""

private fun candidate(path: String, status: String = "new") =
    """{"path":"$path","name":"$path","description":"","status":"$status","problem":null,"skipped":[],"skillId":null}"""

private fun changeOf(json: String) = CrumbleJson.decodeFromString(LearnedChange.serializer(), json)

/** Answers by "METHOD path", holding back any request whose key is in `held`. */
private class Script : HttpTransport {
    val requests: MutableList<HttpRequestData> = Collections.synchronizedList(mutableListOf())
    val held = mutableMapOf<String, CompletableDeferred<Unit>>()
    val responses = mutableMapOf<String, Pair<Int, String>>()
    /** Answers before `responses`, for requests told apart by their body. */
    var override: (HttpRequestData) -> Pair<Int, String>? = { null }
    var running = 0
    var mostRunning = 0

    override suspend fun execute(request: HttpRequestData): HttpResponseData {
        val key = "${request.method} ${request.pathWithQuery}"
        requests.add(request)
        synchronized(this) {
            running += 1
            mostRunning = maxOf(mostRunning, running)
        }
        delay(1)
        held[key]?.await()
        synchronized(this) { running -= 1 }
        val (status, body) = override(request) ?: responses[key] ?: (500 to """{"error":"unscripted $key"}""")
        return HttpResponseData(status, body.toByteArray(StandardCharsets.UTF_8))
    }

    override suspend fun openStream(request: HttpRequestData) =
        HttpStreamResponse(404, ByteArrayInputStream(ByteArray(0)))

    fun hold(key: String) = CompletableDeferred<Unit>().also { held[key] = it }
    fun sent(key: String) = requests.filter { "${it.method} ${it.pathWithQuery}" == key }
    fun bodyOf(key: String) = sent(key).single().body!!.toString(StandardCharsets.UTF_8).let { CrumbleJson.parseToJsonElement(it).jsonObject }
}

private val BOTS = listOf(Agent(id = "a1", name = "Ada", enabled = true), Agent(id = "a2", name = "Kev", enabled = true))

@OptIn(ExperimentalCoroutinesApi::class)
class SkillsMemoryAvatarTests {
    private fun model(scope: TestScope, transport: Script) = AppModel(
        client = APIClient("http://example.com", "token", transport),
        scope = scope.backgroundScope,
    ).also { it.agents = BOTS }

    private fun TestScope.settle() {
        testScheduler.advanceTimeBy(50)
        runCurrent()
    }

    // Skills page

    @Test
    fun personalSkillsLoadPerBotAndOneFailureKeepsTheRest() = runTest {
        val transport = Script().apply {
            responses["GET /v1/agents"] = 200 to "[${agentFixture("a1", "Ada")},${agentFixture("a2", "Kev")}]"
            responses["GET /v1/skills"] = 200 to "[${sharedRow("s1")}]"
            responses["GET /v1/agents/a1/skills"] = 200 to "[${packageJson("p1")}]"
            responses["GET /v1/agents/a2/skills"] = 500 to """{"error":"busy"}"""
        }
        val app = model(this, transport)

        app.openPanel(Route.Skills)
        settle()

        assertEquals(listOf("p1"), app.agentSkills["a1"]?.map { it.id })
        assertEquals("busy", app.agentSkillErrors["a2"])
        assertEquals(listOf("s1"), app.sharedSkills.map { it.id })
        assertEquals("Short", app.sharedSkills.single().description)
        assertEquals("owner/repo", app.sharedSkills.single().origin?.repository)
    }

    @Test
    fun aDelayedPersonalSkillShowsAtOnceAndReconcilesWithoutDuplicates() = runTest {
        val transport = Script().apply { responses["POST /v1/agents/a1/skills"] = 201 to packageJson("p9", name = "New") }
        val gate = transport.hold("POST /v1/agents/a1/skills")
        val app = model(this, transport)
        app.skillDraft = SkillDraft(skillID = "", agentID = "a1", name = "New", instructions = "Do")

        app.saveSkill()
        settle()
        assertEquals(listOf("New"), app.agentSkills["a1"]?.map { it.name })
        assertTrue(app.agentSkills["a1"]!!.single().id.startsWith("pending-"))
        assertNull(app.skillDraft)
        app.agents = app.agents.map { it.copy(description = "Newer") }

        gate.complete(Unit)
        settle()

        assertEquals(listOf("p9"), app.agentSkills["a1"]?.map { it.id })
        assertEquals(listOf("New"), app.agents.first().skills?.map { it.name })
        assertEquals("Newer", app.agents.first().description)
        assertEquals(setOf("name", "instructions"), transport.bodyOf("POST /v1/agents/a1/skills").keys)
    }

    @Test
    fun editingAPersonalSkillRestoresItsPlaceAndTheDraftOnFailure() = runTest {
        val transport = Script().apply { responses["PATCH /v1/agents/a1/skills/p1"] = 500 to """{"error":"no"}""" }
        val gate = transport.hold("PATCH /v1/agents/a1/skills/p1")
        val app = model(this, transport)
        app.route = Route.Skills
        app.agentSkills = mapOf("a1" to listOf(packageOf("p1", "One"), packageOf("p2", "Two")))
        app.openSkillForm("p1", "a1")
        app.skillDraft = app.skillDraft!!.copy(name = "Edited")

        app.saveSkill()
        settle()
        assertEquals(listOf("Edited", "Two"), app.agentSkills["a1"]?.map { it.name })
        gate.complete(Unit)
        settle()

        assertEquals(listOf("One", "Two"), app.agentSkills["a1"]?.map { it.name })
        assertEquals("Edited", app.skillDraft?.name)
        assertEquals("no", app.errorText)
    }

    private fun packageOf(id: String, name: String, pinned: Boolean = false) =
        CrumbleJson.decodeFromString(dev.crumble.android.generated.SkillPackage.serializer(), packageJson(id, name = name, pinned = pinned))

    @Test
    fun pinAndDeleteApplyAtOnceAndRollBack() = runTest {
        val transport = Script().apply {
            responses["PATCH /v1/agents/a1/skills/p1"] = 500 to """{"error":"no"}"""
            responses["DELETE /v1/agents/a1/skills/p2"] = 500 to """{"error":"no"}"""
        }
        val pinGate = transport.hold("PATCH /v1/agents/a1/skills/p1")
        val deleteGate = transport.hold("DELETE /v1/agents/a1/skills/p2")
        val app = model(this, transport)
        app.agentSkills = mapOf("a1" to listOf(packageOf("p1", "One"), packageOf("p2", "Two")))

        app.toggleSkillPin("a1", "p1")
        app.deleteAgentSkill("a1", "p2")
        settle()
        assertEquals(listOf(true), app.agentSkills["a1"]?.map { it.pinned })
        assertEquals("""{"pinned":true}""", transport.sent("PATCH /v1/agents/a1/skills/p1").single().body!!.toString(StandardCharsets.UTF_8))

        pinGate.complete(Unit)
        deleteGate.complete(Unit)
        settle()

        assertEquals(listOf("p1", "p2"), app.agentSkills["a1"]?.map { it.id })
        assertEquals(listOf(false, false), app.agentSkills["a1"]?.map { it.pinned })
    }

    @Test
    fun aNewSharedSkillAssignsItsBotsAndAFailedAssignmentLeavesTheSkillSaved() = runTest {
        val transport = Script().apply {
            responses["POST /v1/skills"] = 201 to skillJson("s9", "New")
            responses["POST /v1/skills/s9/assignment"] = 500 to """{"error":"full"}"""
        }
        val gate = transport.hold("POST /v1/skills")
        val app = model(this, transport)
        app.skillDraft = SkillDraft(skillID = "", agentID = null, name = "New", instructions = "Do", usedBy = setOf("a1"))

        app.saveSkill()
        settle()
        val provisional = app.sharedSkills.single().id
        assertTrue(provisional.startsWith("pending-"))
        assertEquals(listOf(provisional), app.agents.first().sharedSkills)

        gate.complete(Unit)
        settle()

        assertEquals(listOf("s9"), app.sharedSkills.map { it.id })
        assertEquals(emptyList<String>(), app.agents.first().sharedSkills)
        assertEquals("full", app.errorText)
        assertNull(app.skillDraft)
    }

    @Test
    fun editingUsedByAssignsAndUnassignsOnlyBotsThatDiffer() = runTest {
        val transport = Script().apply {
            responses["PATCH /v1/skills/s1"] = 200 to skillJson("s1")
            responses["POST /v1/skills/s1/assignment"] = 200 to """{"skipped":false}"""
        }
        val app = model(this, transport)
        app.sharedSkills = listOf(SharedSkill(id = "s1", name = "Old", instructions = "Do", source = "shared", description = "Keep"))
        app.agents = listOf(BOTS[0].copy(sharedSkills = listOf("s1")), BOTS[1].copy(sharedSkills = emptyList()))
        app.openSkillForm("s1")
        assertEquals(setOf("a1"), app.skillDraft?.usedBy)
        app.skillDraft = app.skillDraft!!.copy(usedBy = setOf("a2"))

        app.saveSkill()
        settle()

        assertEquals(listOf(emptyList(), listOf("s1")), app.agents.map { it.sharedSkills })
        assertEquals(2, transport.sent("POST /v1/skills/s1/assignment").size)
        assertEquals("Keep", app.sharedSkills.single().description)
    }

    @Test
    fun copyingAnInstalledSkillSendsItsDocumentsAndDropsTheOriginAndRecovers() = runTest {
        val transport = Script().apply {
            responses["GET /v1/skills/s1"] = 200 to packageJson("s1", agentId = null, name = "Triage", origin = ORIGIN)
            responses["POST /v1/skills"] = 201 to skillJson("s2", "Triage copy")
        }
        val gate = transport.hold("POST /v1/skills")
        val app = model(this, transport)
        app.sharedSkills = listOf(SharedSkill(id = "s1", name = "Triage", source = "shared"))

        app.copySkill("s1")
        settle()
        assertEquals(listOf("Triage", "Triage copy"), app.sharedSkills.map { it.name })
        assertNull(app.sharedSkills.last().origin)
        gate.complete(Unit)
        settle()
        assertEquals(listOf("s1", "s2"), app.sharedSkills.map { it.id })
        val body = transport.bodyOf("POST /v1/skills")
        assertEquals("\"Triage copy\"", body["name"].toString())
        assertEquals("""[{"path":"ref.md","content":"Doc"}]""", body["documents"].toString())

        transport.responses["GET /v1/skills/s1"] = 500 to """{"error":"gone"}"""
        app.copySkill("s1")
        settle()
        assertEquals(listOf("s1", "s2"), app.sharedSkills.map { it.id })
        assertEquals("gone", app.errorText)
    }

    @Test
    fun updateCheckStaysPendingUntilAnsweredAndReportsFailures() = runTest {
        val transport = Script().apply {
            responses["POST /v1/skills/updates/check"] = 200 to """{"failed":[{"repository":"o/r","branch":"main","message":"404"}]}"""
            responses["GET /v1/agents"] = 200 to "[]"
            responses["GET /v1/skills"] = 200 to "[]"
        }
        val gate = transport.hold("POST /v1/skills/updates/check")
        val app = model(this, transport)

        app.checkSkillUpdates()
        app.checkSkillUpdates()
        settle()
        assertTrue(app.checkingSkillUpdates)
        gate.complete(Unit)
        settle()

        assertFalse(app.checkingSkillUpdates)
        assertEquals(listOf("Couldn't check o/r (main): 404"), app.skillCheckFailures)
        assertEquals(1, transport.sent("POST /v1/skills/updates/check").size)
    }

    // GitHub install

    @Test
    fun aLateBrowseAnswerNeverReplacesWhatWasTypedSince() = runTest {
        val transport = Script().apply { responses["POST /v1/skills/github/browse"] = 200 to listing(candidate("a")) }
        val gate = transport.hold("POST /v1/skills/github/browse")
        val app = model(this, transport)
        app.openSkillInstall()
        app.changeSkillLocation("owner/repo")

        app.findSkills()
        settle()
        assertTrue(app.skillInstall?.browsing == true)
        app.changeSkillLocation("owner/other")
        gate.complete(Unit)
        settle()

        assertEquals("owner/other", app.skillInstall?.location)
        assertNull(app.skillInstall?.listing)
        assertFalse(app.skillInstall?.browsing == true)
    }

    @Test
    fun browsingChecksOnlyNewSkillsAndAFailureStaysInTheSheet() = runTest {
        val transport = Script().apply {
            responses["POST /v1/skills/github/browse"] = 200 to listing(candidate("a"), candidate("b", "installed"))
        }
        val app = model(this, transport)
        app.openSkillInstall()
        app.changeSkillLocation(" owner/repo ")

        app.findSkills()
        settle()
        assertEquals(setOf("a"), app.skillInstall?.picked)
        assertEquals("\"owner/repo\"", transport.bodyOf("POST /v1/skills/github/browse")["location"].toString())

        transport.responses["POST /v1/skills/github/browse"] = 502 to """{"error":"GitHub is down"}"""
        app.findSkills()
        settle()
        assertEquals("GitHub is down", app.skillInstall?.error)
    }

    @Test
    fun installStaysPendingUntilConfirmedAndKeepsPicksOnFailure() = runTest {
        val transport = Script().apply {
            responses["POST /v1/skills/github/install"] = 502 to """{"error":"Rate limited"}"""
            responses["GET /v1/agents"] = 200 to "[]"
            responses["GET /v1/skills"] = 200 to "[${sharedRow("s1")}]"
        }
        val gate = transport.hold("POST /v1/skills/github/install")
        val app = model(this, transport)
        app.skillInstall = SkillInstallForm(
            location = "owner/repo",
            listing = CrumbleJson.decodeFromString(dev.crumble.android.generated.SkillListing.serializer(), listing(candidate("a"), candidate("b"))),
            picked = setOf("b"),
            botIDs = setOf("a2"),
        )

        app.installSkills()
        settle()
        assertTrue(app.skillInstall?.installing == true)
        assertTrue(app.sharedSkills.isEmpty())
        gate.complete(Unit)
        settle()

        assertEquals("Rate limited", app.skillInstall?.error)
        assertEquals(setOf("b"), app.skillInstall?.picked)
        assertFalse(app.skillInstall?.installing == true)
        val body = transport.bodyOf("POST /v1/skills/github/install")
        assertEquals("""["b"]""", body["paths"].toString())
        assertEquals("""["a2"]""", body["botIds"].toString())
        assertEquals("\"c1\"", body["commit"].toString())

        transport.responses["POST /v1/skills/github/install"] = 200 to "[${packageJson("s1", agentId = null, origin = ORIGIN)}]"
        transport.held.clear()
        app.installSkills()
        settle()
        assertNull(app.skillInstall)
        assertEquals(listOf("s1"), app.sharedSkills.map { it.id })
    }

    @Test
    fun applyingAnUpdateChangesTheRowOnlyOnceConfirmed() = runTest {
        val pendingJson = """{"commit":"c2","name":"Triage","description":"","instructions":"New line","documents":[],"skipped":["big.bin"]}"""
        val transport = Script().apply {
            responses["GET /v1/skills/s1"] = 200 to packageJson("s1", agentId = null, origin = ORIGIN)
            responses["GET /v1/skills/s1/update"] = 200 to pendingJson
            responses["POST /v1/skills/s1/update"] = 500 to """{"error":"Conflict"}"""
            responses["GET /v1/agents"] = 200 to "[]"
            responses["GET /v1/skills"] = 200 to "[${sharedRow("s1")}]"
        }
        val gate = transport.hold("POST /v1/skills/s1/update")
        val app = model(this, transport)
        app.sharedSkills = listOf(SharedSkill(id = "s1", name = "Triage", instructions = "Old", source = "shared"))

        app.openSkillUpdate("s1")
        settle()
        assertEquals(listOf("big.bin"), app.skillUpdate?.pending?.skipped)
        assertNotNull(app.skillUpdate?.current)

        app.applySkillUpdate()
        settle()
        assertTrue(app.skillUpdate?.applying == true)
        assertEquals("Old", app.sharedSkills.single().instructions)
        gate.complete(Unit)
        settle()
        assertEquals("Conflict", app.skillUpdate?.error)
        assertEquals("Old", app.sharedSkills.single().instructions)

        transport.held.clear()
        transport.responses["POST /v1/skills/s1/update"] = 200 to packageJson("s1", agentId = null, instructions = "New line", origin = ORIGIN)
        app.applySkillUpdate()
        settle()
        assertNull(app.skillUpdate)
    }

    // Bulk actions

    private fun catalog(app: AppModel) = selectionCatalog(app.sharedSkills, app.agents, app.agentSkills)

    @Test
    fun defaultSkillsNeverEnterTheSelectionAndScopesFilterIt() = runTest {
        val app = model(this, Script())
        app.sharedSkills = listOf(
            SharedSkill(id = "d1", name = "Tasks", source = "bundled"),
            SharedSkill(id = "s1", name = "Shared", source = "shared"),
        )
        app.agentSkills = mapOf("a1" to listOf(packageOf("p1", "Own")))

        val skills = catalog(app)

        assertEquals(listOf("s1", "p1"), skills.map { it.id })
        assertEquals(listOf("s1"), skills.visibleIn(SKILLS_SHARED, "").map { it.id })
        assertEquals(listOf("p1"), skills.visibleIn("a1", "").map { it.id })
        assertEquals(listOf("p1"), skills.visibleIn("all", "own").map { it.id })
    }

    @Test
    fun bulkDeleteRemovesAtOnceAndRestoresOnlyFailuresWhichStaySelected() = runTest {
        val transport = Script().apply {
            responses["DELETE /v1/skills/s1"] = 204 to ""
            responses["DELETE /v1/agents/a1/skills/p1"] = 500 to """{"error":"locked"}"""
            responses["GET /v1/agents"] = 200 to "[${agentFixture("a1", "Ada")}]"
            responses["GET /v1/skills"] = 200 to "[]"
            responses["GET /v1/agents/a1/skills"] = 200 to "[${packageJson("p1")}]"
        }
        val gate = transport.hold("DELETE /v1/agents/a1/skills/p1")
        val app = model(this, transport)
        app.sharedSkills = listOf(SharedSkill(id = "s1", name = "One", source = "shared"))
        app.agents = listOf(BOTS[0].copy(sharedSkills = listOf("s1")), BOTS[1])
        app.agentSkills = mapOf("a1" to listOf(packageOf("p1", "Own")))
        app.selectedSkills = setOf("s1", "p1")

        app.runBulkAction(BulkAction.delete)
        settle()
        assertTrue(app.sharedSkills.isEmpty())
        assertTrue(app.agentSkills["a1"]!!.isEmpty())
        assertEquals(emptyList<String>(), app.agents.first().sharedSkills)
        assertTrue("skills:bulk" in app.pendingMutations)

        gate.complete(Unit)
        settle()

        assertEquals(listOf("p1"), app.agentSkills["a1"]?.map { it.id })
        assertTrue(app.sharedSkills.isEmpty())
        assertEquals(setOf("p1"), app.selectedSkills)
        assertEquals(
            listOf("1 of 2 skills processed; 1 failed and remain selected.", "Failed Own (Ada): locked"),
            app.bulkReport.map { it.text },
        )
    }

    @Test
    fun bulkDeleteRestoresASharedSkillToTheBotsThatUsedIt() = runTest {
        val transport = Script().apply { responses["DELETE /v1/skills/s1"] = 500 to """{"error":"no"}""" }
        val app = model(this, transport)
        app.sharedSkills = listOf(SharedSkill(id = "s1", name = "One", source = "shared"))
        app.agents = listOf(BOTS[0].copy(sharedSkills = listOf("s1")))
        app.selectedSkills = setOf("s1")

        app.runBulkAction(BulkAction.delete)
        settle()

        assertEquals(listOf("s1"), app.sharedSkills.map { it.id })
        assertEquals(listOf("s1"), app.agents.first().sharedSkills)
    }

    @Test
    fun bulkAssignAppliesAtOnceAndReportsSkippedBots() = runTest {
        val transport = Script().apply {
            responses["POST /v1/skills/s1/assignment"] = 200 to """{"skipped":true}"""
            responses["GET /v1/agents"] = 500 to """{"error":"later"}"""
            responses["GET /v1/skills"] = 200 to "[]"
        }
        val app = model(this, transport)
        app.sharedSkills = listOf(SharedSkill(id = "s1", name = "One", source = "shared"))
        app.agents = BOTS.map { it.copy(sharedSkills = emptyList()) }
        app.selectedSkills = setOf("s1")

        app.runBulkAction(BulkAction.assign, setOf("a1", "a2"))
        runCurrent()
        assertEquals(listOf(listOf("s1"), listOf("s1")), app.agents.map { it.sharedSkills })
        settle()

        assertEquals(emptySet<String>(), app.selectedSkills)
        assertEquals(
            listOf(
                "1 of 1 skills processed.",
                "Skipped One (Shared) → Ada: already in the requested state.",
                "Skipped One (Shared) → Kev: already in the requested state.",
            ),
            app.bulkReport.map { it.text },
        )
        assertEquals("""{"botId":"a1","assigned":true}""", transport.requests.first { it.body != null }.body!!.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun bulkAssignRollsBackOnlyTheFailedBot() = runTest {
        val transport = Script().apply {
            responses["POST /v1/skills/s1/assignment"] = 200 to """{"skipped":false}"""
            override = { request ->
                if (request.body?.toString(StandardCharsets.UTF_8)?.contains("a2") == true) 500 to """{"error":"full"}""" else null
            }
        }
        val app = model(this, transport)
        app.sharedSkills = listOf(SharedSkill(id = "s1", name = "One", source = "shared"))
        app.agents = BOTS.map { it.copy(sharedSkills = emptyList()) }
        app.selectedSkills = setOf("s1")

        app.runBulkAction(BulkAction.assign, setOf("a1", "a2"))
        settle()

        assertEquals(listOf(listOf("s1"), emptyList()), app.agents.map { it.sharedSkills })
        assertEquals(setOf("s1"), app.selectedSkills)
        assertEquals("Failed One (Shared) → Kev: full", app.bulkReport.last().text)
    }

    @Test
    fun bulkAssignKeepsFailedSkillsSelectedAndCapsConcurrency() = runTest {
        val transport = Script().apply { responses["POST /v1/skills/s1/assignment"] = 500 to """{"error":"full"}""" }
        val app = model(this, transport)
        val many = (1..6).map { Agent(id = "b$it", name = "B$it", enabled = true, sharedSkills = emptyList()) }
        app.agents = many
        app.sharedSkills = listOf(SharedSkill(id = "s1", name = "One", source = "shared"))
        app.selectedSkills = setOf("s1")

        app.runBulkAction(BulkAction.assign, many.map { it.id }.toSet())
        settle()

        assertEquals(setOf("s1"), app.selectedSkills)
        assertEquals(4, transport.mostRunning)
        assertTrue(app.agents.all { it.sharedSkills == emptyList<String>() })
        assertEquals("0 of 1 skills processed; 1 failed and remain selected.", app.bulkReport.first().text)
    }

    @Test
    fun bulkUpdateSkipsWhatItCannotUpdateAndAppliesOnlySelected() = runTest {
        val transport = Script().apply {
            responses["POST /v1/skills/updates/check"] = 200 to """{"failed":[]}"""
            responses["GET /v1/skills"] = 200 to "[${sharedRow("s1", """{"commit":"c","removed":false}""")},${sharedRow("s2")},${sharedRow("s3", """{"commit":"c","removed":false}""")}]"
            responses["POST /v1/skills/s1/update"] = 200 to packageJson("s1", agentId = null)
        }
        val api = APIClient("http://example.com", "token", transport)
        val picked = selectionCatalog(
            listOf(
                SharedSkill(id = "s1", name = "One", source = "shared", origin = origin()),
                SharedSkill(id = "s2", name = "Two", source = "shared", origin = origin()),
                SharedSkill(id = "p", name = "Plain", source = "shared"),
            ),
            emptyList(),
            emptyMap(),
        )

        val result = applyBulkAction(api, BulkAction.update, picked, emptyList())

        assertEquals(emptySet<String>(), result.failed)
        assertEquals(
            listOf(
                "3 of 3 skills processed.",
                "Skipped Two (Shared): already up to date.",
                "Skipped Plain (Shared): not installed from GitHub.",
            ),
            result.messages.map { it.text },
        )
        assertEquals(1, transport.requests.count { it.pathWithQuery.endsWith("/update") })
    }

    @Test
    fun bulkUpdateFailsSkillsWhoseRepositoryCheckFailed() = runTest {
        val transport = Script().apply {
            responses["POST /v1/skills/updates/check"] = 200 to """{"failed":[{"repository":"owner/repo","branch":"main","message":"404"}]}"""
            responses["GET /v1/skills"] = 200 to "[${sharedRow("s1", """{"commit":"c","removed":false}""")}]"
        }
        val api = APIClient("http://example.com", "token", transport)
        val picked = selectionCatalog(listOf(SharedSkill(id = "s1", name = "One", source = "shared", origin = origin())), emptyList(), emptyMap())

        val result = applyBulkAction(api, BulkAction.update, picked, emptyList())

        assertEquals(setOf("s1"), result.failed)
        assertEquals("Failed One (Shared): 404", result.messages.last().text)
    }

    private fun origin() = CrumbleJson.decodeFromString(dev.crumble.android.generated.SkillOrigin.serializer(), ORIGIN)

    @Test
    fun skillTextHelpersMatchTheWebsite() {
        assertEquals("Triage", skillGlimpse("\n## Triage\nSort"))
        assertEquals("No instructions.", skillGlimpse("  \n"))
        assertNull(skippedNote(emptyList()))
        assertEquals("Skips a, b", skippedNote(listOf("a", "b")))
        assertEquals("Skips a, b, c and 2 more", skippedNote(listOf("a", "b", "c", "d", "e")))
    }

    // Memory

    @Test
    fun memoriesLoadPerOwnerAndAFailureStaysWithItsGroup() = runTest {
        val source = """{"kind":"task","name":"Standup"}"""
        val transport = Script().apply {
            responses["GET /v1/agents"] = 200 to "[${agentFixture("a1", "Ada")},${agentFixture("a2", "Kev")}]"
            responses["GET /v1/memories"] = 200 to "[${memoryJson("m1", "Shared", source = source)}]"
            responses["GET /v1/agents/a1/memories"] = 500 to """{"error":"busy"}"""
            responses["GET /v1/agents/a2/memories"] = 200 to "[${memoryJson("m2", "Kev", "a2")}]"
        }
        val app = model(this, transport)
        app.agentMemories = mapOf("a1" to listOf(Memory(id = "old", content = "Kept")))

        app.openPanel(Route.Memory)
        settle()

        assertEquals(mapOf("a1" to "busy"), app.memoryErrors)
        assertEquals(listOf("old"), app.agentMemories["a1"]?.map { it.id })
        assertEquals(listOf("m2"), app.agentMemories["a2"]?.map { it.id })
        val memory = app.sharedMemories.single()
        assertEquals("From the automation Standup", memorySourceLabel(memory))
        assertEquals(TIME, memory.updatedAt)
    }

    @Test
    fun aFailedMemoryEditKeepsItsPlaceAndSource() = runTest {
        val transport = Script().apply { responses["PATCH /v1/memories/m1"] = 500 to """{"error":"no"}""" }
        val gate = transport.hold("PATCH /v1/memories/m1")
        val app = model(this, transport)
        app.route = Route.Memory
        val source = MemorySource(kind = "owner")
        app.sharedMemories = listOf(Memory(id = "m1", content = "One", source = source), Memory(id = "m2", content = "Two"))
        app.openMemoryForm("m1")
        app.memoryDraft = app.memoryDraft!!.copy(content = "Edited")

        app.saveMemory()
        settle()
        assertEquals(source, app.sharedMemories.first().source)
        assertEquals("Edited", app.sharedMemories.first().content)
        gate.complete(Unit)
        settle()

        assertEquals(listOf("One", "Two"), app.sharedMemories.map { it.content })
        assertEquals("Edited", app.memoryDraft?.content)
    }

    @Test
    fun memorySourceAndAgeReadLikeTheWebsite() {
        val now = Instant.parse("2026-10-21T10:00:00Z")
        assertEquals("From the bot chat", memorySourceLabel(Memory(source = MemorySource("chat"))))
        assertEquals("Added by you", memorySourceLabel(Memory(source = MemorySource("owner"))))
        assertEquals("From a request by a bot", memorySourceLabel(Memory(source = MemorySource("bot"))))
        assertEquals("From the group chat Team", memorySourceLabel(Memory(source = MemorySource("group", name = "Team"))))
        assertNull(memorySourceLabel(Memory()))
        assertEquals("3 days ago", memoryAge(TIME, now))
        assertEquals("yesterday", memoryAge("2026-10-20T10:00:00Z", now))
        assertEquals("this minute", memoryAge("2026-10-21T09:59:40Z", now))
        assertEquals("2 hours ago", memoryAge("2026-10-21T08:00:00Z", now))
        assertNull(memoryAge("nonsense", now))
        assertEquals("Added by you · 3 days ago", memoryLine(Memory(source = MemorySource("owner"), updatedAt = TIME), now))
        assertEquals("", memoryLine(Memory(), now))
    }

    // Change history

    @Test
    fun diffsAreLineBasedLikeTheWebsite() {
        assertEquals(
            listOf(DiffLine(' ', "a"), DiffLine('-', "b"), DiffLine('+', "x"), DiffLine('+', "y"), DiffLine(' ', "c")),
            lineDiff(listOf("a", "b", "c"), listOf("a", "x", "y", "c")),
        )
        val skill = changeOf(changeJson("c1", before = "null", after = packageJson("p1", name = "New"), target = "skill", targetId = "p1", agentId = "a1", action = "create"))
        assertEquals(
            listOf("# New", "", "", "Sort things", "", "--- ref.md", "Doc"),
            skill.diff().map { it.text },
        )
        assertTrue(skill.diff().all { it.kind == '+' })
        assertEquals("The skill is deleted again. The revert is recorded, so you can undo it too.", revertPrompt(skill))
        val memory = changeOf(changeJson("c2", before = memoryJson("m1", "Old"), after = memoryJson("m1", "New")))
        assertEquals(listOf(DiffLine('-', "Old"), DiffLine('+', "New")), memory.diff())
        assertTrue(revertPrompt(memory).startsWith("The memory goes back"))
    }

    @Test
    fun changesPageWithTheLastIDAndStopAtAShortPage() = runTest {
        val page = (1..50).joinToString(",") { changeJson("c$it") }
        val transport = Script().apply {
            responses["GET /v1/changes?limit=50"] = 200 to "[$page]"
            responses["GET /v1/changes?limit=50&before=c50"] = 200 to "[${changeJson("c51")}]"
        }
        val app = model(this, transport)

        app.openChanges()
        settle()
        assertTrue(app.changesHasMore)
        app.loadMoreChanges()
        settle()

        assertEquals(51, app.changes.size)
        assertFalse(app.changesHasMore)
    }

    @Test
    fun aFailedHistoryLoadReports() = runTest {
        val transport = Script().apply { responses["GET /v1/changes?limit=50"] = 500 to """{"error":"down"}""" }
        val app = model(this, transport)

        app.openChanges()
        settle()

        assertEquals("down", app.changesError)
        assertFalse(app.changesLoading)
    }

    @Test
    fun revertingRestoresTheRecordAtOnceAndRollsBackOnFailure() = runTest {
        val change = changeOf(changeJson("c1", before = memoryJson("m1", "Before"), after = memoryJson("m1", "After")))
        val transport = Script().apply { responses["POST /v1/changes/c1/revert"] = 500 to """{"error":"no"}""" }
        val gate = transport.hold("POST /v1/changes/c1/revert")
        val app = model(this, transport)
        app.sharedMemories = listOf(Memory(id = "m1", content = "After"), Memory(id = "m2", content = "Other"))

        app.revertChange(change)
        settle()
        assertEquals(listOf("Before", "Other"), app.sharedMemories.map { it.content })
        assertTrue("change:c1" in app.pendingMutations)
        app.sharedMemories = app.sharedMemories.map { if (it.id == "m2") it.copy(content = "Newer") else it }
        gate.complete(Unit)
        settle()

        assertEquals(listOf("After", "Newer"), app.sharedMemories.map { it.content })
        assertEquals("no", app.errorText)
    }

    @Test
    fun revertingACreationDeletesTheRecordAndConfirmsWithTheRevertChange() = runTest {
        val change = changeOf(changeJson("c1", after = packageJson("p1"), target = "skill", targetId = "p1", agentId = "a1", action = "create"))
        val transport = Script().apply {
            responses["POST /v1/changes/c1/revert"] = 201 to changeJson("c2", before = packageJson("p1"), target = "skill", targetId = "p1", agentId = "a1", action = "revert")
        }
        val app = model(this, transport)
        app.agentSkills = mapOf("a1" to listOf(packageOf("p1", "One")))
        app.agents = app.agents.map { it.copy(skills = emptyList()) }
        app.changes = listOf(change)

        app.revertChange(change)
        runCurrent()
        assertTrue(app.agentSkills["a1"]!!.isEmpty())
        settle()

        assertEquals(listOf("c2", "c1"), app.changes.map { it.id })
    }

    @Test
    fun revertingADeletedSharedSkillBringsItBack() = runTest {
        val change = changeOf(changeJson("c1", before = packageJson("s1", agentId = null, name = "Back"), target = "skill", targetId = "s1", action = "delete"))
        val transport = Script().apply { responses["POST /v1/changes/c1/revert"] = 500 to """{"error":"no"}""" }
        val app = model(this, transport)

        app.revertChange(change)
        runCurrent()
        assertEquals(listOf("Back"), app.sharedSkills.map { it.name })
        settle()
        assertTrue(app.sharedSkills.isEmpty())
    }

    @Test
    fun activityFeedKeepsChangesTypedForDiffAndRevert() = runTest {
        val body = """{"activities":[{"id":"c1","agentId":null,"agentName":null,"timestamp":1,"kind":"change","change":${changeJson("c1", before = memoryJson("m1", "Old"), after = memoryJson("m1", "New"))}}],"hasMore":false,"nextCursor":null}"""
        val transport = Script().apply { responses["GET /v1/activity/feed?limit=50"] = 200 to body }
        val app = model(this, transport)

        app.openPanel(Route.Activity)
        settle()

        val record: ActivityRecord = app.activities.single()
        assertEquals("c1", record.change?.id)
    }

    // Avatars

    @Test
    fun savingAnAvatarAppliesAtOnceLocksThePageAndConfirms() = runTest {
        val transport = Script().apply { responses["PATCH /v1/agents/a1"] = 200 to agentFixture("a1", "Ada") }
        val gate = transport.hold("PATCH /v1/agents/a1")
        val app = model(this, transport)
        val edited: BotAvatarDefinition = placeholderAvatar("x").copy(color = "#123456")
        app.pickAvatarBot("a1")
        app.changeAvatar(edited)

        app.saveAvatar()
        settle()
        assertTrue(app.avatarPage.saving)
        assertFalse(app.avatarPage.saved)
        assertEquals("#123456", app.agents.first().avatar?.color)
        app.changeAvatar(edited.copy(color = "#000000"))
        app.pickAvatarBot("a2")
        assertEquals("#123456", app.avatarPage.value.color)
        assertEquals("a1", app.avatarPage.botID)

        gate.complete(Unit)
        settle()

        assertTrue(app.avatarPage.saved)
        assertFalse(app.avatarPage.saving)
        assertEquals("#38bdf8", app.agents.first().avatar?.color)
        val sent = transport.bodyOf("PATCH /v1/agents/a1")
        assertEquals(setOf("avatar"), sent.keys)
        assertEquals("\"#123456\"", sent["avatar"]!!.jsonObject["color"].toString())
        app.changeAvatar(edited)
        assertFalse(app.avatarPage.saved)
    }

    @Test
    fun aFailedAvatarSaveRestoresTheBotAndKeepsTheDraft() = runTest {
        val transport = Script().apply { responses["PATCH /v1/agents/a1"] = 500 to """{"error":"no"}""" }
        val gate = transport.hold("PATCH /v1/agents/a1")
        val app = model(this, transport)
        val original = placeholderAvatar("o")
        val edited = placeholderAvatar("x").copy(color = "#123456")
        app.agents = listOf(BOTS[0].copy(avatar = original), BOTS[1])
        app.pickAvatarBot("a1")
        assertEquals(original, app.avatarPage.value)
        app.changeAvatar(edited)

        app.saveAvatar()
        settle()
        app.agents = app.agents.map { it.copy(description = "Newer") }
        gate.complete(Unit)
        settle()

        assertEquals(original, app.agents.first().avatar)
        assertEquals("Newer", app.agents.first().description)
        assertEquals(edited, app.avatarPage.value)
        assertFalse(app.avatarPage.saving)
        assertFalse(app.avatarPage.saved)
        assertEquals("no", app.errorText)
    }

    @Test
    fun previewOnlyCannotSave() = runTest {
        val transport = Script()
        val app = model(this, transport)
        val value = app.avatarPage.value

        app.saveAvatar()
        app.pickAvatarBot("")
        settle()

        assertEquals(value, app.avatarPage.value)
        assertTrue(transport.requests.isEmpty())
    }
}
