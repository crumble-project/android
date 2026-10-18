package dev.crumble.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.crumble.android.model.AppModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(model: AppModel) {
    var providerMenuExpanded by remember { mutableStateOf(false) }
    val configured = model.providers.filter { it.configured }.map { it.provider }

    LaunchedEffect(Unit) {
        model.loadProviders()
    }

    AlertDialog(
        onDismissRequest = {
            model.errorText = ""
            model.showingSettings = false
        },
        title = { Text("Settings") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SheetErrorBanner(model.errorText)

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Server",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutlinedTextField(
                        value = model.serverURL,
                        onValueChange = { model.serverURL = it },
                        label = { Text("Server URL") },
                        placeholder = { Text("http://10.0.2.2:7337") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = model.token,
                        onValueChange = { model.token = it },
                        label = { Text("Server token") },
                        placeholder = { Text("Required") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = { model.saveSettings() },
                            enabled = !model.savingConnection,
                        ) {
                            Text("Save Connection")
                        }
                        if (model.savingConnection) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                text = "Connecting…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        text = "The token stays encrypted in the Android Keystore. On the Android emulator, use http://10.0.2.2:7337 to reach a server running on your computer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Provider API Keys",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (model.providers.isEmpty()) {
                        OutlinedTextField(
                            value = model.provider,
                            onValueChange = { model.provider = it },
                            label = { Text("Provider") },
                            placeholder = { Text("melious") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        ExposedDropdownMenuBox(
                            expanded = providerMenuExpanded,
                            onExpandedChange = { providerMenuExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = model.provider,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Provider") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenuExpanded) },
                                modifier = Modifier
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                    .fillMaxWidth(),
                            )
                            ExposedDropdownMenu(
                                expanded = providerMenuExpanded,
                                onDismissRequest = { providerMenuExpanded = false },
                            ) {
                                for (item in model.providers) {
                                    DropdownMenuItem(
                                        text = { Text(item.provider) },
                                        onClick = {
                                            model.provider = item.provider
                                            providerMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = model.providerKey,
                        onValueChange = { model.providerKey = it },
                        label = { Text("API key") },
                        placeholder = { Text("Paste a key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val isMutatingKey = model.pendingMutations.contains("provider:${model.provider}")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { model.saveProviderKey() },
                            enabled = !isMutatingKey,
                        ) {
                            Text("Save Key")
                        }
                        OutlinedButton(
                            onClick = { model.removeProviderKey() },
                            enabled = !isMutatingKey,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text("Remove Key")
                        }
                        if (isMutatingKey) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }
                    val prefix = if (configured.isEmpty()) "" else "Configured: ${configured.joinToString(", ")}. "
                    Text(
                        text = "${prefix}Keys are stored encrypted on your server.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    model.errorText = ""
                    model.showingSettings = false
                },
            ) {
                Text("Done")
            }
        },
    )
}
