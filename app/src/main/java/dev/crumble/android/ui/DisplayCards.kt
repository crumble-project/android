package dev.crumble.android.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.ChartInk
import dev.crumble.android.model.ChartLayout
import dev.crumble.android.model.ChartNode
import dev.crumble.android.model.ChartPoint
import dev.crumble.android.model.ChartShape
import dev.crumble.android.model.DisplayCard
import dev.crumble.android.model.DisplayCell
import dev.crumble.android.model.DisplayStat
import dev.crumble.android.model.Message
import dev.crumble.android.model.chartLayoutFor
import dev.crumble.android.model.chartLayouts
import dev.crumble.android.model.fitColumns
import dev.crumble.android.model.nearestChartPoint
import dev.crumble.android.model.numericColumns
import java.text.NumberFormat
import kotlinx.serialization.json.JsonArray

private val CARD_SHAPE = RoundedCornerShape(12.dp)

@Composable
internal fun DisplayCardView(card: DisplayCard) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CARD_SHAPE)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        card.title?.takeIf { it.isNotEmpty() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
        }
        when (card) {
            is DisplayCard.Stats -> StatGrid(card.items)
            is DisplayCard.Table -> DisplayTable(card.columns, card.rows)
            is DisplayCard.Chart -> DisplayChart(card.layouts, card.title)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatGrid(items: List<DisplayStat>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (item in items) StatCell(item)
    }
}

@Composable
private fun StatCell(item: DisplayStat) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = Modifier.widthIn(min = 126.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(item.label, style = MaterialTheme.typography.labelSmall, color = muted)
        Text(item.value, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold))
        item.change?.let { change ->
            val color = when (item.trend) {
                "up" -> if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF34D399) else Color(0xFF059669)
                "down" -> MaterialTheme.colorScheme.error
                else -> muted
            }
            val icon = when (item.trend) {
                "up" -> Icons.Default.ArrowUpward
                "down" -> Icons.Default.ArrowDownward
                "flat" -> Icons.Default.ArrowForward
                else -> null
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                icon?.let { Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(12.dp)) }
                Text(change, style = MaterialTheme.typography.labelSmall, color = color)
            }
        }
        item.caption?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = muted) }
    }
}

private fun cellText(cell: DisplayCell, format: NumberFormat) =
    cell.number?.let(format::format) ?: cell.text

@Composable
private fun DisplayTable(columns: List<String>, rows: List<List<DisplayCell>>) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodySmall
    val density = LocalDensity.current
    val cellPadding = 8.dp
    val format = remember { NumberFormat.getInstance() }
    val texts = remember(rows, format) { rows.map { row -> row.map { cellText(it, format) } } }
    val count = maxOf(columns.size, rows.maxOfOrNull { it.size } ?: 0)
    val numeric = remember(columns, rows, count) { numericColumns(count, rows) }
    val natural = remember(columns, texts, count, style) {
        List(count) { index ->
            val cells = listOfNotNull(columns.getOrNull(index)) + texts.mapNotNull { it.getOrNull(index) }
            val widest = cells.maxOfOrNull { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width } ?: 0
            with(density) { widest.toDp() + cellPadding * 2 + 1.dp }
        }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val widths = remember(natural, maxWidth) { fitColumns(natural.map { it.value }, maxWidth.value).map { it.dp } }
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            @Composable
            fun TableRow(cells: List<String>, color: Color) {
                Row {
                    cells.forEachIndexed { index, text ->
                        Text(
                            text = text,
                            style = style,
                            color = color,
                            maxLines = 1,
                            softWrap = false,
                            textAlign = if (numeric.getOrElse(index) { false }) TextAlign.End else TextAlign.Start,
                            modifier = Modifier
                                .width(widths[index])
                                .padding(horizontal = cellPadding, vertical = 6.dp),
                        )
                    }
                }
            }
            val divider = Modifier.width(widths.fold(0.dp) { sum, each -> sum + each })
            TableRow(columns, MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(modifier = divider)
            texts.forEachIndexed { index, row ->
                TableRow(row, MaterialTheme.colorScheme.onSurface)
                if (index != texts.lastIndex) HorizontalDivider(modifier = divider)
            }
        }
    }
}

