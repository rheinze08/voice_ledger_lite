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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.Switch
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
import com.voiceledger.lite.semantic.AggregationCheckpoint
import com.voiceledger.lite.semantic.RollupSnapshot
import com.voiceledger.lite.semantic.SemanticSearchHit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerMiniApp(
    viewModel: LedgerViewModel,
    onImportSummaryModel: () -> Unit,
    onImportEmbeddingModel: () -> Unit,
) {
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
                            text = "Phone-local notes, rollups, and semantic search",
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
                onRefresh = { viewModel.refreshInsights(false) },
                onRebuild = { viewModel.refreshInsights(true) },
                onOpenNote = viewModel::selectNote,
                onSearchChange = viewModel::updateSearchQuery,
                onSearch = viewModel::runSearch,
                onOpenSearchHit = viewModel::openSearchHit,
            )
            AppTab.SETTINGS -> SettingsScreen(
                state = state,
                paddingValues = innerPadding,
                onSummaryModelPathChange = viewModel::updateSummaryModelPath,
                onEmbeddingModelPathChange = viewModel::updateEmbeddingModelPath,
                onSummaryStartDateChange = viewModel::updateSummaryStartDate,
                onMaxSourcesChange = viewModel::updateMaxSourcesPerRollup,
                onEmbeddingDimensionsChange = viewModel::updateEmbeddingDimensions,
                onSearchLimitChange = viewModel::updateSearchResultLimit,
                onMaxTokensChange = viewModel::updateMaxTokens,
                onTopKChange = viewModel::updateTopK,
                onTemperatureChange = viewModel::updateTemperature,
                onBackgroundProcessingChange = viewModel::updateBackgroundProcessing,
                onImportSummaryModel = onImportSummaryModel,
                onImportEmbeddingModel = onImportEmbeddingModel,
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
            "Notes are stored locally. Rollups and embeddings are rebuilt on-device from the dirty checkpoint forward.",
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
    onRebuild: () -> Unit,
    onOpenNote: (Long?) -> Unit,
    onSearchChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenSearchHit: (SemanticSearchHit) -> Unit,
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
                    Text("Local aggregation", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Daily, weekly, monthly, and yearly rollups are generated on-device from the last dirty checkpoint forward.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onRefresh, enabled = !state.isRefreshingInsights) {
                            if (state.isRefreshingInsights) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(if (state.isRefreshingInsights) "Running" else "Run now")
                        }
                        OutlinedButton(onClick = onRebuild, enabled = !state.isRefreshingInsights) {
                            Text("Rebuild since start")
                        }
                    }
                }
            }
        }
        item {
            StatsRow(state.localStats)
        }
        item {
            SearchCard(
                state = state,
                onSearchChange = onSearchChange,
                onSearch = onSearch,
                onOpenSearchHit = onOpenSearchHit,
            )
        }
        if (state.checkpoints.isNotEmpty()) {
            item {
                Text("Checkpoints", style = MaterialTheme.typography.titleLarge)
            }
            items(state.checkpoints, key = { it.granularity.name }) { checkpoint ->
                CheckpointCard(checkpoint)
            }
        }
        if (state.latestRollups.isNotEmpty()) {
            item {
                Text("Latest rollups", style = MaterialTheme.typography.titleLarge)
            }
            items(state.latestRollups, key = RollupSnapshot::id) { rollup ->
                RollupCard(rollup, onOpenNote)
            }
        }
    }
}

