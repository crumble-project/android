package dev.crumble.android

import dev.crumble.android.generated.Capabilities
import dev.crumble.android.generated.ScriptRun
import dev.crumble.android.model.Agent
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.CrumbleJson
import dev.crumble.android.model.EventSource
import dev.crumble.android.model.EventSourceDraft
import dev.crumble.android.model.Execution
import dev.crumble.android.model.Route
import dev.crumble.android.model.Schedule
import dev.crumble.android.model.ScheduleStatus
import dev.crumble.android.model.TaskDraft
import dev.crumble.android.model.TaskTab
import dev.crumble.android.model.TaskWhen
import dev.crumble.android.model.choice
import dev.crumble.android.model.choosing
import dev.crumble.android.model.inTimezone
import dev.crumble.android.model.recurrenceOf
import dev.crumble.android.model.recurring
import dev.crumble.android.model.runningAt
import dev.crumble.android.model.canRunScripts
import dev.crumble.android.model.finished
import dev.crumble.android.model.listedSchedules
import dev.crumble.android.model.scriptRunDuration
import dev.crumble.android.model.status
import dev.crumble.android.model.taskRuns
import dev.crumble.android.model.validateTask
import dev.crumble.android.network.APIClient
import dev.crumble.android.network.HttpRequestData
import dev.crumble.android.network.HttpResponseData
import dev.crumble.android.network.HttpStreamResponse
import dev.crumble.android.network.HttpTransport
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class HeldTransport(
    private val handler: (HttpRequestData) -> Pair<Int, String>,
) : HttpTransport {
    val requests = mutableListOf<HttpRequestData>()
    val held = mutableMapOf<String, CompletableDeferred<Unit>>()

    override suspend fun execute(request: HttpRequestData): HttpResponseData {
        requests.add(request)
        held["${request.method} ${request.pathWithQuery}"]?.await()
        val (status, body) = handler(request)
        return HttpResponseData(status, body.toByteArray(StandardCharsets.UTF_8))
    }

    override suspend fun openStream(request: HttpRequestData): HttpStreamResponse =
        throw UnsupportedOperationException("streams are not used here")
}

private fun bodyOf(request: HttpRequestData): JsonObject =
    CrumbleJson.parseToJsonElement(String(request.body!!, StandardCharsets.UTF_8)).jsonObject

private fun scheduleJson(
    id: String = "s1",
    kind: String = "script",
    cron: String? = null,
    runAt: String? = null,
    eventSourceId: String? = "e1",
    enabled: Boolean = true,
    nextRunAt: String? = null,
): String {
    fun quoted(value: String?) = value?.let { "\"$it\"" } ?: "null"
    val command = if (kind == "script") "\"./backup.sh\"" else "null"
    val prompt = if (kind == "script") "" else "Say hi"
    return """{"id":"$id","agentId":"a1","name":"Backup","kind":"$kind","prompt":"$prompt","command":$command,"timeoutSeconds":120,"notify":false,"eventSourceId":${quoted(eventSourceId)},"enabled":$enabled,"timezone":"UTC","runAt":${quoted(runAt)},"cron":${quoted(cron)},"nextRunAt":${quoted(nextRunAt)},"description":"Once","timing":"Once","createdAt":"2026-11-05T10:00:00Z","createdByAgentId":null}"""
}

private fun schedule(json: String = scheduleJson()) = CrumbleJson.decodeFromString(Schedule.serializer(), json)

private fun scriptRun(id: String, scheduleId: String = "s1", status: String = "succeeded", startedAt: String = "2026-11-05T10:00:00Z") =
    ScriptRun(id, scheduleId, "a1", "./backup.sh", status, 0, "done", 0, null, startedAt, "2026-11-05T10:00:01.500Z")