private fun ChartInk.color(foreground: Color, background: Color): Color? = when (this) {
    ChartInk.None -> null
    ChartInk.Foreground -> foreground
    ChartInk.Background -> background
    is ChartInk.Literal -> Color(argb)
}

private fun curveOf(shape: ChartShape.Curve) = Path().apply {
    fillType = if (shape.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero
    for (command in shape.commands) {
        val a = command.args
        when (command.op) {
            "M" -> moveTo(a[0], a[1])
            "L" -> lineTo(a[0], a[1])
            "Q" -> quadraticTo(a[0], a[1], a[2], a[3])
            "C" -> cubicTo(a[0], a[1], a[2], a[3], a[4], a[5])
            "Z" -> close()
        }
    }
}

private fun strokeOf(node: ChartNode): Stroke {
    // SVG repeats an odd dash list; Android needs pairs and fails on an all-zero pattern.
    val dash = node.dash.takeIf { list -> list.isNotEmpty() && list.none { it < 0f } && list.sum() > 0f }
        ?.let { if (it.size % 2 == 0) it else it + it }
    return Stroke(
        width = node.strokeWidth,
        cap = when (node.lineCap) {
            "round" -> StrokeCap.Round
            "square" -> StrokeCap.Square
            else -> StrokeCap.Butt
        },
        join = when (node.lineJoin) {
            "round" -> StrokeJoin.Round
            "bevel" -> StrokeJoin.Bevel
            else -> StrokeJoin.Miter
        },
        pathEffect = dash?.let { PathEffect.dashPathEffect(it.toFloatArray()) },
    )
}

private fun DrawScope.drawLabel(shape: ChartShape.Label, color: Color, measurer: TextMeasurer) {
    val style = TextStyle(
        color = color,
        fontSize = shape.size.toSp(),
        fontWeight = FontWeight(shape.weight.coerceIn(1, 1000)),
    )
    val measured = measurer.measure(shape.text, style, softWrap = false, maxLines = 1)
    val left = when (shape.anchor) {
        "middle" -> shape.x - measured.size.width / 2f
        "end" -> shape.x - measured.size.width
        else -> shape.x
    }
    val top = when (shape.baseline) {
        "middle" -> shape.y - measured.size.height / 2f
        "hanging" -> shape.y
        else -> shape.y - measured.firstBaseline
    }
    rotate(shape.rotate, pivot = Offset(shape.x, shape.y)) {
        drawText(measured, topLeft = Offset(left, top))
    }
}

private fun DrawScope.drawShape(node: ChartNode, foreground: Color, background: Color, measurer: TextMeasurer) {
    val fill = node.fill.color(foreground, background)?.let { it.copy(alpha = it.alpha * node.fillOpacity * node.opacity) }
    // Android draws a hairline for width 0, where SVG draws nothing.
    val stroke = node.stroke.color(foreground, background)
        ?.takeIf { node.strokeWidth > 0f }
        ?.let { it.copy(alpha = it.alpha * node.strokeOpacity * node.opacity) }
    val style = strokeOf(node)
    when (val shape = node.shape) {
        is ChartShape.Line -> stroke?.let {
            drawLine(
                color = it,
                start = Offset(shape.x1, shape.y1),
                end = Offset(shape.x2, shape.y2),
                strokeWidth = style.width,
                cap = style.cap,
                pathEffect = style.pathEffect,
            )
        }
        is ChartShape.Curve -> {
            val path = curveOf(shape)
            fill?.let { drawPath(path, it, style = Fill) }
            stroke?.let { drawPath(path, it, style = style) }
        }
        is ChartShape.Circle -> {
            val center = Offset(shape.x, shape.y)
            fill?.let { drawCircle(it, shape.radius, center, style = Fill) }
            stroke?.let { drawCircle(it, shape.radius, center, style = style) }
        }
        is ChartShape.Box -> {
            val corner = CornerRadius(shape.radius)
            val topLeft = Offset(shape.x, shape.y)
            val size = Size(shape.width, shape.height)
            fill?.let { drawRoundRect(it, topLeft, size, corner, style = Fill) }
            stroke?.let { drawRoundRect(it, topLeft, size, corner, style = style) }
        }
        is ChartShape.Label -> fill?.let { drawLabel(shape, it, measurer) }
    }
}

private fun DrawScope.drawNode(node: ChartNode, foreground: Color, background: Color, measurer: TextMeasurer) {
    val clip = node.clip
    if (clip == null) {
        drawShape(node, foreground, background, measurer)
    } else {
        clipRect(clip.x, clip.y, clip.x + clip.width, clip.y + clip.height) {
            drawShape(node, foreground, background, measurer)
        }
    }
}

@Composable
private fun DisplayChart(layouts: JsonArray, title: String?) {
    val parsed = remember(layouts) { chartLayouts(layouts) }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val layout = chartLayoutFor(parsed, maxWidth.value) ?: return@BoxWithConstraints
        val shown = minOf(maxWidth, parsed.last().width.dp)
        ChartCanvas(layout, shown.value, title)
    }
}

