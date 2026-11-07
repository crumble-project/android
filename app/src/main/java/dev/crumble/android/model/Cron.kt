package dev.crumble.android.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

const val MAX_CRON_CHARS = 100

// Reaches the next Feb 29 from any date; anything later never fires.
private const val MAX_SCAN_DAYS = 3_000

private val CRON_ALIASES = mapOf(
    "@yearly" to "0 0 0 1 1 *",
    "@annually" to "0 0 0 1 1 *",
    "@monthly" to "0 0 0 1 * *",
    "@weekly" to "0 0 0 * * 0",
    "@daily" to "0 0 0 * * *",
    "@hourly" to "0 0 * * * *",
    "@minutely" to "0 * * * * *",
    "@secondly" to "* * * * * *",
    "@weekdays" to "0 0 0 * * 1-5",
    "@weekends" to "0 0 0 * * 0,6",
)

private val CRON_NAMES = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat").withIndex().associate { (index, name) -> name to index } +
    listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec").withIndex().associate { (index, name) -> name to index + 1 }
private val CRON_NAME = Regex("[a-z]{3}", RegexOption.IGNORE_CASE)
private val CRON_FIELD = Regex("[?,*\\dL#/-]+")
private val LAST_WEEKDAY = Regex("\\dL")
private val WHITESPACE = Regex("\\s+")

private val FIELD_DEFAULTS = listOf("0", "*", "*", "*", "*", "*")

private class CronField(val values: Set<Int>, val wildcard: Boolean, val last: Boolean = false)

private class CronRule(
    val seconds: List<Int>,
    val minutes: List<Int>,
    val hours: List<Int>,
    val months: Set<Int>,
    val daysOfMonth: CronField,
    val weekdays: CronField,
    val lastWeekdays: Set<Int>,
    val nthWeekday: Int?,
) {
    fun matches(date: LocalDate): Boolean {
        if (date.monthValue !in months) return false
        val lastDay = date == date.with(TemporalAdjusters.lastDayOfMonth())
        val onDay = date.dayOfMonth in daysOfMonth.values || (daysOfMonth.last && lastDay)
        val weekday = date.dayOfWeek.value % 7
        val finalWeek = date.plusDays(7).month != date.month
        val onWeekday = (weekday in weekdays.values && (nthWeekday == null || (date.dayOfMonth - 1) / 7 + 1 == nthWeekday)) ||
            (weekday in lastWeekdays && finalWeek)
        return when {
            daysOfMonth.wildcard && weekdays.wildcard -> true
            daysOfMonth.wildcard -> onWeekday
            weekdays.wildcard -> onDay
            else -> onDay || onWeekday
        }
    }
}

private fun cronAtom(atom: String, min: Int, max: Int, weekday: Boolean): Set<Int>? {
    val slash = atom.split("/")
    if (slash.size > 2) return null
    val stepped = slash.size == 2
    val range = if (stepped && '-' !in slash[0]) "${slash[0]}-$max" else slash[0]
    val step = if (stepped) slash[1].toIntOrNull() ?: return null else 1
    val bounds = range.split("-")
    if (bounds.size == 1) {
        val value = range.toIntOrNull()?.takeIf { it in min..max } ?: return null
        return setOf(if (weekday) value % 7 else value)
    }
    val low = bounds[0].toIntOrNull() ?: return null
    val high = bounds[1].toIntOrNull() ?: return null
    if (low < min || high > max || low > high || step <= 0) return null
    return (low..high step step).mapTo(HashSet()) { if (weekday) it % 7 else it }
}

private fun cronField(raw: String, min: Int, max: Int, weekday: Boolean = false, lastDay: Boolean = false, lastWeekdays: MutableSet<Int>? = null): CronField? {
    var unknownName = false
    val named = CRON_NAME.replace(raw) { match ->
        CRON_NAMES[match.value.lowercase()]?.toString() ?: match.value.also { unknownName = true }
    }
    if (unknownName || !CRON_FIELD.matches(named)) return null
    val values = HashSet<Int>()
    var last = false
    for (atom in named.replace(Regex("[*?]"), "$min-$max").split(",")) {
        when {
            lastDay && atom == "L" -> last = true
            lastWeekdays != null && LAST_WEEKDAY.matches(atom) -> lastWeekdays.add(atom[0].digitToInt() % 7)
            else -> values.addAll(cronAtom(atom, min, max, weekday) ?: return null)
        }
    }
    return CronField(values, wildcard = raw == "*" || raw == "?", last = last)
}

