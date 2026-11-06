package dev.crumble.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.crumble.android.model.AppModel
import dev.crumble.android.model.Route
import dev.crumble.android.model.WORKSPACE_PAGES

@Composable
private fun AccountCircle(model: AppModel, size: Dp) {
    Box(
        modifier = Modifier.size(size).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = model.email.trim().take(1).uppercase().ifEmpty { "?" },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** The account circle at the top right of Home, as in Messages. */
@Composable
fun AccountButton(model: AppModel) {
    Box(
        modifier = Modifier
            .padding(start = 4.dp, end = 12.dp)
            .size(48.dp)
            .clickable(role = Role.Button) { model.navigate(Route.Account) }
            .semantics { contentDescription = "Account" },
        contentAlignment = Alignment.Center,
    ) {
        AccountCircle(model, 36.dp)
    }
}

/** Everything that isn't a conversation: the workspace pages, then Settings. */
@Composable
fun AccountPanel(model: AppModel) {
    val pages = WORKSPACE_PAGES
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        GroupCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AccountCircle(model, 56.dp)
                Column {
                    Text(
                        text = model.email.ifEmpty { "Not signed in" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    ConnectionStatus(model)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
            pages.forEachIndexed { index, page ->
                GroupedRow(
                    title = page.title,
                    shape = groupedShape(index, pages.size),
                    onClick = { model.navigate(page) },
                    leading = { IconTile(routeIcon(page), Tone.entries[index % Tone.entries.size]) },
                )
            }
        }
        GroupedRow(
            title = Route.Settings.title,
            shape = groupedShape(0, 1),
            onClick = { model.navigate(Route.Settings) },
            leading = { IconTile(routeIcon(Route.Settings), Tone.Lavender) },
        )
    }
}

class ChatMenuScope(private val close: () -> Unit) {
    val entries = mutableListOf<Pair<String, () -> Unit>>()

    fun item(title: String, action: () -> Unit) {
        entries += title to {
            close()
            action()
        }
    }
}

/** The overflow menu in a chat's top bar: what belongs to this chat. */
@Composable
fun ChatMenu(build: ChatMenuScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More options")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ChatMenuScope { open = false }.apply(build).entries.forEach { (title, action) ->
                DropdownMenuItem(text = { Text(title) }, onClick = action)
            }
        }
    }
}
