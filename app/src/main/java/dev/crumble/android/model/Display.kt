package dev.crumble.android.model

import kotlin.math.hypot
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

private const val DISPLAY_TOOL = "show"
private const val DESKTOP_SCREENSHOT_TOOL = "desktop_screenshot"

/** Web `--chart-1` to `--chart-5`, which are the same in both themes. */
private val CHART_COLORS = listOf(0xFFD4D4D4, 0xFF737373, 0xFF525252, 0xFF404040, 0xFF262626)

data class DisplayStat(
    val label: String,
    val value: String,
    val change: String? = null,
    val trend: String? = null,
    val caption: String? = null,
)

/** A table cell; `number` is set only for a numeric cell, which right-aligns and formats. */
data class DisplayCell(val text: String, val number: Double? = null)

/** What the `show` tool asked to draw. */
sealed class DisplayCard {
    abstract val title: String?

    data class Stats(override val title: String?, val items: List<DisplayStat>) : DisplayCard()

    data class Table(override val title: String?, val columns: List<String>, val rows: List<List<DisplayCell>>) : DisplayCard()

    /** Layouts hold up to thousands of primitives, so they are parsed when drawn, not whenever rows are built. */
    data class Chart(override val title: String?, val layouts: JsonArray) : DisplayCard()
}

private class InvalidDisplay : Exception()

private fun JsonElement?.string(): String =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw InvalidDisplay()

private fun JsonElement?.optionalString(): String? = if (this == null) null else string()

private fun JsonElement?.array(): JsonArray = this as? JsonArray ?: throw InvalidDisplay()

private fun JsonElement?.record(): JsonObject = this as? JsonObject ?: throw InvalidDisplay()

private fun cellOf(element: JsonElement): DisplayCell {
    val primitive = element as? JsonPrimitive ?: throw InvalidDisplay()
    if (primitive.isString) return DisplayCell(primitive.content)
    val number = primitive.doubleOrNull ?: throw InvalidDisplay()
    return DisplayCell(primitive.content, number)
}

private fun statOf(element: JsonElement): DisplayStat {
    val item = element.record()
    val trend = item["trend"].optionalString()
    if (trend != null && trend !in setOf("up", "down", "flat")) throw InvalidDisplay()
    return DisplayStat(
        label = item["label"].string(),
        value = item["value"].string(),
        change = item["change"].optionalString(),
        trend = trend,
        caption = item["caption"].optionalString(),
    )
}

/** The card a `show` result draws, or null when it is not one or fails the server's schema, like the website. */
fun displayOf(message: Message): DisplayCard? {
    if (message.role != "toolResult" || message.isError == true || message.toolName != DISPLAY_TOOL) return null
    return try {
        val details = message.toolDetails.record()
        val title = details["title"].optionalString()
        when (details["kind"].string()) {
            "stats" -> DisplayCard.Stats(title, details["items"].array().map(::statOf))
            "table" -> DisplayCard.Table(
                title,
                details["columns"].array().map { it.string() },
                details["rows"].array().map { row -> row.array().map(::cellOf) },
            )
            "chart" -> DisplayCard.Chart(
                title,
                details["layouts"].array().also { layouts ->
                    layouts.forEach { layout ->
                        layout.record().let { it["nodes"].array(); it["points"].array() }
                    }
                },
            )
            else -> null
        }
    } catch (_: InvalidDisplay) {
        null
    }
}

fun isDesktopScreenshot(message: Message): Boolean =
    message.role == "toolResult" && message.toolName == DESKTOP_SCREENSHOT_TOOL && message.isError != true &&
        !message.entryId.isNullOrEmpty()

/** A column reads as numeric only when every row's cell is a number. */
fun numericColumns(columnCount: Int, rows: List<List<DisplayCell>>): List<Boolean> =
    List(columnCount) { index -> rows.all { it.getOrNull(index)?.number != null } }

