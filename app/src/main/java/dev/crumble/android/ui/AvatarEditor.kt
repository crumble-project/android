package dev.crumble.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.crumble.android.R
import dev.crumble.android.generated.BotAvatarDefinition
import re.leob.openbotavatar.kt.AvatarCatalog
import re.leob.openbotavatar.kt.ValidationResult
import re.leob.openbotavatar.kt.validateAvatar

private const val GALLERY_COLUMNS = 4

/** Geometry imports use the library validator; colors are separate from the portable definition. */
internal fun importAvatar(json: String, current: BotAvatarDefinition): Result<BotAvatarDefinition> =
    when (val result = validateAvatar(json)) {
        is ValidationResult.Valid -> Result.success(result.value.withColors(current.color, current.eyeColor))
        is ValidationResult.Invalid -> Result.failure(IllegalArgumentException(result.errors.joinToString("\n")))
    }

internal fun avatarGenerationPrompt(template: String, description: String) =
    "$template\n\n## User description\n\n${description.trim()}\n"

@Composable
fun AvatarEditor(value: BotAvatarDefinition, onChange: (BotAvatarDefinition) -> Unit, enabled: Boolean = true) {
    var tab by remember { mutableIntStateOf(0) }
    var colorDraft by remember { mutableStateOf(value.color) }
    var eyeDraft by remember { mutableStateOf(value.eyeColor) }
    var json by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val resources = LocalContext.current.resources

    fun colors(color: String, eyeColor: String) {
        if (isAvatarColor(color) && isAvatarColor(eyeColor)) {
            onChange(value.copy(color = color, eyeColor = eyeColor))
            error = ""
        } else {
            error = "Colors must be #rgb or #rrggbb."
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = colorDraft,
                onValueChange = { colorDraft = it.take(7); colors(colorDraft, eyeDraft) },
                enabled = enabled,
                label = { Text("Body color") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = eyeDraft,
                onValueChange = { eyeDraft = it.take(7); colors(colorDraft, eyeDraft) },
                enabled = enabled,
                label = { Text("Eye color") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            listOf("Gallery", "Import", "Generate").forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
            }
        }
        when (tab) {
            0 -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in AvatarCatalog.examples.chunked(GALLERY_COLUMNS)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (shape in row) {
                            val picked = shape.id == value.definition.id
                            Surface(
                                modifier = Modifier.weight(1f).clickable(enabled = enabled) {
                                    onChange(shape.withColors(value.color, value.eyeColor))
                                    error = ""
                                    status = ""
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = sheetColor,
                                border = if (picked) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                            ) {
                                Column(
                                    modifier = Modifier.padding(6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    BotAvatarView(shape.withColors(value.color, value.eyeColor), size = 44.dp)
                                    Text(
                                        shape.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                        repeat(GALLERY_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            1 -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = json,
                    onValueChange = { json = it.take(16_000) },
                    enabled = enabled,
                    label = { Text("Definition JSON") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(enabled = enabled, onClick = {
                    importAvatar(json, value).fold(
                        onSuccess = {
                            onChange(it)
                            error = ""
                            status = "Avatar imported. Save the bot to keep it."
                        },
                        onFailure = {
                            error = it.message ?: "Invalid avatar JSON"
                            status = ""
                        },
                    )
                }) { Text("Validate and Import") }
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(4_000) },
                    enabled = enabled,
                    label = { Text("Describe your avatar") },
                    placeholder = { Text("A gardening companion shaped like a watering can") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Copy the Open Bot Avatar prompt with your description. Give it to any bot or model, then paste its definition JSON into Import. A bot can also suggest its own avatar in chat. Nothing is generated or sent from this page.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    enabled = enabled && description.isNotBlank(),
                    onClick = {
                        val template = resources.openRawResource(R.raw.generate_avatar_prompt).bufferedReader().use { it.readText() }
                        clipboard.setText(AnnotatedString(avatarGenerationPrompt(template, description)))
                        status = "Prompt copied. Give it to any bot or model, then paste its JSON into Import."
                        error = ""
                    },
                ) { Text("Copy Generation Prompt") }
            }
        }
        if (error.isNotEmpty()) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
