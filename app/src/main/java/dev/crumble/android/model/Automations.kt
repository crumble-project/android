package dev.crumble.android.model

import dev.crumble.android.generated.Capabilities
import dev.crumble.android.generated.CreateEventSourceResponse
import dev.crumble.android.generated.ListEventSourcesResponseSourcesItem
import dev.crumble.android.generated.ScriptRun
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

typealias EventSource = ListEventSourcesResponseSourcesItem

const val MAX_COMMAND_CHARS = 8000
const val MAX_EVENT_SOURCE_NAME_CHARS = 200
private const val RUNS_SHOWN = 100

enum class TaskTab(val label: String) {
    Tasks("Tasks"),
    Runs("Runs"),
    EventSources("Event sources"),
}

/** A source's address is shown once, so `created` keeps the sheet open until the owner is done. */
data class EventSourceDraft(
    val name: String = "",
    val saving: Boolean = false,
    val error: String = "",
    val created: CreateEventSourceResponse? = null,
)

enum class ScheduleStatus(val label: String, val rank: Int) {
    Active("Active", 0),
    Paused("Paused", 1),
    Completed("Completed", 2),
}

/** A one-off task that already ran; its result is in the bot's chat. */
val Schedule.finished: Boolean
    get() = cron == null && eventSourceId == null && nextRunAt == null

val Schedule.status: ScheduleStatus
    get() = when {
        finished -> ScheduleStatus.Completed
        enabled -> ScheduleStatus.Active
        else -> ScheduleStatus.Paused
    }

private val Schedule.nextTime: Instant
    get() = nextRunAt?.let(::taskRunDate) ?: Instant.MAX

/** Active tasks soonest first, then paused ones, then finished script tasks. */
fun listedSchedules(schedules: List<Schedule>): List<Schedule> = schedules
    .filter { it.kind == "script" || !it.finished }
    .sortedWith(compareBy({ it.status.rank }, { it.nextTime }, { it.name }))

/** A bot's tasks that can still run, as its details list them. */
fun automationsOf(agentID: String, schedules: List<Schedule>): List<Schedule> =
    schedules.filter { it.agentId == agentID && !it.finished }

fun automationTiming(schedule: Schedule, sources: List<EventSource>, now: Instant = Instant.now()): String =
    (if (schedule.enabled) "" else "Paused · ") + describeTiming(schedule, sources, now)

fun describeTiming(
    schedule: Schedule,
    sources: List<EventSource>,
    now: Instant = Instant.now(),
    localZone: String = ZoneId.systemDefault().id,
): String {
    val sourceID = schedule.eventSourceId
    if (sourceID != null) {
        val source = sources.firstOrNull { it.id == sourceID }
        return if (source != null) "On event · ${source.name}" else "On event"
    }
    if (!schedule.cron.isNullOrEmpty()) return describeCron(schedule.cron, schedule.timezone ?: localZone, localZone)
    val runAt = schedule.runAt?.let(::taskRunDate) ?: return "Once"
    val zone = runCatching { ZoneId.of(schedule.timezone) }.getOrDefault(ZoneId.systemDefault())
    val text = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(zone).format(runAt)
    return if (runAt.isBefore(now) && schedule.nextRunAt == null) "Ran $text" else "Once, $text"
}

fun canRunScripts(capabilities: Capabilities?, agent: Agent?): Boolean =
    capabilities?.coding == true && agent?.tools?.contains("coding") == true

/** One row of the Runs tab: a prompt run opens the bot's chat, a script run its output. */
data class TaskRun(
    val id: String,
    val schedule: Schedule?,
    val agentId: String,
    val startedAt: String,
    val label: String,
    val failed: Boolean,
    val running: Boolean,
    val script: ScriptRun? = null,
)

private val EXECUTION_STATUS = mapOf(
    "pending" to "Queued",
    "running" to "Running",
    "done" to "Done",
    "failed" to "Failed",
    "cancelled" to "Cancelled",
)

private val SCRIPT_STATUS = mapOf(
    "running" to "Running",
    "succeeded" to "Succeeded",
    "failed" to "Failed",
    "timed_out" to "Timed out",
    "interrupted" to "Interrupted",
)

fun taskRuns(
    schedules: List<Schedule>,
    executions: List<Execution>,
    scriptRuns: Map<String, List<ScriptRun>>,
): List<TaskRun> {
    val byId = schedules.associateBy { it.id }
    val prompts = executions.mapNotNull { execution ->
        val scheduleId = execution.scheduleId ?: return@mapNotNull null
        val status = execution.status.orEmpty()
        TaskRun(
            id = execution.id,
            schedule = byId[scheduleId],
            agentId = execution.agentId.orEmpty(),
            startedAt = execution.createdAt.orEmpty(),
            label = EXECUTION_STATUS[status] ?: status,
            failed = status == "failed",
            running = status == "running" || status == "pending",
        )
    }
    val scripts = schedules.filter { it.kind == "script" }.flatMap { scriptRuns[it.id].orEmpty() }.map { run ->
        TaskRun(
            id = run.id,
            schedule = byId[run.scheduleId],
            agentId = run.agentId,
            startedAt = run.startedAt,
            label = SCRIPT_STATUS[run.status] ?: run.status,
            failed = run.status != "succeeded" && run.status != "running",
            running = run.status == "running",
            script = run,
        )
    }
    return (prompts + scripts)
        .sortedByDescending { taskRunDate(it.startedAt) ?: Instant.MIN }
        .take(RUNS_SHOWN)
}

fun scriptRunDuration(run: ScriptRun, now: Instant = Instant.now()): String {
    val start = taskRunDate(run.startedAt) ?: return "—"
    val end = run.finishedAt?.let(::taskRunDate) ?: now
    val seconds = maxOf(0L, Duration.between(start, end).toMillis()) / 1000.0
    return "${String.format(Locale.ROOT, "%.1f", seconds)} s"
}
