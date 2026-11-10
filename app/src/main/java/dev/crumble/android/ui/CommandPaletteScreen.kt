package dev.crumble.android.ui

import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.PaletteAction
import dev.crumble.android.model.PaletteItem
import dev.crumble.android.model.ThemeChoice
import dev.crumble.android.model.paletteSections
import dev.crumble.android.model.searchPalette

private const val PLACEHOLDER = "Search chats, pages, and settings"

@Composable
fun CommandPaletteDialog(model: AppModel) {
    var query by rememberSaveable { mutableStateOf("") }
    val sections = remember(query, model.agents, model.groupChats, model.theme) {
        val all = paletteSections(model.agents, model.groupChats, model.theme)
        searchPalette(all, query)
    }
    val focus = remember { FocusRequester() }

    Dialog(
        onDismissRequest = { model.showingPalette = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(color = pageColor, modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.systemBarsPadding().imePadding()) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(PLACEHOLDER) },
                    singleLine = true,
                    shape = CircleShape,
                    leadingIcon = {
                        IconButton(onClick = { model.showingPalette = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                        }
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = { sections.firstOrNull()?.items?.firstOrNull()?.let { model.runPalette(it.action) } },
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = sheetColor,
                        unfocusedContainerColor = sheetColor,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .focusRequester(focus),
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
                if (sections.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No results",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
                    ) {
                        sections.forEachIndexed { sectionIndex, section ->
                            item(key = section.heading) {
                                Box(modifier = Modifier.padding(top = 8.dp)) { GroupLabel(section.heading) }
                            }
                            itemsIndexed(section.items) { index, item ->
                                PaletteRow(model, item, groupedShape(index, section.items.size), Tone.entries[sectionIndex % Tone.entries.size])
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteRow(model: AppModel, item: PaletteItem, shape: Shape, tone: Tone) {
    val bot = (item.action as? PaletteAction.OpenBot)?.let { action -> model.agents.firstOrNull { it.id == action.id } }
    GroupedRow(
        title = item.title,
        subtitle = item.note,
        shape = shape,
        onClick = { model.runPalette(item.action) },
        leading = {
            if (bot != null) BotAvatarView(agent = bot, size = 44.dp) else IconTile(paletteIcon(item.action), tone)
        },
    )
}

private fun paletteIcon(action: PaletteAction): ImageVector = when (action) {
    is PaletteAction.OpenBot, is PaletteAction.OpenGroup -> Icons.Default.Group
    PaletteAction.NewBot, PaletteAction.NewGroup -> Icons.Default.Add
    is PaletteAction.OpenPage -> routeIcon(action.route)
    is PaletteAction.SetTheme -> when (action.theme) {
        ThemeChoice.System -> Icons.Default.PhoneAndroid
        ThemeChoice.Light -> Icons.Default.LightMode
        ThemeChoice.Dark -> Icons.Default.DarkMode
    }
}