/** Natural widths, with spare room shared out so the table fills the card as it does on the web. */
fun fitColumns(natural: List<Float>, available: Float): List<Float> {
    val spare = available - natural.sum()
    return if (spare <= 0f || natural.isEmpty()) natural else natural.map { it + spare / natural.size }
}

sealed class ChartInk {
    data object None : ChartInk()
    data object Foreground : ChartInk()
    data object Background : ChartInk()
    data class Literal(val argb: Long) : ChartInk()
}

/** Series colors reuse the five theme colors, offset by one as the website's are. */
private fun seriesColor(series: Int) = CHART_COLORS[series % CHART_COLORS.size]

/** Resolves a chart color: a token the server normalized, or a hex literal. */
fun chartInk(color: String?, fallback: ChartInk): ChartInk {
    if (color.isNullOrEmpty()) return fallback
    when (color) {
        "none" -> return ChartInk.None
        "foreground" -> return ChartInk.Foreground
        "background" -> return ChartInk.Background
    }
    Regex("^series-(\\d+)$").find(color)?.let { return ChartInk.Literal(seriesColor(it.groupValues[1].toInt())) }
    val hex = color.removePrefix("#").takeIf { color.startsWith("#") } ?: return fallback
    val digits = when (hex.length) {
        3 -> hex.flatMap { listOf(it, it) }.joinToString("") + "ff"
        6 -> hex + "ff"
        8 -> hex
        else -> return fallback
    }
    val rgba = digits.toLongOrNull(16) ?: return fallback
    return ChartInk.Literal(((rgba and 0xFF) shl 24) or (rgba shr 8))
}

data class ChartClip(val x: Float, val y: Float, val width: Float, val height: Float)

data class ChartCommand(val op: String, val args: List<Float>)

sealed class ChartShape {
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : ChartShape()
    data class Curve(val commands: List<ChartCommand>, val evenOdd: Boolean) : ChartShape()
    data class Circle(val x: Float, val y: Float, val radius: Float) : ChartShape()
    data class Box(val x: Float, val y: Float, val width: Float, val height: Float, val radius: Float) : ChartShape()
    data class Label(
        val x: Float,
        val y: Float,
        val text: String,
        val anchor: String,
        val baseline: String,
        val rotate: Float,
        val size: Float,
        val weight: Int,
    ) : ChartShape()
}

data class ChartNode(
    val shape: ChartShape,
    val fill: ChartInk,
    val stroke: ChartInk,
    val fillOpacity: Float = 1f,
    val strokeOpacity: Float = 1f,
    val opacity: Float = 1f,
    val strokeWidth: Float = 1f,
    val lineCap: String? = null,
    val lineJoin: String? = null,
    val dash: List<Float> = emptyList(),
    val clip: ChartClip? = null,
)

data class ChartTooltipRow(val label: String, val value: String)

data class ChartPoint(
    val x: Float,
    val y: Float,
    val title: String? = null,
    val text: String? = null,
    val rows: List<ChartTooltipRow> = emptyList(),
)

data class ChartLayout(val width: Float, val height: Float, val nodes: List<ChartNode>, val points: List<ChartPoint>)

private val COMMAND_ARGS = mapOf("M" to 2, "L" to 2, "Q" to 4, "C" to 6, "Z" to 0)

private fun JsonObject.float(key: String): Float? = (this[key] as? JsonPrimitive)?.doubleOrNull?.toFloat()

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun floats(element: JsonElement?): List<Float>? =
    (element as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: return null }

private fun commandOf(element: JsonElement): ChartCommand? {
    val parts = element as? JsonArray ?: return null
    val op = (parts.firstOrNull() as? JsonPrimitive)?.content ?: return null
    val args = floats(JsonArray(parts.drop(1))) ?: return null
    return ChartCommand(op, args).takeIf { COMMAND_ARGS[op] == args.size }
}

