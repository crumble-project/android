package dev.crumble.android.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

enum class TaskWhen(val label: String) {
    Once("Once"),
    Hourly("Every hour"),
    Daily("Every day"),
    Weekdays("Every weekday"),
    Weekly("Every week"),
    Monthly("Every month"),
    Custom("Custom (cron)"),
}

/** A cron the friendly picker can show. Fields a kind ignores keep their value across kind switches. */
data class Recurrence(
    val kind: TaskWhen,
    val minute: Int = 0,
    val hour: Int = 9,
    val days: List<Int> = listOf(1),
    val day: Int = 1,
)

/** Monday first; cron counts Sunday as 0. */
val WEEK = listOf(1, 2, 3, 4, 5, 6, 0)

private val NUMBER = "(\\d+)"
private val HOURLY = Regex("^$NUMBER \\* \\* \\* \\*$")
private val DAILY = Regex("^$NUMBER $NUMBER \\* \\* \\*$")
private val WEEKDAYS = Regex("^$NUMBER $NUMBER \\* \\* 1-5$")
private val WEEKLY = Regex("^$NUMBER $NUMBER \\* \\* ([0-7](?:,[0-7])*)$")
private val MONTHLY = Regex("^$NUMBER $NUMBER $NUMBER \\* \\*$")

private fun parsedRecurrence(text: String): Recurrence? {
    fun Regex.numbers() = matchEntire(text)?.groupValues?.drop(1)
    fun time(fields: List<String>, build: (Int, Int) -> Recurrence): Recurrence? {
        val minute = fields[0].toIntOrNull() ?: return null
        val hour = fields[1].toIntOrNull() ?: return null
        return build(minute, hour)
    }
    HOURLY.numbers()?.let { fields -> return fields[0].toIntOrNull()?.let { Recurrence(TaskWhen.Hourly, minute = it) } }
    DAILY.numbers()?.let { fields -> return time(fields) { minute, hour -> Recurrence(TaskWhen.Daily, minute, hour) } }
    WEEKDAYS.numbers()?.let { fields -> return time(fields) { minute, hour -> Recurrence(TaskWhen.Weekdays, minute, hour) } }
    WEEKLY.numbers()?.let { fields ->
        val days = fields[2].split(",").map { it.toInt() % 7 }.distinct()
        return time(fields) { minute, hour -> Recurrence(TaskWhen.Weekly, minute, hour, days) }
    }
    MONTHLY.numbers()?.let { fields ->
        val day = fields[2].toIntOrNull() ?: return null
        return time(fields) { minute, hour -> Recurrence(TaskWhen.Monthly, minute, hour, day = day) }
    }
    return null
}

fun recurrenceOf(cron: String): Recurrence? = parsedRecurrence(cron.trim().replace(Regex("\\s+"), " "))?.takeIf {
    it.minute <= 59 && it.hour <= 23 && (it.kind != TaskWhen.Monthly || it.day in 1..31)
}

fun cronOf(recurrence: Recurrence): String = with(recurrence) {
    when (kind) {
        TaskWhen.Hourly -> "$minute * * * *"
        TaskWhen.Weekdays -> "$minute $hour * * 1-5"
        TaskWhen.Weekly -> "$minute $hour * * ${days.sorted().joinToString(",")}"
        TaskWhen.Monthly -> "$minute $hour $day * *"
        else -> "$minute $hour * * *"
    }
}

fun weekdayName(day: Int, style: TextStyle, locale: Locale = Locale.getDefault()): String =
    DayOfWeek.of(if (day == 0) 7 else day).getDisplayName(style, locale)

/** Five-minute steps, plus the current minute when a hand-written cron uses another one. */
fun minuteChoices(minute: Int): List<Int> = ((0 until 12).map { it * 5 } + minute).distinct().sorted()

val TaskDraft.choice: TaskWhen
    get() {
        if (!repeats) return TaskWhen.Once
        return (if (customCron) null else recurrenceOf(cron)?.kind) ?: TaskWhen.Custom
    }

fun TaskDraft.choosing(next: TaskWhen): TaskDraft = when (next) {
    TaskWhen.Once -> copy(repeats = false, customCron = false)
    TaskWhen.Custom -> copy(repeats = true, customCron = true)
    else -> copy(
        repeats = true,
        customCron = false,
        cron = cronOf(recurrenceOf(cron)?.copy(kind = next) ?: Recurrence(next)),
    )
}

fun TaskDraft.recurring(next: Recurrence): TaskDraft = copy(repeats = true, cron = cronOf(next))

val TaskDraft.zone: ZoneId
    get() = runCatching { ZoneId.of(timezone.trim()) }.getOrDefault(ZoneId.systemDefault())

val TaskDraft.wallClock: LocalDateTime
    get() = LocalDateTime.ofInstant(runDate, zone)

fun TaskDraft.runningAt(local: LocalDateTime): TaskDraft = copy(runDate = local.atZone(zone).toInstant())

/** A new zone keeps the time the owner picked, so the same clock reading runs at another instant. */
fun TaskDraft.inTimezone(id: String): TaskDraft = copy(timezone = id).runningAt(wallClock)

/** The zones a task can run in, narrowed by `query`; the current one stays listed even if the device lacks it. */
fun timezoneChoices(query: String, current: String, available: Set<String> = ZoneId.getAvailableZoneIds()): List<String> {
    val needle = query.trim().replace(' ', '_').lowercase()
    val regions = available.filter { it == "UTC" || ('/' in it && !it.startsWith("Etc/") && !it.startsWith("SystemV/")) }
    return (regions + current).distinct().filter { it.isNotEmpty() && it.lowercase().contains(needle) }.sorted()
}

sealed interface NextRun {
    data object Never : NextRun
    data object Passed : NextRun
    data object Unknown : NextRun
    data class At(val label: String, val time: String, val zone: String) : NextRun
}

private fun validZone(id: String) = runCatching { ZoneId.of(id) }.isSuccess

fun nextRunOf(draft: TaskDraft, now: Instant = Instant.now(), locale: Locale = Locale.getDefault()): NextRun {
    val zoneID = draft.timezone.trim()
    if (!validZone(zoneID)) return NextRun.Never
    if (draft.repeats && hasHashedField(draft.cron)) return NextRun.Unknown
    val zone = ZoneId.of(zoneID)
    val run = if (draft.repeats) nextOccurrence(draft.cron, zone, now) ?: return NextRun.Never else draft.runDate
    if (run.isBefore(now)) return NextRun.Passed
    return NextRun.At(if (draft.repeats) "Next run" else "Runs", humanRun(run, zone, now, locale), zoneID)
}

/** "today at 09:00", "tomorrow at 09:00", a weekday this week, or a short date further out. */
fun humanRun(run: Instant, zone: ZoneId, now: Instant, locale: Locale = Locale.getDefault()): String {
    val date: ZonedDateTime = run.atZone(zone)
    val days = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), date.toLocalDate())
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(date)
    val day = when (days) {
        0L -> "today"
        1L -> "tomorrow"
        in 2L..6L -> date.format(DateTimeFormatter.ofPattern("EEEE", locale))
        else -> date.format(DateTimeFormatter.ofPattern("EEE, MMM d", locale))
    }
    return "$day at $time"
}