private fun cronRule(cron: String): CronRule? {
    val text = cron.trim()
    if (text.isEmpty() || text.length > MAX_CRON_CHARS) return null
    val written = (CRON_ALIASES[text] ?: text).split(WHITESPACE)
    if (written.size > 6) return null
    // cron-parser fills missing leading fields, so "1 2 3" is a valid, if odd, schedule.
    val fields = FIELD_DEFAULTS.take(6 - written.size) + written
    val (second, minute, hour, dayOfMonth, month) = fields
    val dayOfWeek = fields[5]

    val nth = dayOfWeek.split("#")
    if (nth.size > 2 || (nth.size == 2 && dayOfWeek.any { it in ",-/" })) return null
    val nthWeekday = if (nth.size == 2) nth[1].toIntOrNull()?.takeIf { it in 1..5 } ?: return null else null

    val lastWeekdays = HashSet<Int>()
    return CronRule(
        seconds = cronField(second, 0, 59)?.values?.sorted() ?: return null,
        minutes = cronField(minute, 0, 59)?.values?.sorted() ?: return null,
        hours = cronField(hour, 0, 23)?.values?.sorted() ?: return null,
        months = cronField(month, 1, 12)?.values ?: return null,
        daysOfMonth = cronField(dayOfMonth, 1, 31, lastDay = true) ?: return null,
        weekdays = cronField(nth[0], 0, 7, weekday = true, lastWeekdays = lastWeekdays) ?: return null,
        lastWeekdays = lastWeekdays,
        nthWeekday = nthWeekday,
    )
}

private fun instantsOf(local: LocalDateTime, zone: ZoneId, everyHour: Boolean): List<Instant> {
    val offsets = zone.rules.getValidOffsets(local)
    return if (everyHour && offsets.size == 2) offsets.map(local::toInstant) else listOf(ZonedDateTime.of(local, zone).toInstant())
}

/**
 * The server's next run for `cron`, or null if it never fires. Like cron-parser, a skipped time runs
 * just after the gap, and only every-hour rules run again in a repeated hour.
 */
fun nextOccurrence(cron: String, zone: ZoneId, from: Instant): Instant? {
    val rule = cronRule(cron) ?: return null
    var date = from.atZone(zone).toLocalDate()
    repeat(MAX_SCAN_DAYS) {
        var soonest: Instant? = null
        if (rule.matches(date)) {
            for (hour in rule.hours) for (minute in rule.minutes) for (second in rule.seconds) {
                val readings = instantsOf(LocalDateTime.of(date, LocalTime.of(hour, minute, second)), zone, rule.hours.size == 24)
                for (at in readings) if (at.isAfter(from) && (soonest == null || at.isBefore(soonest))) soonest = at
                if (soonest != null && readings.size == 1) return soonest
            }
        }
        if (soonest != null) return soonest
        date = date.plusDays(1)
    }
    return null
}

/** `H` fields are randomized by the server, so the app cannot predict their next run. */
fun hasHashedField(cron: String): Boolean = Regex("(?<![A-Za-z])H").containsMatchIn(cron)

/** cron-parser reads weekday `1/2` as `1-7/2`, Sunday included, so the server refuses it. */
fun hasBareWeekdayStep(cron: String): Boolean {
    val fields = cron.trim().split(WHITESPACE)
    if (fields.size !in 5..6) return false
    return fields.last().split(",").any { Regex("^[^*-]+/").containsMatchIn(it) }
}

fun describeCron(cron: String, timezone: String, localZone: String = ZoneId.systemDefault().id): String {
    val zone = if (timezone != localZone) " ($timezone)" else ""
    val fields = cron.trim().split(WHITESPACE)
    val days = if (fields.size == 6) listOf(fields[3], fields[5]) else if (fields.size == 5) listOf(fields[2], fields[4]) else emptyList()
    val bothDays = days.isNotEmpty() && days.none { it == "*" || it == "?" }
    val worded = if (bothDays || hasBareWeekdayStep(cron)) null else wordedCron(cron)
    return "${worded ?: cron}$zone"
}

private val SPECIAL_CRONS = mapOf(
    "@yearly" to "0 0 1 JAN *",
    "@annually" to "0 0 1 JAN *",
    "@monthly" to "0 0 1 * *",
    "@weekly" to "0 0 * * SUN",
    "@daily" to "0 0 * * *",
    "@midnight" to "0 0 * * *",
    "@hourly" to "0 * * * *",
)
private val WEEKDAY_NAMES = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
private val MONTH_NAMES = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
private val WEEKDAY_NUMBERS = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")
private val MONTH_NUMBERS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
private val SPECIAL_CHARS = "/-,*"
private val PLAIN_FIELD = Regex("[0-9,\\-*/]*")

private fun fill(template: String, vararg values: String): String {
    val next = values.iterator()
    return Regex("%s").replace(template) { if (next.hasNext()) next.next() else "" }
}

private fun String.special() = any { it in SPECIAL_CHARS }

private fun plural(value: String, one: String, many: String) = if (value.toIntOrNull() == 1) one else many

