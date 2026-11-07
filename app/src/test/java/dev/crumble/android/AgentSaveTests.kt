package dev.crumble.android

import dev.crumble.android.model.Agent
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.Skill
import dev.crumble.android.network.APIClient
import dev.crumble.android.model.CrumbleJson
import dev.crumble.android.network.HttpRequestData
import dev.crumble.android.network.HttpResponseData
import dev.crumble.android.network.HttpStreamResponse
import dev.crumble.android.network.HttpTransport
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SERVER = "https://crumble.example"

private class RecordingTransport : HttpTransport {
    val requests = mutableListOf<HttpRequestData>()
    var status = 200

    override suspend fun execute(request: HttpRequestData): HttpResponseData {
        requests.add(request)
        return HttpResponseData(status, (if (request.method == "PATCH") agentFixture("a1") else "[]").toByteArray(StandardCharsets.UTF_8))
    }

    override suspend fun openStream(request: HttpRequestData): HttpStreamResponse =
        throw UnsupportedOperationException("streams are not used here")

    fun patch(): JsonObject {
        val body = requests.single { it.method == "PATCH" }.body ?: ByteArray(0)
        return CrumbleJson.parseToJsonElement(String(body, StandardCharsets.UTF_8)).jsonObject
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AgentSaveTests {
    private val bot = Agent(
        id = "a1",
        name = "Kev",
        provider = "openai",
        model = "gpt",
        tools = listOf("memory", "web", "coding", "desktop", "create_bots"),
        skills = listOf(Skill(name = "Triage", instructions = "Sort it")),
        sharedSkills = listOf("shared-1"),
    )

    @Test
    fun savingAnEditKeepsToolsTheEditorDoesNotShow() = runTest {
        val transport = RecordingTransport()
        val model = AppModel(client = APIClient(SERVER, "session", transport), scope = this)
        model.agents = listOf(bot)
        model.openEditor("a1")
        model.agentDraft = model.agentDraft!!.copy(name = "Kevin")
        model.saveAgentEditor()
        runCurrent()

        val tools = transport.patch().getValue("tools").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(bot.tools!!.toSet(), tools.toSet())
    }

    @Test
    fun savingAnEditNeverSubmitsSkillLists() = runTest {
        val transport = RecordingTransport()
        val model = AppModel(client = APIClient(SERVER, "session", transport), scope = this)
        model.agents = listOf(bot)
        model.openEditor("a1")
        model.saveAgentEditor()

        assertEquals(bot.skills, model.agents.single().skills)
        assertEquals(bot.sharedSkills, model.agents.single().sharedSkills)
        runCurrent()

        val patch = transport.patch()
        assertFalse(patch.containsKey("skills"))
        assertFalse(patch.containsKey("sharedSkills"))
    }

    @Test
    fun aSavedSessionNeverGoesToAChangedServerURL() = runTest {
        val transport = RecordingTransport()
        val model = AppModel(client = APIClient(SERVER, "session", transport), scope = this)
        model.token = "session"
        model.serverURL = "http://other.example"
        model.saveSettings()
        runCurrent()

        assertTrue(transport.requests.isEmpty())
        assertEquals("Enter your password to connect to this server.", model.errorText)
    }
}
