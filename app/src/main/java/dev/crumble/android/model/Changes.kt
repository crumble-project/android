package dev.crumble.android.model

import dev.crumble.android.generated.LearnedChange
import dev.crumble.android.generated.LearnedChangeAfter
import dev.crumble.android.generated.LearnedChangeBefore
import dev.crumble.android.generated.SkillDocument
import dev.crumble.android.generated.SkillPackage
import dev.crumble.android.network.domain
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToLong

data class DiffLine(val kind: Char, val text: String)

internal val LearnedChange.beforeSkill: SkillPackage? get() = (before as? LearnedChangeBefore.Variant1)?.value
internal val LearnedChange.beforeMemory: Memory? get() = (before as? LearnedChangeBefore.Variant2)?.value?.domain()
internal val LearnedChange.existedBefore: Boolean get() = before !is LearnedChangeBefore.Variant3

private fun contentLines(content: String) = content.split("\n")

/** The state as plain lines, so a skill's name, description, instructions, and documents diff together. */
fun skillLines(name: String, description: String, instructions: String, documents: List<SkillDocument>): List<String> =
    listOf("# $name", description, "") + contentLines(instructions) + documents.flatMap {
        listOf("", "--- ${it.path}") + contentLines(it.content)
    }

fun skillLines(skill: SkillPackage) = skillLines(skill.name, skill.description, skill.instructions, skill.documents)

private fun LearnedChangeBefore.lines() = when (this) {
    is LearnedChangeBefore.Variant1 -> skillLines(value)
    is LearnedChangeBefore.Variant2 -> contentLines(value.content)
    is LearnedChangeBefore.Variant3 -> emptyList()
}

private fun LearnedChangeAfter.lines() = when (this) {
    is LearnedChangeAfter.Variant1 -> skillLines(value)
    is LearnedChangeAfter.Variant2 -> contentLines(value.content)
    is LearnedChangeAfter.Variant3 -> emptyList()
}

fun LearnedChange.diff(): List<DiffLine> = lineDiff(before.lines(), after.lines())

private fun commonLengths(a: List<String>, b: List<String>): Array<IntArray> {
    val table = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) {
        for (j in b.indices.reversed()) {
            table[i][j] = if (a[i] == b[j]) table[i + 1][j + 1] + 1 else maxOf(table[i + 1][j], table[i][j + 1])
        }
    }
    return table
}

private fun walkDiff(a: List<String>, b: List<String>): List<DiffLine> {
    val table = commonLengths(a, b)
    val middle = mutableListOf<DiffLine>()
    var i = 0
    var j = 0
    while (i < a.size || j < b.size) {
        if (i < a.size && j < b.size && a[i] == b[j]) {
            middle.add(DiffLine(' ', a[i]))
            i += 1
            j += 1
        } else if (i < a.size && (j == b.size || table[i + 1][j] >= table[i][j + 1])) {
            middle.add(DiffLine('-', a[i]))
            i += 1
        } else {
            middle.add(DiffLine('+', b[j]))
            j += 1
        }
    }
    return middle
}

/** A line diff by longest common subsequence, after trimming the shared head and tail. */
fun lineDiff(before: List<String>, after: List<String>): List<DiffLine> {
    var head = 0
    while (head < before.size && head < after.size && before[head] == after[head]) head += 1
    var tail = 0
    while (tail < before.size - head && tail < after.size - head && before[before.size - 1 - tail] == after[after.size - 1 - tail]) tail += 1
    val middle = walkDiff(before.subList(head, before.size - tail), after.subList(head, after.size - tail))
    fun same(lines: List<String>) = lines.map { DiffLine(' ', it) }
    return same(before.subList(0, head)) + middle + same(before.subList(before.size - tail, before.size))
}

fun revertPrompt(change: LearnedChange): String {
    val target = if (change.target == "skill") "skill" else "memory"
    return if (change.existedBefore) {
        "The $target goes back to how it was before this change. Anything changed since is replaced. The revert is recorded, so you can undo it too."
    } else {
        "The $target is deleted again. The revert is recorded, so you can undo it too."
    }
}

private val AGE_STEPS = listOf("minute" to 60.0, "hour" to 60.0, "day" to 24.0, "week" to 7.0, "month" to 4.35, "year" to 12.0)
private val LAST = mapOf("day" to "yesterday", "week" to "last week", "month" to "last month", "year" to "last year")

/** "3 days ago", in the largest unit that is at least one. */
fun memoryAge(iso: String, now: Instant): String? {
    val then = runCatching { Instant.parse(iso) }.getOrNull() ?: return null
    var value = abs(Duration.between(then, now).seconds.toDouble())
    var unit = "second"
    for ((next, size) in AGE_STEPS) {
        if (value < size) break
        value /= size
        unit = next
    }
    val count = value.roundToLong()
    return when {
        unit == "second" -> "this minute"
        count == 1L -> LAST[unit] ?: "1 $unit ago"
        else -> "$count ${unit}s ago"
    }
}

private val SOURCE_LABELS = mapOf<String, (String?) -> String>(
    "chat" to { _ -> "From the bot chat" },
    "owner" to { _ -> "Added by you" },
    "task" to { name -> "From the automation ${name.orEmpty()}".trim() },
    "bot" to { name -> "From a request by ${name ?: "a bot"}" },
    "group" to { name -> "From the group chat ${name.orEmpty()}".trim() },
)

fun memorySourceLabel(memory: Memory): String? =
    memory.source?.let { source -> SOURCE_LABELS[source.kind]?.invoke(source.name) }

/** Where a memory came from and when it last changed, such as "Added by you · 3 days ago". */
fun memoryLine(memory: Memory, now: Instant): String =
    listOfNotNull(memorySourceLabel(memory), memory.updatedAt?.let { memoryAge(it, now) }).joinToString(" · ")