private fun sortedList(field: String): String {
    if (',' !in field) return field
    val items = field.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    return when {
        items.isEmpty() -> "*"
        items.all { it.toIntOrNull() != null } -> items.sortedBy { it.toInt() }.joinToString(",")
        else -> items.joinToString(",")
    }
}

private fun replaceNames(field: String, names: List<String>, first: Int): String {
    var text = field
    names.forEachIndexed { index, name -> text = text.replace(name, (index + first).toString(), ignoreCase = true) }
    return text
}

private fun inBounds(field: String, min: Int, max: Int): Boolean = field.split(",").all { part ->
    val step = part.substringAfter('/', "")
    val first = Regex("^\\d+").find(part)?.value?.toIntOrNull()
    val second = Regex("^\\d+-(\\d+)").find(part)?.groupValues?.get(1)?.toIntOrNull()
    !step.matches(Regex("0+")) && (first == null || first in min..max) && (second == null || second in min..max)
}

private fun zeroStep(field: String, start: String) = if (field.startsWith("$start/")) "*/" + field.removePrefix("$start/") else field

private fun boundedStep(field: String, through: Int): String {
    if ('/' !in field || Regex("^\\*|-|,").containsMatchIn(field)) return field
    val (start, step) = field.split("/", limit = 2)
    return "$start-$through/$step"
}

/** The five fields cronstrue words after normalizing, or null when the expression needs the raw text. */
private fun wordableFields(cron: String): CronWords? {
    var text = cron.trim()
    if (text.startsWith("@")) text = SPECIAL_CRONS[text] ?: return null
    val parts = text.split(Regex(" +")).map(::sortedList).toMutableList()
    if (parts.size == 6) {
        if (Regex("\\d{4}$").containsMatchIn(parts[5]) || parts[4] == "?" || parts[2] == "?" || parts[0] != "0") return null
        parts.removeAt(0)
    }
    if (parts.size != 5) return null

    var minute = zeroStep(parts[0], "0")
    var hour = zeroStep(parts[1].replaceFirst("?", "*"), "0")
    var day = zeroStep(parts[2].replaceFirst("?", "*"), "1")
    var month = zeroStep(parts[3], "1")
    var weekday = Regex("(^\\d+)|([^#/\\s\\d]\\d+)").replace(parts[4].replaceFirst("?", "*")) { match ->
        if (match.value.replaceFirst(Regex("\\D"), "") == "7") match.value.replace("7", "0") else match.value
    }
    weekday = replaceNames(weekday, WEEKDAY_NUMBERS, 0)
    month = replaceNames(month, MONTH_NUMBERS, 1)
    if (listOf(minute, hour, day, month, weekday).any { !PLAIN_FIELD.matches(it) || it.isEmpty() }) return null
    if (!Regex("[*\\-,/]").containsMatchIn(hour) && Regex("[*/]").containsMatchIn(minute)) hour = "$hour-$hour"

    fun oneStep(field: String) = if (field == "*/1") "*" else field
    minute = oneStep(minute)
    hour = oneStep(hour)
    day = oneStep(day)
    month = boundedStep(oneStep(month), 12)
    weekday = boundedStep(oneStep(weekday), 6)
    val valid = inBounds(minute, 0, 59) && inBounds(hour, 0, 23) && inBounds(day, 1, 31) && inBounds(month, 1, 12) && inBounds(weekday, 0, 6)
    return if (valid) CronWords(minute, hour, day, month, weekday) else null
}

private class Segment(
    val all: String,
    val single: (String) -> String,
    val increment: (String) -> String,
    val range: (String) -> String,
    val format: (String) -> String,
)

/** The English cronstrue wording, or null when only the raw expression says it right. */
fun wordedCron(cron: String): String? = wordableFields(cron)?.sentence()

private class CronWords(val minute: String, val hour: String, val day: String, val month: String, val weekday: String) {
    fun sentence(): String = (timeOfDay() + dayOfMonth() + dayOfWeek() + monthOfYear())
        .replace(", every minute", "")
        .replace(", every hour", "")
        .replace(", every day", "")
        .replace(Regex(", ?$"), "")
        .replaceFirstChar { it.uppercase() }

    private fun time(hour: String, minute: String) = "${hour.padStart(2, '0')}:${minute.padStart(2, '0')}"

    private fun timeOfDay(): String = when {
        !minute.special() && !hour.special() -> "At " + time(hour, minute)
        '-' in minute && ',' !in minute && '/' !in minute && !hour.special() -> {
            val bounds = minute.split("-")
            "Every minute between ${time(hour, bounds[0])} and ${time(hour, bounds[1])}"
        }
        ',' in hour && '-' !in hour && '/' !in hour && !minute.special() -> {
            val hours = hour.split(",")
            buildString {
                append("At")
                hours.forEachIndexed { index, each ->
                    append(" ").append(time(each, minute))
                    if (index < hours.size - 2) append(",")
                    if (index == hours.size - 2) append(" and")
                }
            }
        }
        else -> {
            val minutes = minutesText()
            val hours = hoursText()
            if (minutes == hours) minutes else listOf(minutes, hours).filter { it.isNotEmpty() }.joinToString(", ")
        }
    }