private const val SOURCES = """{"sources":[{"id":"e1","name":"GitHub","createdAt":"2026-11-05T10:00:00Z","usedBy":0}]}"""
private const val CREATED_SOURCE = """{"id":"e2","name":"Linear","createdAt":"2026-11-05T10:00:00Z","usedBy":0,"address":"https://relay.example/e2"}"""
private val CODING = Capabilities(coding = true, desktop = false, jobKinds = listOf("coding"))
private val SOURCE = EventSource("e1", "GitHub", "2026-11-05T10:00:00Z", 0)

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationsTests {
    private fun modelWith(transport: HeldTransport, scope: TestScope): AppModel {
        val client = APIClient(baseURL = "https://example.test", token = "secret", transport = transport)
        return AppModel(client = client, scope = scope).also {
            it.agents = listOf(Agent(id = "a1", name = "Atlas", tools = listOf("coding")), Agent(id = "a2", name = "Scout"))
            it.capabilities = CODING
        }
    }

    @Test
    fun scriptTaskOnAnEventAppearsAtOnceAndReconcilesWithoutDuplicates() = runTest {
        val transport = HeldTransport { 201 to scheduleJson() }
        val gate = CompletableDeferred<Unit>()
        transport.held["POST /v1/schedules"] = gate
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.taskDraft = TaskDraft(
            scheduleID = "", agentID = "a1", name = "Backup", kind = "script", command = "./backup.sh",
            timeoutSeconds = "120", notify = false, onEvent = true, eventSourceID = "e1",
        )

        model.saveTask()
        testScheduler.runCurrent()

        val provisional = model.schedules.single()
        assertTrue(provisional.id.startsWith("pending-"))
        assertEquals(listOf(provisional), listedSchedules(model.schedules))
        assertTrue(model.schedulePending(provisional.id))
        val body = bodyOf(transport.requests.single())
        assertEquals(JsonPrimitive("script"), body["kind"])
        assertEquals(JsonPrimitive("./backup.sh"), body["command"])
        assertEquals(JsonPrimitive(120L), body["timeoutSeconds"])
        assertEquals(JsonPrimitive(false), body["notify"])
        assertEquals(JsonPrimitive("e1"), body["eventSourceId"])
        assertEquals(JsonPrimitive(""), body["prompt"])
        assertFalse(body.containsKey("cron"))
        assertFalse(body.containsKey("runAt"))

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("s1"), model.schedules.map { it.id })
        assertEquals(120L, model.schedules.single().timeoutSeconds)
    }

    @Test
    fun aNewOneOffStaysListedWhileItSaves() = runTest {
        val transport = HeldTransport { 500 to """{"error":"down"}""" }
        transport.held["POST /v1/schedules"] = CompletableDeferred()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.taskDraft = TaskDraft(scheduleID = "", agentID = "a1", name = "Once", prompt = "Hi")

        model.saveTask()
        testScheduler.runCurrent()

        assertEquals(1, listedSchedules(model.schedules).size)
    }

    @Test
    fun aFailedScriptSaveDropsTheRowAndRestoresTheDraft() = runTest {
        val transport = HeldTransport { 500 to """{"error":"down"}""" }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.openPanel(Route.Tasks)
        testScheduler.advanceUntilIdle()
        val form = TaskDraft(scheduleID = "", agentID = "a1", name = "Backup", kind = "script", command = "./b.sh", timeoutSeconds = "90", notify = false)
        model.taskDraft = form

        model.saveTask()
        testScheduler.advanceUntilIdle()

        assertTrue(model.schedules.isEmpty())
        assertEquals(form, model.taskDraft)
        assertTrue(model.errorText.isNotEmpty())
    }

    @Test
    fun movingATaskOntoAnEventClearsItsOtherTriggersAndAPromptSendsNoCommand() = runTest {
        val transport = HeldTransport { 200 to scheduleJson(kind = "prompt") }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.schedules = listOf(schedule(scheduleJson(kind = "prompt", cron = "0 9 * * *", eventSourceId = null)))
        model.openTaskForm("s1")
        assertTrue(model.taskDraft!!.repeats)

        model.taskDraft = model.taskDraft!!.copy(onEvent = true, eventSourceID = "e1")
        model.saveTask()
        testScheduler.advanceUntilIdle()

        val body = bodyOf(transport.requests.single { it.method == "PATCH" })
        assertEquals(JsonPrimitive("e1"), body["eventSourceId"])
        assertEquals(JsonNull, body["cron"])
        assertEquals(JsonNull, body["runAt"])
        assertEquals(JsonNull, body["command"])
        assertEquals(JsonPrimitive(600L), body["timeoutSeconds"])
    }

    @Test
    fun aFormOpensWithTheScriptFieldsOfItsTask() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(HeldTransport { 200 to "[]" }, scope)
        model.schedules = listOf(schedule())

        model.openTaskForm("s1")

        val draft = model.taskDraft!!
        assertEquals("script", draft.kind)
        assertEquals("./backup.sh", draft.command)
        assertEquals("120", draft.timeoutSeconds)
        assertFalse(draft.notify)
        assertTrue(draft.onEvent)
        assertEquals("e1", draft.eventSourceID)
    }

    @Test
    fun pickingABotWithoutCodingJobsFallsBackToAPrompt() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(HeldTransport { 200 to "[]" }, scope)
        model.taskDraft = TaskDraft(scheduleID = "", agentID = "a1", kind = "script")

        model.pickTaskBot("a1")
        assertEquals("script", model.taskDraft!!.kind)
        model.pickTaskBot("a2")
        assertEquals("prompt", model.taskDraft!!.kind)
        assertEquals("a2", model.taskDraft!!.agentID)
    }

    @Test
    fun pausingAppliesAtOnceAndRollsBack() = runTest {
        val transport = HeldTransport { 500 to """{"error":"down"}""" }
        val gate = CompletableDeferred<Unit>()
        transport.held["PATCH /v1/schedules/s1"] = gate
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.schedules = listOf(schedule())

        model.toggleSchedule("s1")
        testScheduler.runCurrent()

        assertFalse(model.schedules.single().enabled)
        assertTrue(model.schedulePending("s1"))
        assertEquals(setOf("enabled"), bodyOf(transport.requests.single()).keys)

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue(model.schedules.single().enabled)
        assertFalse(model.schedulePending("s1"))
        assertTrue(model.errorText.isNotEmpty())
    }

    @Test
    fun runningAScriptOpensItsBotThoughAdmissionNamesOnlyTheRun() = runTest {
        val transport = HeldTransport { 202 to """{"admitted":true,"result":{"id":"r1","status":"running"}}""" }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.schedules = listOf(schedule())
        model.route = Route.Tasks

        model.runSchedule("s1")
        testScheduler.advanceUntilIdle()

        assertEquals(Route.AgentChat("a1"), model.route)
    }

    @Test
    fun aRefreshDuringASaveKeepsTheProvisionalRow() = runTest {
        val transport = HeldTransport { request ->
            when {
                request.method == "POST" -> 201 to scheduleJson()
                request.pathWithQuery == "/v1/schedules" -> 200 to "[]"
                request.pathWithQuery == "/v1/capabilities" -> 200 to """{"coding":true,"desktop":false,"jobKinds":[]}"""
                else -> 200 to SOURCES
            }
        }
        val gate = CompletableDeferred<Unit>()
        transport.held["POST /v1/schedules"] = gate
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.taskDraft = TaskDraft(scheduleID = "", agentID = "a1", name = "Backup", prompt = "Hi")
        model.saveTask()
        testScheduler.runCurrent()

        model.openPanel(Route.Tasks)
        testScheduler.advanceUntilIdle()

        assertEquals(1, model.schedules.size)
        assertEquals("GitHub", model.eventSources.single().name)
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("s1"), model.schedules.map { it.id })
    }

    @Test
    fun anEventSourceStaysPendingUntilTheRelayConfirmsAndShowsItsAddress() = runTest {
        val transport = HeldTransport { request ->
            if (request.method == "POST") {
                201 to CREATED_SOURCE
            } else {
                200 to """{"sources":[{"id":"e2","name":"Linear","createdAt":"2026-11-05T10:00:00Z","usedBy":0}]}"""
            }
        }
        val gate = CompletableDeferred<Unit>()
        transport.held["POST /v1/event-sources"] = gate
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.eventSourceDraft = EventSourceDraft(name = " Linear ")

        model.saveEventSource()
        testScheduler.runCurrent()

        assertTrue(model.eventSourceDraft!!.saving)
        assertTrue(model.eventSources.isEmpty())
        model.closeEventSourceForm()
        assertNotNull("a pending sheet can't lose its one-time address", model.eventSourceDraft)
        assertEquals(JsonPrimitive("Linear"), bodyOf(transport.requests.single())["name"])

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals("https://relay.example/e2", model.eventSourceDraft!!.created!!.address)
        assertEquals(listOf("e2"), model.eventSources.map { it.id })
        model.closeEventSourceForm()
        assertNull(model.eventSourceDraft)
    }

    @Test
    fun aFailedEventSourceKeepsTheNameForRetry() = runTest {
        val transport = HeldTransport { 502 to """{"error":"relay down"}""" }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.eventSourceDraft = EventSourceDraft(name = "Linear")

        model.saveEventSource()
        testScheduler.advanceUntilIdle()

        val draft = model.eventSourceDraft!!
        assertEquals("Linear", draft.name)
        assertFalse(draft.saving)
        assertTrue(draft.error.isNotEmpty())
        assertTrue(model.eventSources.isEmpty())
    }

    @Test
    fun deletingAnEventSourceWaitsForTheRelayAndKeepsTheRowOnFailure() = runTest {
        val transport = HeldTransport { 409 to """{"error":"Used by 1 automation"}""" }
        val gate = CompletableDeferred<Unit>()
        transport.held["DELETE /v1/event-sources/e1"] = gate
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.eventSources = listOf(SOURCE)

        model.deleteEventSource("e1")
        testScheduler.runCurrent()

        assertEquals(listOf(SOURCE), model.eventSources)
        assertEquals(setOf("e1"), model.deletingEventSources)

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(SOURCE), model.eventSources)
        assertTrue(model.deletingEventSources.isEmpty())
        assertTrue(model.eventSourceErrors.getValue("e1").isNotEmpty())
    }

    @Test
    fun aListThatStartedBeforeADeleteCannotBringTheSourceBack() = runTest {
        // The held list answers last, with what it read before the delete.
        var lists = 0
        val transport = HeldTransport { request ->
            when {
                request.method == "DELETE" -> 204 to ""
                lists++ == 0 -> 200 to """{"sources":[]}"""
                else -> 200 to SOURCES
            }
        }
        val listGate = CompletableDeferred<Unit>()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.eventSources = listOf(SOURCE)
        transport.held["GET /v1/event-sources"] = listGate

        model.selectTaskTab(TaskTab.EventSources)
        testScheduler.runCurrent()
        transport.held.clear()
        model.deleteEventSource("e1")
        testScheduler.runCurrent()
        assertTrue(model.eventSources.isEmpty())

        listGate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue(model.eventSources.isEmpty())
    }

    @Test
    fun runsTabLoadsScriptHistoryAndKeepsWhatAFailedScheduleHad() = runTest {
        val runJson = """{"id":"r1","scheduleId":"s1","agentId":"a1","command":"./backup.sh","status":"succeeded","exitCode":0,"output":"done","droppedBytes":0,"error":null,"startedAt":"2026-11-05T10:00:00Z","finishedAt":"2026-11-05T10:00:01Z"}"""
        var failing = false
        val transport = HeldTransport { request ->
            when (request.pathWithQuery) {
                "/v1/schedules/s1/runs?limit=20" -> if (failing) 500 to """{"error":"down"}""" else 200 to "[$runJson]"
                else -> 200 to "[]"
            }
        }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.schedules = listOf(schedule())

        model.selectTaskTab(TaskTab.Runs)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("r1"), model.scriptRuns.getValue("s1").map { it.id })
        assertEquals("", model.taskRunsError)

        failing = true
        model.selectTaskTab(TaskTab.Runs)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("r1"), model.scriptRuns.getValue("s1").map { it.id })
        assertEquals("Some script runs could not be loaded.", model.taskRunsError)
        assertFalse(model.taskRunsLoading)
    }

    @Test
    fun runsMergeNewestFirstAndNameDeletedTasks() {
        val runs = taskRuns(
            schedules = listOf(schedule()),
            executions = listOf(
                Execution(id = "x1", agentId = "a1", status = "failed", createdAt = "2026-11-05T12:00:00Z", scheduleId = "gone"),
                Execution(id = "x2", agentId = "a1", status = "done", createdAt = "2026-11-05T13:00:00Z"),
            ),
            scriptRuns = mapOf(
                "s1" to listOf(scriptRun("r1", status = "timed_out"), scriptRun("r2", status = "running", startedAt = "2026-11-05T14:00:00Z")),
                "deleted" to listOf(scriptRun("r3", scheduleId = "deleted")),
            ),
        )

        assertEquals(listOf("r2", "x1", "r1"), runs.map { it.id })
        assertTrue(runs[0].running)
        assertEquals("Failed", runs[1].label)
        assertNull(runs[1].schedule)
        assertEquals("Timed out", runs[2].label)
        assertTrue(runs[2].failed)
    }

    @Test
    fun runsStopAtOneHundred() {
        val many = (1..150).map { scriptRun("r$it") }
        assertEquals(100, taskRuns(listOf(schedule()), emptyList(), mapOf("s1" to many)).size)
    }

    @Test
    fun aScriptRunReportsItsDuration() {
        assertEquals("1.5 s", scriptRunDuration(scriptRun("r1")))
        val running = scriptRun("r2").copy(finishedAt = null)
        assertEquals("2.0 s", scriptRunDuration(running, Instant.parse("2026-11-05T10:00:02Z")))
    }

    @Test
    fun anEventTaskNamesItsSourceAndNeverCountsAsFinished() {
        val task = schedule()
        assertFalse(task.finished)
    }

    @Test
    fun aOneOffReadsRanOnlyAfterItFinished() {
        val done = schedule(scheduleJson(kind = "prompt", runAt = "2026-11-05T10:00:00Z", eventSourceId = null))
        val waiting = schedule(scheduleJson(kind = "prompt", runAt = "2026-11-05T14:00:00Z", eventSourceId = null, nextRunAt = "2026-11-05T14:00:00Z"))
        assertEquals(ScheduleStatus.Completed, done.status)
        assertEquals(ScheduleStatus.Active, waiting.status)
    }

    @Test
    fun listingHidesFinishedPromptsAndOrdersByStatusThenNextRun() {
        val finishedPrompt = schedule(scheduleJson(id = "p", kind = "prompt", runAt = "2026-11-05T10:00:00Z", eventSourceId = null))
        val finishedScript = schedule(scheduleJson(id = "f", runAt = "2026-11-05T10:00:00Z", eventSourceId = null))
        val paused = schedule(scheduleJson(id = "z", enabled = false))
        val later = schedule(scheduleJson(id = "l", cron = "0 9 * * *", eventSourceId = null, nextRunAt = "2026-11-06T09:00:00Z"))
        val sooner = schedule(scheduleJson(id = "s", cron = "0 8 * * *", eventSourceId = null, nextRunAt = "2026-11-05T15:00:00Z"))

        val order = listedSchedules(listOf(finishedPrompt, finishedScript, paused, later, sooner)).map { it.id }

        assertEquals(listOf("s", "l", "z", "f"), order)
    }

    @Test
    fun scriptValidationMatchesTheWebsite() {
        fun script(command: String = "./a.sh", timeout: String = "600", onEvent: Boolean = false, source: String = "") = validateTask(
            name = "Task", prompt = "", cron = "0 9 * * *", runAt = "", kind = "script",
            command = command, timeoutSeconds = timeout, onEvent = onEvent, eventSourceID = source,
        )
        assertNull(script())
        assertEquals("Task name and command are required.", script(command = "  "))
        assertEquals("Timeout must be between 1 and 3600 seconds.", script(timeout = "0"))
        assertEquals("Timeout must be between 1 and 3600 seconds.", script(timeout = "3601"))
        assertEquals("Timeout must be between 1 and 3600 seconds.", script(timeout = "1.5"))
        assertEquals("Choose an event source.", script(onEvent = true))
        assertNull(script(onEvent = true, source = "e1"))
        assertNull(validateTask(name = "T", prompt = "Hi", cron = "", runAt = "", onEvent = true, eventSourceID = "e1"))
    }

    @Test
    fun scriptsNeedBothAWorkspaceServerAndACodingBot() {
        val coder = Agent(id = "a1", tools = listOf("coding"))
        assertTrue(canRunScripts(CODING, coder))
        assertFalse(canRunScripts(CODING.copy(coding = false), coder))
        assertFalse(canRunScripts(null, coder))
        assertFalse(canRunScripts(CODING, Agent(id = "a2")))
    }

    @Test
    fun anExistingCronOpensInTheMatchingPickerAndAnOddOneAsCustom() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(HeldTransport { 200 to "{}" }, scope)
        model.schedules = listOf(
            schedule(scheduleJson(id = "weekly", kind = "prompt", cron = "30 8 * * 1,3", eventSourceId = null)),
            schedule(scheduleJson(id = "odd", kind = "prompt", cron = "*/7 * * * *", eventSourceId = null)),
            schedule(scheduleJson(id = "once", kind = "prompt", runAt = "2026-12-01T10:00:00Z", eventSourceId = null)),
        )
        model.openTaskForm("weekly")
        assertEquals(TaskWhen.Weekly, model.taskDraft!!.choice)
        model.openTaskForm("odd")
        assertEquals(TaskWhen.Custom, model.taskDraft!!.choice)
        model.openTaskForm("once")
        assertEquals(TaskWhen.Once, model.taskDraft!!.choice)
    }

    @Test
    fun aDraftFromThePickersSavesTheCronAndZoneItShows() = runTest {
        val transport = HeldTransport { 201 to scheduleJson(kind = "prompt", cron = "15 7 * * 2,4", eventSourceId = null) }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        var draft = TaskDraft(scheduleID = "", agentID = "a1", name = "Standup", prompt = "Go", timezone = "UTC")
        draft = draft.choosing(TaskWhen.Weekly)
        draft = draft.recurring(recurrenceOf(draft.cron)!!.copy(days = listOf(2, 4), hour = 7, minute = 15))
        model.taskDraft = draft.inTimezone("Asia/Tokyo")
        model.saveTask()
        testScheduler.runCurrent()
        val body = bodyOf(transport.requests.single())
        assertEquals("15 7 * * 2,4", body["cron"]?.jsonPrimitive?.content)
        assertEquals("Asia/Tokyo", body["timezone"]?.jsonPrimitive?.content)
        assertNull(body["runAt"])
    }

    @Test
    fun aOneOffSavesTheWallClockInTheChosenZone() = runTest {
        val transport = HeldTransport { 201 to scheduleJson(kind = "prompt", runAt = "2026-12-01T00:00:00Z", eventSourceId = null) }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        val draft = TaskDraft(scheduleID = "", agentID = "a1", name = "Once", prompt = "Go", timezone = "UTC")
            .runningAt(LocalDateTime.of(2026, 12, 1, 9, 0))
        model.taskDraft = draft.inTimezone("Asia/Tokyo")
        model.saveTask()
        testScheduler.runCurrent()
        assertEquals("2026-12-01T00:00:00Z", bodyOf(transport.requests.single())["runAt"]?.jsonPrimitive?.content)
    }

    @Test
    fun deletingATaskAppliesAtOnceAndRestoresItOnFailure() = runTest {
        val transport = HeldTransport { 500 to """{"error":"down"}""" }
        val gate = CompletableDeferred<Unit>()
        transport.held["DELETE /v1/schedules/s1"] = gate
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val model = modelWith(transport, scope)
        model.schedules = listOf(schedule(), schedule(scheduleJson(id = "s2")))
        model.deleteSchedule("s1")
        assertEquals(listOf("s2"), model.schedules.map { it.id })
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(listOf("s1", "s2"), model.schedules.map { it.id })
    }
}
