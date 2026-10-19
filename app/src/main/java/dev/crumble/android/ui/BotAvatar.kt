package dev.crumble.android.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import dev.crumble.android.model.Agent
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

// A port of apfelkern's BotAvatar (itself grown from Hermex's drawn faces, MIT, see
// THIRD_PARTY_NOTICES.md), so a seed draws the same bot here as on iOS and macOS. Only the dither
// finish the Swift apps use is ported; keep the numbers in step with apfelkern.

enum class BotState { idle, working, sleeping }

fun agentAvatarSeed(agent: Agent): String {
    val avatar = agent.avatar ?: ""
    for (prefix in listOf("bot:", "critters:")) {
        if (avatar.startsWith(prefix)) {
            val seed = avatar.removePrefix(prefix).trim()
            if (seed.isNotEmpty()) return seed
        }
    }
    val id = agent.id.trim()
    return id.ifEmpty { "crumble" }
}

enum class BotShape { circle, squircle, pill, triangle, hexagon, cloud, drop }

enum class BotMouth { smile, grin, cat, tiny }

enum class BotTopper { none, antenna, sprout, tuft }

/** How the face reads, apart from its motion. */
enum class BotExpression { calm, delighted, focused, asleep }

private fun seedHash(seed: String): ULong =
    seed.encodeToByteArray().fold(1_469_598_103_934_665_603UL) { acc, byte ->
        (acc xor (byte.toInt() and 0xFF).toULong()) * 1_099_511_628_211UL
    }

data class BotLook(
    val shape: BotShape,
    val hex: String,
    val hash: ULong,
    val mouth: BotMouth,
    val topper: BotTopper,
) {
    private constructor(hash: ULong) : this(
        shape = BotShape.entries[(hash % BotShape.entries.size.toULong()).toInt()],
        hex = palette[(hash % palette.size.toULong()).toInt()],
        hash = hash,
        mouth = BotMouth.entries[((hash shr 16) % BotMouth.entries.size.toULong()).toInt()],
        topper = BotTopper.entries[((hash shr 24) % BotTopper.entries.size.toULong()).toInt()],
    )

    constructor(seed: String) : this(seedHash(seed))

    val color: Color
        get() {
            val value = hex.removePrefix("#").toLongOrNull(16) ?: 0L
            return Color(
                red = ((value shr 16) and 0xFF) / 255f,
                green = ((value shr 8) and 0xFF) / 255f,
                blue = (value and 0xFF) / 255f,
            )
        }

    /** The body color moved toward white for positive amounts and toward black for negative ones. */
    fun tint(amount: Double): Color = color.mixed(if (amount > 0) Color.White else Color.Black, abs(amount))

    val inkColor: Color get() = tint(-0.8)

    /** Pink cheeks vanish on warm bodies, so those blush white instead. */
    val blushColor: Color
        get() = if (hex in listOf("#ef4444", "#ec4899", "#f97316", "#f59e0b")) {
            Color.White.copy(alpha = 0.5f)
        } else {
            Color(red = 1f, green = 0.42f, blue = 0.6f, alpha = 0.55f)
        }

    companion object {
        val palette = listOf(
            "#8b5cf6", "#38bdf8", "#14b8a6", "#22c55e",
            "#f59e0b", "#f97316", "#ef4444", "#ec4899",
        )
    }
}

private fun Color.mixed(target: Color, amount: Double): Color {
    val mix = amount.toFloat()
    return Color(
        red = red + (target.red - red) * mix,
        green = green + (target.green - green) * mix,
        blue = blue + (target.blue - blue) * mix,
        alpha = alpha,
    )
}

private fun Color.faded(opacity: Float): Color = copy(alpha = alpha * opacity)

/**
 * One frame of a face: gaze as a fraction of the size, roll in degrees, the lid factor, a lift as a
 * fraction of the size, the squash and stretch of the body anchored at its base, how far the mouth
 * opens, the topper's lag in degrees and the antenna's glow.
 */
