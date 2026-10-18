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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import dev.crumble.android.model.Agent
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// Bot avatars are Hermex's drawn faces (MIT, see THIRD_PARTY_NOTICES.md): a flat shape in one
// color with two leaning capsule eyes. The seed picks the shape and color.

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

private fun seedHash(seed: String): ULong =
    seed.encodeToByteArray().fold(1_469_598_103_934_665_603UL) { acc, byte ->
        (acc xor (byte.toInt() and 0xFF).toULong()) * 1_099_511_628_211UL
    }

data class BotLook(
    val shape: BotShape,
    val hex: String,
    val hash: ULong,
) {
    constructor(seed: String) : this(
        shape = BotShape.entries[(seedHash(seed) % BotShape.entries.size.toULong()).toInt()],
        hex = palette[(seedHash(seed) % palette.size.toULong()).toInt()],
        hash = seedHash(seed),
    )

    val rgb: Triple<Double, Double, Double>
        get() {
            val v = hex.removePrefix("#").toLongOrNull(16) ?: 0L
            return Triple(
                ((v shr 16) and 0xFF).toDouble(),
                ((v shr 8) and 0xFF).toDouble(),
                (v and 0xFF).toDouble(),
            )
        }

    val color: Color
        get() {
            val (r, g, b) = rgb
            return Color(red = (r / 255.0).toFloat(), green = (g / 255.0).toFloat(), blue = (b / 255.0).toFloat())
        }

    val eyeColor: Color
        get() {
            val (r, g, b) = rgb
            return if (0.2126 * r + 0.7152 * g + 0.0722 * b < 110.0) {
                Color.White.copy(alpha = 0.9f)
            } else {
                Color.Black.copy(alpha = 0.85f)
            }
        }

    companion object {
        val palette = listOf(
            "#8b5cf6", "#38bdf8", "#14b8a6", "#22c55e",
            "#f59e0b", "#f97316", "#ef4444", "#ec4899",
        )
    }
}

data class BotEyes(
    val left: Eye,
    val right: Eye,
    val split: Double,
) {
    data class Eye(val width: Double, val height: Double, val tilt: Double)

    companion object {
        val neutral = BotEyes(
            left = Eye(width = 0.186, height = 0.412, tilt = 0.0),
            right = Eye(width = 0.186, height = 0.412, tilt = 0.0),
            split = 15.46,
        )
        val sleepy = BotEyes(
            left = Eye(width = 0.2, height = 0.42 * 0.42, tilt = 0.0),
            right = Eye(width = 0.2, height = 0.42 * 0.42, tilt = 0.0),
            split = 16.0,
        )
    }
}

data class BotPose(
    val gazeX: Double = 0.0,
    val gazeY: Double = 0.0,
    val roll: Double = 0.0,
    val lid: Double = 1.0,
    val lift: Double = 0.0,
    val scaleX: Double = 1.0,
    val scaleY: Double = 1.0,
) {
    companion object {
        val rest = BotPose()
        const val shutLid = 0.06

        fun working(at: Double): BotPose {
            val turn = -11.0 + sin(at * 0.48) * 8.0
            val tilt = sin(at * 0.42) * 8.0 + sin(at * 1.1) * 1.6
            val blink = (at % 1.45) > 1.26
            val hop = (at % 0.9) / 0.9
            val squash = when {
                hop < 0.12 -> 1.0 - 0.1 * sin(hop / 0.12 * PI)
                hop > 0.88 -> 1.0 - 0.1 * sin((hop - 0.88) / 0.12 * PI)
                else -> 1.0
            }
            return BotPose(
                gazeX = turn * 0.0025,
                gazeY = -tilt * 0.0025,
                roll = sin(at * 0.75) * 4.2,
                lid = if (blink) shutLid else 1.0,
                lift = 0.07 * sin(hop * PI),
                scaleX = 2.0 - squash,
                scaleY = squash,
            )
        }

        fun sleeping(at: Double): BotPose {
            val breath = sin(at * 2.0 * PI / 4.0)
            return BotPose(
                gazeY = 0.03,
                roll = -5.0 + sin(at * 0.4) * 1.5,
                scaleX = 1.0 - 0.02 * breath,
                scaleY = 1.0 + 0.035 * breath,
            )
        }
    }
}

class BotBlinkSchedule(hash: ULong) {
    val period: Double = 3.0 + (hash % 2001UL).toDouble() / 1000.0
    val phase: Double = ((hash shr 20) % 5000UL).toDouble() / 1000.0

    fun isShut(atSeconds: Double): Boolean {
        val rem = (atSeconds - phase) % period
        val offset = if (rem < 0) rem + period else rem
        return offset + 0.0001 < SHUT
    }

    fun nextEdgeDelayMillis(atSeconds: Double): Long {
        val rem = (atSeconds - phase) % period
        val offset = if (rem < 0) rem + period else rem
        val waitSec = if (offset < SHUT) {
            SHUT - offset
        } else {
            period - offset
        }
        return (waitSec * 1000.0).toLong().coerceAtLeast(16L)
    }

