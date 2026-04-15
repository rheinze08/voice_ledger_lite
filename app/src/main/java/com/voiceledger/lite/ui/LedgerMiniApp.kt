package com.voiceledger.lite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voiceledger.lite.data.LocalStats
import com.voiceledger.lite.data.NoteEntity
import com.voiceledger.lite.ollama.InsightSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LedgerMiniApp(viewModel: LedgerViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    state.infoMessage?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearInfoMessage()
        }
    }
    state.errorMessage?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearErrorMessage()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Voice Ledger Lite")
                        Text(
                            text = "Local notes with opt-in Gemma highlights",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = state.selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        icon = {
                            Icon(
                                imageVector = when (tab) {
                                    AppTab.NOTES -> Icons.Filled.ListAlt
                                    AppTab.COMPOSE -> Icons.Filled.EditNote
                                    AppTab.INSIGHTS -> Icons.Filled.Insights
                                    AppTab.SETTINGS -> Icons.Filled.Settings
                                },
                                contentDescription = tab.label,
                            )
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when (state.selectedTab) {
            AppTab.NOTES -> NotesScreen(
                state = state,
                paddingValues = innerPadding,
                onSelect = viewModel::selectNote,
                onEdit = viewModel::loadNoteIntoComposer,
                onDelete = viewModel::deleteNote,
            )
            AppTab.COMPOSE -> ComposeScreen(
                state = state,
                paddingValues = innerPadding,
                onTitleChange = viewModel::updateComposeTitle,
                onBodyChange = viewModel::updateComposeBody,
                onSave = viewModel::saveDraft,
                onClear = viewModel::clearComposer,
            )
            AppTab.INSIGHTS -> InsightsScreen(
                state = state,
                paddingValues = innerPadding,
                onRefresh = viewModel::refreshInsights,
                onOpenNote = viewModel::selectNote,
            )
            AppTab.SETTINGS -> SettingsScreen(
                state = state,
                paddingValues = innerPadding,
                onBaseUrlChange = viewModel::updateBaseUrl,
                onModelChange = viewModel::updateModel,
                onWindowDaysChange = viewModel::updateWindowDays,
                onNoteLimitChange = viewModel::updateNoteLimit,
                onTimeoutMsChange = viewModel::updateTimeoutMs,
                onSave = viewModel::saveSettings,
            )
        }
    }
}

private val AppTab.label: String
    get() = when (this) {
        AppTab.NOTES -> "Notes"
        AppTab.COMPOSE -> "Compose"
        AppTab.INSIGHTS -> "Insights"
        AppTab.SETTINGS -> "Settings"
    }

@Composable
private fun NotesScreen(
    state: LedgerUiState,
    paddingValues: PaddingValues,
    onSelect: (Long?) -> Unit,
    onEdit: (NoteEntity) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val selectedNote = state.notes.firstOrNull { it.id == state.selectedNoteId }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StatsRow(state.localStats)
        }
        if (selectedNote != null) {
            item {
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(selectedNote.title, style = MaterialTheme.typography.titleLarge)
                        Text(
                            formatTimestamp(selectedNote.createdAtEpochMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(selectedNote.body, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { onEdit(selectedNote) }) {
                                Text("Edit")
                            }
                            OutlinedButton(onClick = { onDelete(selectedNote.id) }) {
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        }
        items(state.notes, key = NoteEntity::id) { note ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(note.id) },
                colors = CardDefaults.cardColors(
                    containerColor = if (state.selectedNoteId == note.id) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(note.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        formatTimestamp(note.createdAtEpochMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = note.body,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposeScreen(
    state: LedgerUiState,
    paddingValues: PaddingValues,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            if (state.editingNoteId == null) "Capture a note" else "Edit note",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            "Everything is written to local SQLite first. Gemma only runs when you request insights.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.composeTitle,
            onValueChange = onTitleChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Title") },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.composeBody,
            onValueChange = onBodyChange,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            label = { Text("Body") },
            minLines = 10,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onSave) {
                Text(if (state.editingNoteId == null) "Save note" else "Update note")
            }
            OutlinedButton(onClick = onClear) {
                Text(if (state.editingNoteId == null) "Clear" else "Cancel edit")
            }
        }
    }
}

@Composable
private fun InsightsScreen(
    state: LedgerUiState,
    paddingValues: PaddingValues,
    onRefresh: () -> Unit,
    onOpenNote: (Long?) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ElevatedCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Semantic rollup", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Gemma reads a capped window of recent notes and returns a concise overview, highlights, and theme buckets.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Window: ${state.settings.windowDays} days | Limit: ${state.settings.noteLimit} notes | Model: ${state.settings.model}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = onRefresh, enabled = !state.isRefreshingInsights) {
                        if (state.isRefreshingInsights) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(if (state.isRefreshingInsights) "Running Gemma" else "Refresh with Gemma")
                    }
                }
            }
        }
        item {
            StatsRow(state.localStats)
        }
        item {
            val insight = state.latestInsight
            if (insight == null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 1.dp,
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("No cached insight yet.", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Run a refresh after saving a few notes. The last response stays on-device for reuse.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                InsightCard(insight = insight, onOpenNote = onOpenNote)
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: LedgerUiState,
    paddingValues: PaddingValues,
    onBaseUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onWindowDaysChange: (String) -> Unit,
    onNoteLimitChange: (String) -> Unit,
    onTimeoutMsChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Ollama settings", style = MaterialTheme.typography.headlineSmall)
        Text(
            "The app stays local-first. Gemma only runs when you tap the rollup button.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.settings.baseUrl,
            onValueChange = onBaseUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Base URL") },
            supportingText = { Text("Emulator default: http://10.0.2.2:11434") },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.settings.model,
            onValueChange = onModelChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Model") },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.settings.windowDays.toString(),
            onValueChange = onWindowDaysChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Window days") },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.settings.noteLimit.toString(),
            onValueChange = onNoteLimitChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Note limit") },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.settings.timeoutMs.toString(),
            onValueChange = onTimeoutMsChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Timeout ms") },
            singleLine = true,
        )
        Button(onClick = onSave) {
            Text("Save settings")
        }
    }
}

@Composable
private fun StatsRow(stats: LocalStats) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatsTile("Total", stats.totalNotes.toString())
        StatsTile("7 days", stats.notesThisWeek.toString())
        StatsTile("30 days", stats.notesThisMonth.toString())
    }
}

@Composable
private fun StatsTile(label: String, value: String) {
    Surface(
        modifier = Modifier.width(120.dp),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InsightCard(insight: InsightSnapshot, onOpenNote: (Long?) -> Unit) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "${formatTimestamp(insight.generatedAtEpochMs)} | ${insight.model} | ${insight.noteCount} notes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(insight.overview, style = MaterialTheme.typography.bodyLarge)
            Text("Highlights", style = MaterialTheme.typography.titleMedium)
            insight.highlights.forEach { highlight ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .padding(top = 7.dp)
                            .size(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.secondary),
                    )
                    Text(highlight, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text("Themes", style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                insight.themes.forEach { theme ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        tonalElevation = 2.dp,
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(theme.label, style = MaterialTheme.typography.titleSmall)
                            Text(theme.summary, style = MaterialTheme.typography.bodyMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                theme.noteIds.forEach { noteId ->
                                    AssistChip(
                                        onClick = { onOpenNote(noteId) },
                                        label = { Text("Note $noteId") },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(epochMs: Long): String {
    val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a").withZone(ZoneId.systemDefault())
    return formatter.format(Instant.ofEpochMilli(epochMs))
}