data class BotPose(
    val gazeX: Double = 0.0,
    val gazeY: Double = 0.0,
    val roll: Double = 0.0,
    val lid: Double = 1.0,
    val lift: Double = 0.0,
    val scaleX: Double = 1.0,
    val scaleY: Double = 1.0,
    val mouthOpen: Double = 0.0,
    val sway: Double = 0.0,
    val glow: Double = 0.0,
) {
    operator fun times(weight: Double) = BotPose(
        gazeX * weight, gazeY * weight, roll * weight, lid * weight, lift * weight,
        scaleX * weight, scaleY * weight, mouthOpen * weight, sway * weight, glow * weight,
    )

    operator fun plus(other: BotPose) = BotPose(
        gazeX + other.gazeX, gazeY + other.gazeY, roll + other.roll, lid + other.lid, lift + other.lift,
        scaleX + other.scaleX, scaleY + other.scaleY, mouthOpen + other.mouthOpen, sway + other.sway,
        glow + other.glow,
    )

    companion object {
        val rest = BotPose()
        const val shutLid = 0.06

        // Every rhythm in a state repeats a whole number of times per loop, as in apfelkern.
        private const val idleLoop = 36.0
        private const val workingLoop = 25.2
        private const val sleepingLoop = 16.0

        private fun rate(count: Double, loop: Double) = 2 * PI * count / loop

        private fun snap(period: Double, loop: Double): Pair<Double, Int> {
            val count = max(1.0, (loop / period).roundToInt().toDouble())
            return Pair(loop / count, count.toInt())
        }

        /** Breathes, rocks a little, glances around and blinks, sometimes twice. */
        fun idle(time: Double, hash: ULong): BotPose {
            val local = time + (hash % 10_000UL).toDouble() / 1000
            val breath = sin(local * rate(10.0, idleLoop))
            val roll = sin(local * rate(3.0, idleLoop)) * 2.2
            val gaze = glance(local, hash)
            val stretchPhase = positive(local, if (hash % 2UL == 0UL) 12.0 else 18.0)
            val stretch = if (stretchPhase < 1.8) sin(stretchPhase / 1.8 * PI).pow(4) else 0.0
            return BotPose(
                gazeX = gaze.first,
                gazeY = gaze.second,
                roll = roll,
                lid = blink(local, hash),
                lift = 0.006 * (breath + 1),
                scaleX = 1 - 0.012 * breath - 0.035 * stretch,
                scaleY = 1 + 0.022 * breath + 0.055 * stretch,
                sway = -roll * 1.6 + sin(local * rate(8.0, idleLoop)) * 3 + stretch * 7,
            )
        }

        /** Leans aside and sways, hopping on the spot; each landing squashes into the next jump. */
        fun working(time: Double): BotPose {
            val loop = workingLoop
            val turn = -11 + sin(time * rate(2.0, loop)) * 8
            val tilt = sin(time * rate(2.0, loop) + 1.3) * 8 + sin(time * rate(4.0, loop)) * 1.6
            val blinkPeriod = loop / 17
            val blink = positive(time, blinkPeriod) > blinkPeriod * 0.869
            val hop = positive(time, 0.9) / 0.9
            val ground = 0.12
            val contact = min(hop, 1 - hop)
            val squash = if (contact < ground) 1 - 0.055 * cos(contact / ground * PI / 2) else 1.0
            val air = if (contact < ground) 0.0 else (hop - ground) / (1 - 2 * ground)
            return BotPose(
                gazeX = turn * 0.0025,
                gazeY = -tilt * 0.0025,
                roll = sin(time * rate(3.0, loop)) * 4.2,
                lid = if (blink) shutLid else 1.0,
                lift = 0.04 * sin(air * PI),
                scaleX = 2 - squash,
                scaleY = squash,
                mouthOpen = 0.5 + 0.5 * sin(time * rate(18.0, loop)),
                sway = -sin(air * 2 * PI) * 9 - sin(time * rate(3.0, loop)) * 4,
                glow = 0.55 + 0.45 * sin(time * rate(20.0, loop)),
            )
        }

        /** Breathes slowly with the head dropped a little and the mouth rising and falling. */
        fun sleeping(time: Double): BotPose {
            val breath = sin(time * rate(4.0, sleepingLoop))
            return BotPose(
                gazeY = 0.03,
                roll = -5 + sin(time * rate(1.0, sleepingLoop)) * 1.5,
                lid = shutLid,
                scaleX = 1 - 0.02 * breath,
                scaleY = 1 + 0.035 * breath,
                mouthOpen = 0.5 + 0.5 * breath,
                sway = 6 + breath * 3,
            )
        }

        /** Blinks for 180 ms every 3 to 5 seconds, seeded; every third blink is a double. */
        fun blink(time: Double, hash: ULong): Double {
            val (period, count) = snap(3 + (hash % 2001UL).toDouble() / 1000, idleLoop)
            val cycle = floor(time / period)
            val offset = time - cycle * period
            val shut = 0.18
            val closed = { moment: Double -> 1 - (1 - shutLid) * sin(moment / shut * PI) }
            if (offset < shut) return closed(offset)
            val double = mix(hash, wrap(cycle, count)) % 3UL == 0UL
            if (double && offset > 0.28 && offset < 0.28 + shut) return closed(offset - 0.28)
            return 1.0
        }

        /** Holds a gaze for two to three and a half seconds, then darts to the next one. */
        fun glance(time: Double, hash: ULong): Pair<Double, Double> {
            val (hold, count) = snap(2 + ((hash shr 8) % 1500UL).toDouble() / 1000, idleLoop)
            val step = floor(time / hold)
            val into = (time - step * hold) / 0.14
            val ease = if (into >= 1) 1.0 else into * into * (3 - 2 * into)
            val from = target(hash, wrap(step - 1, count))
            val next = target(hash, wrap(step, count))
            return Pair(from.first + (next.first - from.first) * ease, from.second + (next.second - from.second) * ease)
        }

        private fun positive(value: Double, modulo: Double): Double {
            val remainder = value % modulo
            return if (remainder < 0) remainder + modulo else remainder
        }

        private fun wrap(step: Double, count: Int): Long = positive(step, count.toDouble()).toLong()

        private fun target(hash: ULong, step: Long): Pair<Double, Double> {
            val value = mix(hash, step)
            if (value % 3UL == 0UL) return Pair(0.0, 0.0)
            return Pair(
                ((value % 1000UL).toDouble() / 500 - 1) * 0.035,
                (((value shr 20) % 1000UL).toDouble() / 500 - 1) * 0.022,
            )
        }

        // Swift's `>>` binds tighter than `&*`, so the shift applies to the constant.
        private fun mix(hash: ULong, step: Long): ULong {
            var value = (hash xor step.toULong()) * 0x9E3779B97F4A7C15UL
            value = value xor (value shr 31)
            return value * (0xBF58476D1CE4E5B9UL shr 7)
        }
    }
}