@Composable
private fun ChartCanvas(layout: ChartLayout, widthDp: Float, title: String?) {
    val foreground = MaterialTheme.colorScheme.onSurface
    val background = MaterialTheme.colorScheme.surface
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var selected by remember(layout) { mutableStateOf<ChartPoint?>(null) }
    val scaleUnits = layout.width / with(density) { widthDp.dp.toPx() }

    Box(modifier = Modifier.width(widthDp.dp).aspectRatio(layout.width / layout.height)) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .semantics { contentDescription = title ?: "Chart" }
                .pointerInput(layout, scaleUnits) {
                    detectTapGestures { touch ->
                        selected = nearestChartPoint(
                            layout.points,
                            touch.x * scaleUnits,
                            touch.y * scaleUnits,
                            with(density) { 32.dp.toPx() } * scaleUnits,
                        )
                    }
                },
        ) {
            scale(size.width / layout.width, pivot = Offset.Zero) {
                layout.nodes.forEach { drawNode(it, foreground, background, measurer) }
                selected?.let { point ->
                    drawCircle(foreground, 5f, Offset(point.x, point.y), style = Stroke(width = 2f))
                }
            }
        }
        selected?.let { point ->
            ChartTooltip(
                point = point,
                modifier = Modifier.layout { measurable, constraints ->
                    val parentWidth = constraints.maxWidth
                    val tooltip = measurable.measure(Constraints(maxWidth = parentWidth))
                    val anchorX = (point.x / scaleUnits).toInt()
                    val anchorY = (point.y / scaleUnits).toInt()
                    val x = (anchorX - tooltip.width / 2).coerceIn(0, maxOf(0, parentWidth - tooltip.width))
                    val y = maxOf(0, anchorY - tooltip.height - 10.dp.roundToPx())
                    layout(tooltip.width, tooltip.height) { tooltip.place(x, y) }
                },
            )
        }
    }
}

@Composable
private fun ChartTooltip(point: ChartPoint, modifier: Modifier) {
    val small = MaterialTheme.typography.labelSmall
    Surface(modifier = modifier, shape = RoundedCornerShape(6.dp), tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            point.title?.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = small.copy(fontWeight = FontWeight.Medium))
            }
            point.text?.let { Text(it, style = small) }
            for (row in point.rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(row.label, style = small, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(row.value, style = small)
                }
            }
        }
    }
}

/** The picture a `desktop_screenshot` call took, stored as the result's first attachment. */
@Composable
internal fun DesktopScreenshotCard(model: AppModel, message: Message) {
    val entryID = message.entryId ?: return
    LaunchedEffect(entryID) { model.loadAttachmentImage(entryID = entryID, index = 0) }
    val bytes = model.attachmentImageBytes(message, 0)
    val bitmap = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "Desktop screenshot",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 512.dp)
                .clip(CARD_SHAPE),
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), CARD_SHAPE),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}
