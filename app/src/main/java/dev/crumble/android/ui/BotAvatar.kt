package dev.crumble.android.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import dev.crumble.android.generated.BotAvatarDefinition
import dev.crumble.android.generated.BotAvatarDefinitionDefinition
import dev.crumble.android.generated.BotAvatarDefinitionDefinitionBody
import dev.crumble.android.generated.BotAvatarDefinitionDefinitionEyes
import dev.crumble.android.model.Agent
import re.leob.openbotavatar.kt.AVATAR_CONTRACT_VERSION
import re.leob.openbotavatar.kt.AvatarCatalog
import re.leob.openbotavatar.kt.AvatarDefinition
import re.leob.openbotavatar.kt.AvatarEyes
import re.leob.openbotavatar.kt.AvatarMood
import re.leob.openbotavatar.kt.DEFAULT_COLOR
import re.leob.openbotavatar.kt.DEFAULT_EYE_COLOR
import re.leob.openbotavatar.kt.compose.BotAvatar

enum class BotState { idle, working, sleeping }

fun BotAvatarDefinition.geometry() = AvatarDefinition(
    id = definition.id,
    name = definition.name,
    path = definition.body.path,
    eyes = definition.eyes.run { AvatarEyes(x, y, spacing, scale) },
)

fun AvatarDefinition.withColors(color: String, eyeColor: String) = BotAvatarDefinition(
    definition = BotAvatarDefinitionDefinition(
        version = AVATAR_CONTRACT_VERSION.toDouble(),
        id = id,
        name = name,
        body = BotAvatarDefinitionDefinitionBody(path),
        eyes = eyes.run { BotAvatarDefinitionDefinitionEyes(x, y, spacing, scale) },
    ),
    color = color,
    eyeColor = eyeColor,
)

/** The look for a bot the server hasn't given one yet, such as a new or unconfirmed bot. */
fun placeholderAvatar(seed: String): BotAvatarDefinition {
    val shapes = AvatarCatalog.examples
    return shapes[Math.floorMod(seed.hashCode(), shapes.size)].withColors(DEFAULT_COLOR, DEFAULT_EYE_COLOR)
}

private val HEX_COLOR = Regex("#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})")

fun isAvatarColor(value: String) = HEX_COLOR.matches(value)

internal fun avatarColor(value: String): Color {
    val digits = value.removePrefix("#")
    val full = if (digits.length == 3) digits.flatMap { listOf(it, it) }.joinToString("") else digits
    return Color(0xFF000000 or full.toLong(16))
}

@Composable
fun BotAvatarView(
    agent: Agent,
    size: Dp,
    state: BotState = BotState.idle,
    live: Boolean = false,
    modifier: Modifier = Modifier,
) {
    BotAvatarView(agent.avatar ?: placeholderAvatar(agent.id), size, state, live, modifier)
}

@Composable
fun BotAvatarView(
    avatar: BotAvatarDefinition,
    size: Dp,
    state: BotState = BotState.idle,
    live: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val geometry = remember(avatar.definition) { avatar.geometry() }
    BotAvatar(
        definition = geometry,
        modifier = modifier.size(size).alpha(if (state == BotState.sleeping) 0.45f else 1f),
        color = avatarColor(avatar.color),
        eyeColor = avatarColor(avatar.eyeColor),
        mood = if (state == BotState.working) AvatarMood.THINKING else AvatarMood.IDLE,
        followPointer = live,
        still = !live || state == BotState.sleeping,
        label = "",
    )
}