/** How much of each state a live avatar shows; state changes ease between two blends. */
private data class BotBlend(val idle: Double, val working: Double, val sleeping: Double) {
    constructor(state: BotState) : this(
        idle = if (state == BotState.idle) 1.0 else 0.0,
        working = if (state == BotState.working) 1.0 else 0.0,
        sleeping = if (state == BotState.sleeping) 1.0 else 0.0,
    )

    fun mixed(target: BotBlend, amount: Double) = BotBlend(
        idle + (target.idle - idle) * amount,
        working + (target.working - working) * amount,
        sleeping + (target.sleeping - sleeping) * amount,
    )

    fun pose(time: Double, hash: ULong): BotPose {
        val local = time + (hash % 10_000UL).toDouble() / 1000
        if (sleeping == 1.0) return BotPose.sleeping(local)
        val workTime = local * (0.92 + (hash % 17UL).toDouble() / 100)
        if (working == 1.0) return BotPose.working(workTime)
        val rest = if (idle > 0) BotPose.idle(time, hash) else BotPose.rest
        if (idle == 1.0) return rest
        val work = if (working > 0) BotPose.working(workTime) else BotPose.rest
        val sleep = if (sleeping > 0) BotPose.sleeping(local) else BotPose.rest
        return rest * idle + work * working + sleep * sleeping
    }

    val expressions: List<Pair<BotExpression, Double>>
        get() = listOf(
            BotExpression.calm to idle,
            BotExpression.focused to working,
            BotExpression.asleep to sleeping,
        )
}

private class BotMotion(state: BotState) {
    private var from = BotBlend(state)
    private var target = BotBlend(state)
    private var since = Double.NEGATIVE_INFINITY
    private var duration = 0.6
    var shown = state
        private set