    companion object {
        const val SHUT = 0.18
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
    val eyes = if (state == BotState.sleeping) BotEyes.sleepy else BotEyes.neutral
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
    val shouldAnimate = live && animationsEnabled
    var timeSec by remember { mutableDoubleStateOf(System.currentTimeMillis() / 1000.0) }

    if (shouldAnimate) {
        LaunchedEffect(state, look.hash) {
            if (state == BotState.working || state == BotState.sleeping) {
                while (isActive) {
                    withFrameNanos { nanos ->
                        timeSec = nanos / 1_000_000_000.0
                    }
                    delay(50L)
                }
            } else {
                val schedule = BotBlinkSchedule(look.hash)
                while (isActive) {
                    val now = System.currentTimeMillis() / 1000.0
                    timeSec = now
                    delay(schedule.nextEdgeDelayMillis(now))
                }
            }
        }
    }

    val pose = remember(shouldAnimate, state, timeSec, look.hash) {
        if (!shouldAnimate) {
            BotPose.rest
        } else {
            when (state) {
                BotState.sleeping -> BotPose.sleeping(timeSec)
                BotState.working -> BotPose.working(timeSec)
                BotState.idle -> {
                    val schedule = BotBlinkSchedule(look.hash)
                    if (schedule.isShut(timeSec)) BotPose(lid = BotPose.shutLid) else BotPose.rest
                }
            }
        }
    }

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val s = this.size.minDimension
            withTransform({
                translate(left = 0f, top = (-s * pose.lift).toFloat())
                scale(
                    scaleX = pose.scaleX.toFloat(),
                    scaleY = pose.scaleY.toFloat(),
                    pivot = Offset(s / 2f, s),
                )
                rotate(degrees = pose.roll.toFloat(), pivot = Offset(s / 2f, s / 2f))
            }) {
                val bodyPath = buildBotBodyPath(look.shape, Rect(0f, 0f, s, s))
                drawPath(path = bodyPath, color = look.color)

                val spacing = (s * 0.1 * eyes.split / 15.46).toFloat()
                val leftW = (s * 0.075 * eyes.left.width / 0.186).toFloat()
                val leftH = (s * 0.22 * eyes.left.height / 0.412 * pose.lid).toFloat().coerceAtLeast(1.5f)
                val rightW = (s * 0.075 * eyes.right.width / 0.186).toFloat()
                val rightH = (s * 0.22 * eyes.right.height / 0.412 * pose.lid).toFloat().coerceAtLeast(1.5f)

                val totalW = leftW + spacing + rightW
                val centerX = s / 2f + (s * (0.12 + pose.gazeX)).toFloat()
                val centerY = s / 2f + (s * (-0.08 + pose.gazeY)).toFloat()

                val leftCenterX = centerX - totalW / 2f + leftW / 2f
                val rightCenterX = centerX + totalW / 2f - rightW / 2f

                rotate(degrees = (-18.0 + eyes.left.tilt).toFloat(), pivot = Offset(leftCenterX, centerY)) {
                    drawRoundRect(
                        color = look.eyeColor,
                        topLeft = Offset(leftCenterX - leftW / 2f, centerY - leftH / 2f),
                        size = Size(leftW, leftH),
                        cornerRadius = CornerRadius(leftW / 2f, leftW / 2f),
                    )
                }

                rotate(degrees = (-18.0 + eyes.right.tilt).toFloat(), pivot = Offset(rightCenterX, centerY)) {
                    drawRoundRect(
                        color = look.eyeColor,
                        topLeft = Offset(rightCenterX - rightW / 2f, centerY - rightH / 2f),
                        size = Size(rightW, rightH),
                        cornerRadius = CornerRadius(rightW / 2f, rightW / 2f),
                    )
                }
            }
        }

        if (live && state == BotState.sleeping) {
            for (index in 0 until 2) {
                val rawPhase = (timeSec / 3.0 + index * 0.5) % 1.0
                val phase = if (rawPhase < 0) rawPhase + 1.0 else rawPhase
                val fontSize = (size.value * (0.16 + 0.1 * phase)).sp
                val alpha = sin(phase * PI).toFloat().coerceIn(0f, 1f)
                val offsetX = (size.value * (0.34 + 0.12 * phase)).dp
                val offsetY = (-size.value * (0.22 + 0.32 * phase)).dp
                Text(
                    text = "z",
                    fontSize = fontSize,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .offset(x = offsetX, y = offsetY)
                        .alpha(alpha),
                )
            }
        }
    }
}

private fun buildBotBodyPath(shape: BotShape, rect: Rect): Path {
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
            path.addOval(
                Rect(
                    left = rect.left + rect.width * 0.08f,
                    top = rect.top + rect.height * 0.38f,
                    right = rect.left + rect.width * (0.08f + 0.48f),
                    bottom = rect.top + rect.height * (0.38f + 0.48f),
                ),
            )
            path.addOval(
                Rect(
                    left = rect.left + rect.width * 0.28f,
                    top = rect.top + rect.height * 0.12f,
                    right = rect.left + rect.width * (0.28f + 0.5f),
                    bottom = rect.top + rect.height * (0.12f + 0.64f),
                ),
            )
            path.addOval(
                Rect(
                    left = rect.left + rect.width * 0.55f,
                    top = rect.top + rect.height * 0.35f,
                    right = rect.left + rect.width * (0.55f + 0.38f),
                    bottom = rect.top + rect.height * (0.35f + 0.45f),
                ),
            )
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
