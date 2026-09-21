package dev.simone.watranscriber.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onOpenStorageSettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("Voice notes") },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Rescan") },
                            onClick = {
                                menuOpen = false
                                viewModel.refresh()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Notification access") },
                            onClick = {
                                menuOpen = false
                                onOpenNotificationSettings()
                            },
                        )
                        if (state.model is ModelState.Ready) {
                            DropdownMenuItem(
                                text = { Text("Delete the model") },
                                onClick = {
                                    menuOpen = false
                                    viewModel.deleteModel()
                                    scope.launch {
                                        snackbarHost.showSnackbar(
                                            "Model deleted. The next tap downloads it again."
                                        )
                                    }
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Wipe stored data") },
                            onClick = {
                                menuOpen = false
                                viewModel.wipeStoredData()
                                scope.launch {
                                    snackbarHost.showSnackbar(
                                        "Names and transcriptions deleted. Audio files are untouched."
                                    )
                                }
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!state.hasStorageAccess) {
                item {
                    SetupCard(
                        title = "Allow access to all files",
                        body = "WhatsApp keeps voice notes in a shared folder. " +
                            "The app needs the all-files permission to read them.",
                        action = "Open settings",
                        onAction = onOpenStorageSettings,
                    )
                }
            }
            if (!state.hasNotificationAccess) {
                item {
                    SetupCard(
                        title = "Allow notification access",
                        body = "The audio file does not name the chat. " +
                            "With notification access the app reads the chat name and the " +
                            "sender from new WhatsApp notifications.",
                        action = "Open settings",
                        onAction = onOpenNotificationSettings,
                    )
                }
            }
            when (val model = state.model) {
                is ModelState.Missing -> item {
                    SetupCard(
                        title = "Download the whisper model",
                        body = "large-v3-turbo, about 550 MB. This happens once.",
                        action = "Download",
                        onAction = viewModel::downloadModel,
                    )
                }
                is ModelState.Downloading -> item { DownloadCard(model.progress) }
                is ModelState.Failed -> item {
                    SetupCard(
                        title = "Download failed",
                        body = model.message,
                        action = "Try again",
                        onAction = viewModel::downloadModel,
                    )
                }
                is ModelState.Ready -> Unit
            }

            state.error?.let { message ->
                item {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            items(state.items, key = { it.path }) { item ->
                AudioCard(
                    item = item,
                    isExpanded = state.expandedPath == item.path,
                    isTranscribing = item.path in state.transcribingPaths,
                    onClick = {
                        val transcript = item.transcript
                        if (state.expandedPath == item.path && transcript != null) {
                            clipboard.setText(AnnotatedString(transcript))
                            scope.launch { snackbarHost.showSnackbar("Transcription copied") }
                        } else {
                            viewModel.onCardClick(item)
                        }
                    },
                    onHide = viewModel::collapse,
                )
            }

            if (state.isScanning) {
                item { Spinner() }
            } else if (state.items.size < state.totalFound) {
                item {
                    OutlinedButton(
                        onClick = viewModel::loadMore,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Load more")
                    }
                }
            } else if (state.hasStorageAccess && state.totalFound == 0) {
                item {
                    Text(
                        "No WhatsApp audio found on this phone.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioCard(
    item: AudioItem,
    isExpanded: Boolean,
    isTranscribing: Boolean,
    onClick: () -> Unit,
    onHide: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isExpanded) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.chat ?: "Unknown chat",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatMessageTime(item.timeMillis),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = listOfNotNull(
                    item.sender,
                    item.durationMillis?.let(::formatDuration),
                    if (item.chat == null) item.name else null,
                    if (item.transcript != null && !isExpanded) "transcribed" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (isExpanded) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                when {
                    isTranscribing -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(10.dp))
                        Text("Transcribing…", style = MaterialTheme.typography.bodyMedium)
                    }
                    item.transcript.isNullOrBlank() -> Text(
                        "No speech found.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> Column {
                        Text(item.transcript, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Tap the card to copy",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = onHide) { Text("Hide") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupCard(
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun DownloadCard(progress: Float) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Downloading the model", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            if (progress >= 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text("${(progress * 100).toInt()} %", style = MaterialTheme.typography.labelMedium)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Spinner() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
    }
}