    fun blend(time: Double): BotBlend {
        val progress = ((time - since) / duration).coerceIn(0.0, 1.0)
        return from.mixed(target, progress * progress * (3 - 2 * progress))
    }

    fun move(state: BotState, time: Double) {
        from = blend(time)
        target = BotBlend(state)
        since = time
        duration = if (state == BotState.sleeping) 0.95 else 0.55
        shown = state
    }
}

@Composable
fun BotAvatarView(
    agent: Agent,
    size: Dp,
    state: BotState = BotState.idle,
    live: Boolean = false,
    modifier: Modifier = Modifier,
) {
    BotAvatarView(
        seed = agentAvatarSeed(agent),
        size = size,
        state = state,
        live = live,
        modifier = modifier,
    )
}

@Composable
fun BotAvatarView(
    seed: String,
    size: Dp,
    state: BotState = BotState.idle,
    live: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val look = remember(seed) { BotLook(seed) }
    val context = LocalContext.current
    val resolver = context.contentResolver
    var animationsEnabled by remember(resolver) {
        mutableStateOf(Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f)
    }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                animationsEnabled = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        observer.onChange(false)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    val animating = live && animationsEnabled
    // Seconds on the frame clock. Only the draw phase reads it, so frames redraw without recomposing.
    var time by remember { mutableDoubleStateOf(0.0) }
    val motion = remember(look) { BotMotion(state) }
    SideEffect { if (motion.shown != state) motion.move(state, time) }

    if (animating) {
        LaunchedEffect(Unit) {
            while (true) withFrameNanos { time = it / 1_000_000_000.0 }
        }
    }

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val side = this.size.minDimension
            if (animating) {
                val now = time
                val blend = motion.blend(now)
                BotPainter(look, side, density, blend.pose(now, look.hash), BotExpression.calm)
                    .paint(this, blend.expressions)
            } else {
                BotPainter(look, side, density, BotPose.rest, state.expression).paint(this, null)
            }
        }

        if (animating) {
            SleepZs(size, { time + (look.hash % 1000UL).toDouble() / 1000 }, { motion.blend(time).sleeping })
        }
    }
}

private val BotState.expression: BotExpression
    get() = when (this) {
        BotState.idle -> BotExpression.calm
        BotState.working -> BotExpression.focused
        BotState.sleeping -> BotExpression.asleep
    }

/** Two Zs that rise from a sleeping bot, grow, and fade, one after the other. */
@Composable
private fun SleepZs(size: Dp, time: () -> Double, sleeping: () -> Double) {
    val fontSize = with(LocalDensity.current) { (size * 0.26f).toSp() }
    for (index in 0 until 2) {
        val phase = { (time() / 3 + index * 0.5).mod(1.0).toFloat() }
        Text(
            text = "z",
            fontSize = fontSize,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .offset {
                    IntOffset(
                        (size * (0.34f + 0.12f * phase())).roundToPx(),
                        (-size * (0.22f + 0.32f * phase())).roundToPx(),
                    )
                }
                .graphicsLayer {
                    val scale = (0.16f + 0.1f * phase()) / 0.26f
                    scaleX = scale
                    scaleY = scale
                    alpha = (sin(phase() * PI) * sleeping()).toFloat().coerceIn(0f, 1f)
                },
        )
    }
}

/** Where a shape's face and topper sit, as fractions of the body's frame, and how big the face is. */
private class BotLayout(val face: Offset, val faceScale: Float, val top: Offset) {
    companion object {
        fun of(shape: BotShape) = when (shape) {
            BotShape.circle, BotShape.squircle -> BotLayout(Offset(0.6f, 0.47f), 1f, Offset(0.5f, 0.08f))
            BotShape.hexagon -> BotLayout(Offset(0.58f, 0.49f), 0.95f, Offset(0.5f, 0.08f))
            BotShape.pill -> BotLayout(Offset(0.6f, 0.49f), 0.8f, Offset(0.5f, 0.23f))
            BotShape.triangle -> BotLayout(Offset(0.54f, 0.64f), 0.78f, Offset(0.5f, 0.08f))
            BotShape.cloud -> BotLayout(Offset(0.56f, 0.52f), 0.85f, Offset(0.53f, 0.12f))
            BotShape.drop -> BotLayout(Offset(0.54f, 0.62f), 0.9f, Offset(0.5f, 0.08f))
        }
    }
}