private fun shapeOf(node: JsonObject): ChartShape? {
    fun required(key: String) = node.float(key)
    return when (node.text("kind")) {
        "line" -> ChartShape.Line(
            required("x1") ?: return null,
            required("y1") ?: return null,
            required("x2") ?: return null,
            required("y2") ?: return null,
        )
        "path" -> ChartShape.Curve(
            (node["commands"] as? JsonArray)?.mapNotNull(::commandOf) ?: return null,
            (node["evenOdd"] as? JsonPrimitive)?.booleanOrNull == true,
        )
        "circle" -> ChartShape.Circle(required("x") ?: return null, required("y") ?: return null, required("radius") ?: return null)
        "rect" -> ChartShape.Box(
            required("x") ?: return null,
            required("y") ?: return null,
            required("width") ?: return null,
            required("height") ?: return null,
            required("radius") ?: 0f,
        )
        "text" -> ChartShape.Label(
            required("x") ?: return null,
            required("y") ?: return null,
            node.text("text") ?: return null,
            node.text("anchor") ?: "start",
            node.text("baseline") ?: "auto",
            required("rotate") ?: 0f,
            required("size") ?: return null,
            required("weight")?.toInt() ?: 400,
        )
        else -> null
    }
}

private fun nodeOf(element: JsonElement): ChartNode? {
    val node = element as? JsonObject ?: return null
    val shape = shapeOf(node) ?: return null
    return ChartNode(
        shape = shape,
        fill = chartInk(node.text("fill"), if (shape is ChartShape.Line) ChartInk.None else ChartInk.Foreground),
        stroke = chartInk(node.text("stroke"), ChartInk.None),
        fillOpacity = node.float("fillOpacity") ?: 1f,
        strokeOpacity = node.float("strokeOpacity") ?: 1f,
        opacity = node.float("opacity") ?: 1f,
        strokeWidth = node.float("strokeWidth") ?: 1f,
        lineCap = node.text("lineCap"),
        lineJoin = node.text("lineJoin"),
        dash = floats(node["dash"]).orEmpty(),
        clip = floats(node["clip"])?.takeIf { it.size == 4 }?.let { ChartClip(it[0], it[1], it[2], it[3]) },
    )
}

private fun pointOf(element: JsonElement): ChartPoint? {
    val point = element as? JsonObject ?: return null
    return ChartPoint(
        x = point.float("x") ?: return null,
        y = point.float("y") ?: return null,
        title = point.text("title"),
        text = point.text("text"),
        rows = (point["rows"] as? JsonArray).orEmpty().mapNotNull { row ->
            val cells = row as? JsonObject ?: return@mapNotNull null
            ChartTooltipRow(cells.text("label") ?: return@mapNotNull null, cells.text("value") ?: return@mapNotNull null)
        },
    )
}

/** Each compiled width, narrowest first. Nodes are stored unchecked, so one bad node is dropped, not the chart. */
fun chartLayouts(layouts: JsonArray): List<ChartLayout> = layouts.mapNotNull { element ->
    val layout = element as? JsonObject ?: return@mapNotNull null
    ChartLayout(
        width = layout.float("width")?.takeIf { it > 0f } ?: return@mapNotNull null,
        height = layout.float("height")?.takeIf { it > 0f } ?: return@mapNotNull null,
        nodes = (layout["nodes"] as? JsonArray).orEmpty().mapNotNull(::nodeOf),
        points = (layout["points"] as? JsonArray).orEmpty().mapNotNull(::pointOf),
    )
}.sortedBy { it.width }

/** The widest layout that fits, or the narrowest when none does. */
fun chartLayoutFor(layouts: List<ChartLayout>, width: Float): ChartLayout? =
    layouts.lastOrNull { it.width <= width } ?: layouts.firstOrNull()

/** The hover target closest to a touch, in layout units, within `reach`. */
fun nearestChartPoint(points: List<ChartPoint>, x: Float, y: Float, reach: Float): ChartPoint? {
    var best: ChartPoint? = null
    var distance = reach
    for (point in points) {
        val d = hypot(point.x - x, point.y - y)
        if (d < distance) {
            best = point
            distance = d
        }
    }
    return best
}
