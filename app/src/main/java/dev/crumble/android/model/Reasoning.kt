package dev.crumble.android.model

data class ThinkingLevel(val value: String, val label: String)

val THINKING_LEVELS = listOf(
    ThinkingLevel("off", "Off"),
    ThinkingLevel("minimal", "Minimal"),
    ThinkingLevel("low", "Low"),
    ThinkingLevel("medium", "Medium"),
    ThinkingLevel("high", "High"),
    ThinkingLevel("xhigh", "Extra high"),
    ThinkingLevel("max", "Maximum"),
)

private val ORDER = THINKING_LEVELS.map { it.value }

/** The levels `model` takes; a model ID outside the catalog may take any. */
fun levelsOf(model: ModelSummary?): List<String> = model?.thinkingLevels ?: ORDER

/** The level a turn runs at, the nearest supported one preferring higher, as Pi AI's `clampThinkingLevel`. */
fun clampLevel(level: String, levels: List<String>): String {
    if (level in levels) return level
    val index = ORDER.indexOf(level)
    return ORDER.drop(index + 1).firstOrNull { it in levels }
        ?: ORDER.take(maxOf(index, 0)).lastOrNull { it in levels }
        ?: levels.firstOrNull()
        ?: "off"
}