/**
 * Paints one frame of a bot. Lengths are fractions of `side` in pixels; the body sits low enough in
 * the square to leave room for its topper above and its shadow below. `density` converts
 * apfelkern's point minimums. `fade` scales the face's opacity while expressions blend.
 */
private class BotPainter(
    val look: BotLook,
    val side: Float,
    val density: Float,
    val pose: BotPose,
    val expression: BotExpression,
    val fade: Float = 1f,
) {
    private val frame = Rect(side * 0.04f, side * 0.1f, side * 0.96f, side * 1.02f)
    private val layout = BotLayout.of(look.shape)
    private val detailed = side >= 22 * density
    private val faceCenter = point(layout.face)
    private val unit = side * layout.faceScale
    private val gazeX = pose.gazeX.toFloat()
    private val gazeY = pose.gazeY.toFloat()

    private fun point(unit: Offset) = Offset(frame.left + unit.x * frame.width, frame.top + unit.y * frame.height)

    private val topperBase: Offset get() = point(Offset(layout.top.x, layout.top.y + 0.06f))

    private fun eyeCenter(side: Float) = Offset(
        faceCenter.x + unit * side * 0.0875f + side * gazeX,
        faceCenter.y - unit * 0.045f + side * gazeY,
    )

    private val mouthCenter: Offset
        get() = Offset(
            faceCenter.x - unit * 0.012f + side * gazeX * 0.65f,
            faceCenter.y + unit * 0.125f + side * gazeY * 0.65f,
        )

    private val ink: Color get() = look.inkColor.faded(fade)

    fun paint(scope: DrawScope, expressions: List<Pair<BotExpression, Double>>?) = with(scope) {
        val body = bodyPath(look.shape, frame)
        val bounds = body.getBounds()
        paintShadow(bounds)
        val base = Offset(bounds.center.x, bounds.bottom)
        withTransform({
            translate(top = -side * pose.lift.toFloat())
            translate(base.x, base.y)
            scale(pose.scaleX.toFloat(), pose.scaleY.toFloat(), Offset.Zero)
            rotate(pose.roll.toFloat(), Offset.Zero)
            translate(-base.x, -base.y)
        }) {
            paintTopper()
            paintBody(body, bounds)
            if (expressions == null) {
                paintFace()
            } else {
                for ((face, opacity) in expressions) {
                    if (opacity > 0.001) {
                        BotPainter(look, side, density, pose, face, opacity.toFloat()).run { paintFace() }
                    }
                }
            }
        }
    }

    private fun DrawScope.paintShadow(bounds: Rect) {
        val rise = min(1.0, pose.lift * 9).toFloat()
        val width = bounds.width * 0.66f * pose.scaleX.toFloat() * (1 - 0.35f * rise)
        val height = side * 0.055f * (1 - 0.3f * rise)
        val grow = side * 0.012f
        softOval(
            Rect(
                bounds.center.x - width / 2 - grow,
                bounds.bottom - height * 0.45f - grow,
                bounds.center.x + width / 2 + grow,
                bounds.bottom + height * 0.55f + grow,
            ),
            Color.Black.copy(alpha = 0.2f * (1 - 0.55f * rise)),
        )
    }

    private fun DrawScope.paintBody(body: Path, bounds: Rect) {
        drawPath(body, look.color)
        clipPath(body) {
            paintDither(bounds)
            if (detailed) paintCheeks()
        }
        drawPath(body, look.tint(-0.45).copy(alpha = 0.3f), style = Stroke(max(0.6f * density, side * 0.01f)))
    }

    /** Light from the top left with a soft highlight, from -1 in shadow to 1 fully lit. */
    private fun light(point: Offset, bounds: Rect): Float {
        val across = (point.x - bounds.left) / bounds.width
        val down = (point.y - bounds.top) / bounds.height
        val falloff = (0.42f - (0.4f * across + 0.6f * down)) * 1.9f
        val spot = max(0f, 1 - hypot(across - 0.33f, down - 0.22f) / 0.26f)
        return (falloff + 0.6f * spot).coerceIn(-1f, 1f)
    }

    /** A 4×4 ordered dither between the body color and a lighter and a darker step of it. */
    private fun DrawScope.paintDither(bounds: Rect) {
        // apfelkern's side / 44 cell, in whole pixels so the squares stay sharp.
        val cell = max(1f, (side / 44).roundToInt().toFloat())
        val origin = Offset(floor(bounds.left), floor(bounds.top))
        val lit = Path()
        val shade = Path()
        for (row in 0 until ((bounds.bottom - origin.y) / cell + 1).toInt()) {
            for (column in 0 until ((bounds.right - origin.x) / cell + 1).toInt()) {
                val square = Rect(Offset(origin.x + column * cell, origin.y + row * cell), Size(cell, cell))
                val value = light(square.center, bounds)
                val threshold = (bayer[row % 4][column % 4] + 0.5f) / 16
                if (value > threshold) {
                    lit.addRect(square)
                } else if (-value > threshold) {
                    shade.addRect(square)
                }
            }
        }
        drawPath(lit, look.tint(0.3))
        drawPath(shade, look.tint(-0.24))
    }

    private fun DrawScope.paintCheeks() {
        val glow = if (expression == BotExpression.delighted) 0.22f else 0.12f
        for (side in listOf(-1f, 1f)) {
            val center = Offset(
                faceCenter.x + unit * (side * 0.175f + gazeX * 0.5f / layout.faceScale - 0.01f),
                faceCenter.y + unit * 0.085f + side * gazeY * 0.5f,
            )
            softOval(
                Rect(center.x - unit * 0.045f, center.y - unit * 0.025f, center.x + unit * 0.045f, center.y + unit * 0.025f),
                look.blushColor.faded(glow),
            )
        }
    }

    // Face

    private fun DrawScope.paintFace() {
        for (side in listOf(-1f, 1f)) {
            if (expression == BotExpression.asleep) paintClosedEye(eyeCenter(side)) else paintEye(eyeCenter(side))
        }
        paintMouth()
    }

    private fun DrawScope.paintEye(center: Offset) {
        val width = unit * 0.068f
        val height = max(width * 0.55f, unit * 0.145f * pose.lid.toFloat())
        withTransform({
            translate(center.x, center.y)
            rotate(-8f, Offset.Zero)
        }) {
            drawRoundRect(ink, Offset(-width / 2, -height / 2), Size(width, height), CornerRadius(min(width, height) / 2))
            if (detailed && pose.lid > 0.6) {
                val spark = width * 0.25f
                drawOval(Color.White.copy(alpha = 0.5f * fade), Offset(-width * 0.28f, -height * 0.36f), Size(spark, spark))
            }
        }
    }

    private fun DrawScope.paintClosedEye(center: Offset) {
        val half = unit * 0.05f
        val lid = Path().apply {
            moveTo(center.x - half, center.y + unit * 0.02f)
            quadraticTo(center.x, center.y + unit * 0.065f, center.x + half, center.y + unit * 0.005f)
        }
        drawPath(lid, ink, style = Stroke(unit * 0.034f, cap = StrokeCap.Round))
    }

    private fun DrawScope.paintMouth() {
        val center = mouthCenter
        val line = Stroke(max(0.8f * density, unit * 0.026f), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val open = pose.mouthOpen.toFloat()
        when (expression) {
            BotExpression.asleep -> drawPath(oval(center, unit * 0.034f, unit * (0.022f + 0.016f * open)), ink)
            BotExpression.focused -> drawPath(oval(center, unit * 0.046f, unit * (0.03f + 0.022f * open)), ink)
            BotExpression.delighted -> paintGrin(center, unit * 0.105f, unit * 0.035f)
            BotExpression.calm -> when (look.mouth) {
                BotMouth.smile -> drawPath(smile(center, unit * 0.1f, unit * 0.025f), ink, style = line)
                BotMouth.tiny -> drawPath(smile(center, unit * 0.06f, unit * 0.015f), ink, style = line)
                BotMouth.grin -> paintGrin(center, unit * 0.09f, unit * 0.025f)
                BotMouth.cat -> {
                    val cat = smile(Offset(center.x - unit * 0.026f, center.y), unit * 0.052f, unit * 0.03f)
                    cat.addPath(smile(Offset(center.x + unit * 0.026f, center.y), unit * 0.052f, unit * 0.03f))
                    drawPath(cat, ink, style = line)
                }
            }
        }
    }

    private fun DrawScope.paintGrin(center: Offset, width: Float, depth: Float) {
        val grin = Path().apply {
            moveTo(center.x - width / 2, center.y - depth * 0.25f)
            lineTo(center.x + width / 2, center.y - depth * 0.25f)
            quadraticTo(center.x, center.y + depth * 1.6f, center.x - width / 2, center.y - depth * 0.25f)
            close()
        }
        drawPath(grin, ink)
        if (!detailed) return
        clipPath(grin) {
            drawPath(
                oval(Offset(center.x, center.y + depth * 0.95f), width * 0.32f, depth * 0.45f),
                Color(red = 1f, green = 0.45f, blue = 0.55f).faded(fade),
            )
        }
    }

    // Topper

    private fun DrawScope.paintTopper() {
        if (look.topper == BotTopper.none) return
        val base = topperBase
        withTransform({
            translate(base.x, base.y)
            rotate(pose.sway.toFloat(), Offset.Zero)
        }) {
            when (look.topper) {
                BotTopper.none -> Unit
                BotTopper.antenna -> paintAntenna()
                BotTopper.sprout -> paintSprout()
                BotTopper.tuft -> paintTuft()
            }
        }
    }

    private fun DrawScope.paintAntenna() {
        val stalk = quad(Offset.Zero, Offset(side * 0.025f, -side * 0.09f), Offset(0f, -side * 0.16f))
        drawPath(stalk, look.tint(-0.35), style = Stroke(side * 0.036f, cap = StrokeCap.Round))
        val glow = pose.glow.toFloat()
        val tip = Offset(0f, -side * 0.16f)
        val radius = side * 0.058f
        if (glow > 0) {
            val halo = radius + side * 0.07f * glow
            softOval(Rect(tip, halo), look.tint(0.5).copy(alpha = glow * 0.6f))
        }
        drawCircle(look.tint(0.4 + 0.3 * glow), radius, tip)
    }

    private fun DrawScope.paintSprout() {
        val green = if (look.hex == "#22c55e" || look.hex == "#14b8a6") {
            Color(red = 0.75f, green = 0.95f, blue = 0.39f)
        } else {
            Color(red = 0.29f, green = 0.85f, blue = 0.5f)
        }
        val top = Offset(0f, -side * 0.1f)
        val stem = quad(Offset.Zero, Offset(-side * 0.02f, -side * 0.05f), top)
        drawPath(stem, green.mixed(Color.Black, 0.3), style = Stroke(side * 0.03f, cap = StrokeCap.Round))
        for (side in listOf(-1f, 1f)) {
            withTransform({
                translate(top.x, top.y)
                rotate(side * 50 - 8, Offset.Zero)
            }) {
                drawPath(
                    oval(Offset(0f, -side * 0.05f), side * 0.075f, side * 0.115f),
                    Brush.linearGradient(
                        listOf(green.mixed(Color.White, 0.25), green),
                        start = Offset(0f, -side * 0.1f),
                        end = Offset.Zero,
                    ),
                )
            }
        }
    }

    private fun DrawScope.paintTuft() {
        val color = look.tint(-0.3)
        for ((angle, length) in listOf(-28f to 0.085f, 0f to 0.11f, 28f to 0.08f)) {
            withTransform({ rotate(angle, Offset.Zero) }) {
                val hair = quad(Offset.Zero, Offset(-side * 0.03f, -side * length * 0.6f), Offset(side * 0.035f, -side * length))
                drawPath(hair, color, style = Stroke(side * 0.034f, cap = StrokeCap.Round))
            }
        }
    }

    // Helpers

    /** A radial falloff filling `area`: full color in the middle, fading out at the edge. */
    private fun DrawScope.softOval(area: Rect, color: Color) {
        withTransform({
            translate(area.center.x, area.center.y)
            scale(area.width / 2, area.height / 2, Offset.Zero)
        }) {
            drawCircle(
                Brush.radialGradient(
                    listOf(color, color.faded(0.65f), color.copy(alpha = 0f)),
                    center = Offset.Zero,
                    radius = 1f,
                ),
                radius = 1f,
                center = Offset.Zero,
            )
        }
    }

    companion object {
        private val bayer = listOf(
            floatArrayOf(0f, 8f, 2f, 10f),
            floatArrayOf(12f, 4f, 14f, 6f),
            floatArrayOf(3f, 11f, 1f, 9f),
            floatArrayOf(15f, 7f, 13f, 5f),
        )
    }
}

private fun quad(from: Offset, control: Offset, to: Offset) = Path().apply {
    moveTo(from.x, from.y)
    quadraticTo(control.x, control.y, to.x, to.y)
}

private fun smile(center: Offset, width: Float, depth: Float) =
    quad(
        Offset(center.x - width / 2, center.y - depth / 2),
        Offset(center.x, center.y + depth * 1.1f),
        Offset(center.x + width / 2, center.y - depth / 2),
    )

private fun oval(center: Offset, width: Float, height: Float) = Path().apply {
    addOval(Rect(center.x - width / 2, center.y - height / 2, center.x + width / 2, center.y + height / 2))
}

/** The body outline in `rect`, as apfelkern's `BotBody` draws it. */
private fun bodyPath(shape: BotShape, rect: Rect): Path {
    val dx = rect.width * 0.08f
    val dy = rect.height * 0.08f
    val inset = Rect(rect.left + dx, rect.top + dy, rect.right - dx, rect.bottom - dy)
    val path = Path()
    when (shape) {
        BotShape.circle -> {
            path.addOval(inset)
        }
        BotShape.squircle -> {
            val r = rect.width * 0.28f
            path.addRoundRect(RoundRect(inset, CornerRadius(r, r)))
        }
        BotShape.pill -> {
            val h = rect.height * 0.54f
            val top = rect.center.y - rect.height * 0.27f
            val pill = Rect(inset.left, top, inset.right, top + h)
            path.addRoundRect(RoundRect(pill, CornerRadius(h / 2f, h / 2f)))
        }
        BotShape.triangle -> {
            path.moveTo(rect.center.x, inset.top)
            path.lineTo(inset.right, inset.bottom)
            path.lineTo(inset.left, inset.bottom)
            path.close()
        }
        BotShape.hexagon -> {
            path.moveTo(rect.center.x, inset.top)
            path.lineTo(inset.right, rect.top + rect.height * 0.3f)
            path.lineTo(inset.right, rect.top + rect.height * 0.7f)
            path.lineTo(rect.center.x, inset.bottom)
            path.lineTo(inset.left, rect.top + rect.height * 0.7f)
            path.lineTo(inset.left, rect.top + rect.height * 0.3f)
            path.close()
        }
        BotShape.cloud -> {
            // Three puffs merged into one outline, so strokes and shading never show the seams.
            val puffs = listOf(
                floatArrayOf(0.08f, 0.38f, 0.48f, 0.48f),
                floatArrayOf(0.28f, 0.12f, 0.5f, 0.64f),
                floatArrayOf(0.55f, 0.35f, 0.38f, 0.45f),
            ).map { (left, top, width, height) ->
                Path().apply {
                    addOval(
                        Rect(
                            Offset(rect.left + rect.width * left, rect.top + rect.height * top),
                            Size(rect.width * width, rect.height * height),
                        ),
                    )
                }
            }
            return puffs.drop(1).fold(puffs[0]) { union, puff -> Path.combine(PathOperation.Union, union, puff) }
        }
        BotShape.drop -> {
            path.moveTo(rect.center.x, inset.top)
            path.cubicTo(
                rect.center.x + rect.width * 0.22f,
                rect.top + rect.height * 0.2f,
                inset.right,
                rect.top + rect.height * 0.42f,
                inset.right,
                rect.top + rect.height * 0.62f,
            )
            path.cubicTo(
                inset.right,
                inset.bottom,
                inset.left,
                inset.bottom,
                inset.left,
                rect.top + rect.height * 0.62f,
            )
            path.cubicTo(
                inset.left,
                rect.top + rect.height * 0.42f,
                rect.center.x - rect.width * 0.22f,
                rect.top + rect.height * 0.2f,
                rect.center.x,
                inset.top,
            )
            path.close()
        }
    }
    return path
}
