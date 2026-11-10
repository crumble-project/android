package dev.crumble.android.ui

import android.content.ClipData
import android.os.PersistableBundle
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import dev.crumble.android.model.ChoiceOption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChoiceField(
    label: String,
    options: List<ChoiceOption>,
    value: String,
    enabled: Boolean = true,
    isError: Boolean = false,
    onChoose: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it && enabled }) {
        OutlinedTextField(
            value = options.firstOrNull { it.value == value }?.label.orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            isError = isError,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in options) {
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onChoose(option.value)
                    },
                )
            }
        }
    }
}

// ClipDescription.EXTRA_IS_SENSITIVE as a literal: it arrived in API 33, and older versions read the same key.
private const val CLIP_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

/** Keeps the value out of clipboard previews and sync. */
private fun sensitiveClip(label: String, value: String): ClipEntry {
    val clip = ClipData.newPlainText(label, value)
    clip.description.extras = PersistableBundle().apply { putBoolean(CLIP_IS_SENSITIVE, true) }
    return ClipEntry(clip)
}

@Composable
internal fun CopyField(label: String, value: String, sensitive: Boolean = false) {
    val clipboard = LocalClipboardManager.current
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        singleLine = true,
        label = { Text(label) },
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        trailingIcon = {
            IconButton(
                onClick = {
                    if (sensitive) clipboard.setClip(sensitiveClip(label, value))
                    else clipboard.setText(AnnotatedString(value))
                },
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