    private fun minutesText() = described(
        minute,
        Segment(
            all = "every minute",
            single = { it },
            increment = { plural(it, "every %s minute", "every %s minutes") },
            range = { "minutes %s through %s past the hour" },
            format = { value ->
                when {
                    value == "0" && '/' !in hour -> "every hour"
                    value == "0" -> "on the hour"
                    else -> plural(value, "at %s minute past the hour", "at %s minutes past the hour")
                }
            },
        ),
    )

    private fun hoursText(): String {
        val rangeEnds = ArrayList<Pair<String, Int>>()
        var index = 0
        for (range in hour.substringBefore('/').split(",")) {
            val parts = range.split("-")
            if (parts.size == 2) rangeEnds.add(parts[1] to index + 1)
            index += parts.size
        }
        var evaluated = 0
        return described(
            hour,
            Segment(
                all = "every hour",
                single = { value ->
                    val endsRange = rangeEnds.any { it.first == value && it.second == evaluated }
                    evaluated += 1
                    time(value, if (endsRange && minute != "0") "59" else "0")
                },
                increment = { plural(it, "every %s hour", "every %s hours") },
                range = { "between %s and %s" },
                format = { "at %s" },
            ),
        )
    }

    private fun dayOfWeek(): String {
        if (weekday == "*") return ""
        return described(
            weekday,
            Segment(
                all = ", every day",
                single = { WEEKDAY_NAMES[it.toInt()] },
                increment = { if (it.toIntOrNull() == 1) "" else ", every %s days of the week" },
                range = { ", %s through %s" },
                format = { if (day != "*") ", and on %s" else ", only on %s" },
            ),
        )
    }

    private fun monthOfYear() = described(
        month,
        Segment(
            all = "",
            single = { MONTH_NAMES[it.toInt() - 1] },
            increment = { if (it.toIntOrNull() == 1) "" else ", every %s months" },
            range = { ", %s through %s" },
            format = { ", only in %s" },
        ),
    )

    private fun dayOfMonth(): String {
        if (day == "*" && weekday != "*") return ""
        return described(
            day,
            Segment(
                all = ", every day",
                single = { it },
                increment = { if (it == "1") ", every day" else ", every %s days in a month" },
                range = { ", between day %s and %s of the month" },
                format = { ", on day %s of the month" },
            ),
        )
    }

    private fun described(field: String, segment: Segment): String = when {
        field.isEmpty() -> ""
        field == "*" -> segment.all
        ',' in field -> listed(field, segment)
        '/' in field -> stepped(field, segment)
        '-' in field -> ranged(field, segment.range, segment.single)
        else -> fill(segment.format(field), segment.single(field))
    }

    private fun listed(field: String, segment: Segment): String {
        val stepped = '/' in field
        val items = field.split(",")
        val text = buildString {
            items.forEachIndexed { index, item ->
                if (index > 0 && items.size > 2) {
                    append(",")
                    if (index < items.size - 1) append(" ")
                }
                if (index > 0 && (index == items.size - 1 || items.size == 2)) append(" and ")
                when {
                    '/' in item || '-' in item -> {
                        val plainRange = '-' in item && '/' !in item
                        val inner = Segment(segment.all, segment.single, segment.increment, if (plainRange) ({ ", %s through %s" }) else segment.range, segment.format)
                        val part = described(item, inner)
                        append(
                            when {
                                plainRange -> part.replaceFirst(", ", "")
                                index > 0 -> part.removePrefix(", ")
                                else -> part
                            },
                        )
                    }
                    !stepped -> append(segment.single(item))
                    else -> {
                        val part = described(item, segment)
                        append(if (index > 0) part.removePrefix(", ") else part)
                    }
                }
            }
        }
        return if (stepped) text else fill(segment.format(field), text)
    }

    private fun stepped(field: String, segment: Segment): String {
        val (start, step) = field.split("/", limit = 2)
        var text = fill(segment.increment(step), step)
        if ('-' in start) {
            val range = ranged(start, segment.range, segment.single)
            if (!range.startsWith(", ")) text += ", "
            text += range
        } else if ('*' !in start) {
            text += ", starting " + fill(segment.format(start), segment.single(start)).replaceFirst(", ", "")
        }
        return text
    }

    private fun ranged(field: String, range: (String) -> String, single: (String) -> String): String {
        val bounds = field.split("-")
        val first = single(bounds[0])
        val second = single(bounds[1])
        return fill(range(field), first, second)
    }
}