@Composable
private fun SearchCard(
    state: LedgerUiState,
    onSearchChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenSearchHit: (SemanticSearchHit) -> Unit,
) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Semantic search", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search notes and rollups") },
                singleLine = true,
            )
            Button(onClick = onSearch, enabled = !state.isSearching) {
                Text(if (state.isSearching) "Searching" else "Search")
            }
            if (state.searchResults.isEmpty()) {
                Text(
                    "Search results will appear here after the local embedding index has been built.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.searchResults.forEach { hit ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenSearchHit(hit) },
                        shape = RoundedCornerShape(18.dp),
                        tonalElevation = 2.dp,
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(hit.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${hit.kind} | score ${"%.3f".format(hit.score)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(hit.preview, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: LedgerUiState,
    paddingValues: PaddingValues,
    onSummaryModelPathChange: (String) -> Unit,
    onEmbeddingModelPathChange: (String) -> Unit,
    onSummaryStartDateChange: (String) -> Unit,
    onMaxSourcesChange: (String) -> Unit,
    onEmbeddingDimensionsChange: (String) -> Unit,
    onSearchLimitChange: (String) -> Unit,
    onMaxTokensChange: (String) -> Unit,
    onTopKChange: (String) -> Unit,
    onTemperatureChange: (String) -> Unit,
    onBackgroundProcessingChange: (Boolean) -> Unit,
    onImportSummaryModel: () -> Unit,
    onImportEmbeddingModel: () -> Unit,
    onSave: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Local AI settings", style = MaterialTheme.typography.headlineSmall)
        }
        item {
            Text(
                "Import a `.task` summary model for on-device generation and optionally a local text embedding model. If those files are absent, the app falls back to built-in local heuristics.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onImportSummaryModel) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Import summary model")
                }
                OutlinedButton(onClick = onImportEmbeddingModel) {
                    Text("Import embedding model")
                }
            }
        }
        item {
            OutlinedTextField(
                value = state.settings.summaryModelPath,
                onValueChange = onSummaryModelPathChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Summary model path") },
                minLines = 2,
            )
        }
        item {
            OutlinedTextField(
                value = state.settings.embeddingModelPath,
                onValueChange = onEmbeddingModelPathChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Embedding model path") },
                minLines = 2,
            )
        }
        item {
            OutlinedTextField(
                value = state.settings.summaryStartDate,
                onValueChange = onSummaryStartDateChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Summarize since (YYYY-MM-DD)") },
                singleLine = true,
            )
        }
        item {
            OutlinedTextField(
                value = state.settings.maxSourcesPerRollup.toString(),
                onValueChange = onMaxSourcesChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Max sources per rollup") },
                singleLine = true,
            )
        }
        item {
            OutlinedTextField(
                value = state.settings.embeddingDimensions.toString(),
                onValueChange = onEmbeddingDimensionsChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Fallback embedding dimensions") },
                singleLine = true,
            )
        }
        item {
            OutlinedTextField(
                value = state.settings.searchResultLimit.toString(),
                onValueChange = onSearchLimitChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search result limit") },
                singleLine = true,
            )
        }
        item {
            OutlinedTextField(
                value = state.settings.maxTokens.toString(),
                onValueChange = onMaxTokensChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Summary max tokens") },
                singleLine = true,
            )
        }
        item {
            OutlinedTextField(
                value = state.settings.topK.toString(),
                onValueChange = onTopKChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Summary top K") },
                singleLine = true,
            )
        }
        item {
            OutlinedTextField(
                value = state.settings.temperature.toString(),
                onValueChange = onTemperatureChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Summary temperature") },
                singleLine = true,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Background processing", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Runs daily when charging so rollups and vector search can catch up off-hours.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.settings.backgroundProcessingEnabled,
                    onCheckedChange = onBackgroundProcessingChange,
                )
            }
        }
        item {
            Button(onClick = onSave) {
                Text("Save settings")
            }
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

@Composable
private fun CheckpointCard(checkpoint: AggregationCheckpoint) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(checkpoint.granularity.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
            Text(
                "Last completed: ${checkpoint.lastCompletedEndEpochMs?.let(::formatTimestamp) ?: "Not yet"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Dirty from: ${checkpoint.dirtyFromEpochMs?.let(::formatTimestamp) ?: "Clean"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            checkpoint.lastError?.takeIf(String::isNotBlank)?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RollupCard(rollup: RollupSnapshot, onOpenNote: (Long?) -> Unit) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "${rollup.granularity.name.lowercase().replaceFirstChar { it.uppercase() }} | ${formatTimestamp(rollup.periodStartEpochMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(rollup.title, style = MaterialTheme.typography.titleLarge)
            Text(rollup.overview, style = MaterialTheme.typography.bodyLarge)
            Text("Highlights", style = MaterialTheme.typography.titleMedium)
            rollup.highlights.forEach { highlight ->
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
                rollup.themes.forEach { theme ->
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
