package com.voiceledger.lite.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
)

enum class RollupGranularity {
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY,
}

@Entity(tableName = "rollups")
data class RollupEntity(
    @PrimaryKey val id: String,
    val granularity: String,
    @ColumnInfo(name = "period_start_epoch_ms") val periodStartEpochMs: Long,
    @ColumnInfo(name = "period_end_epoch_ms") val periodEndEpochMs: Long,
    @ColumnInfo(name = "generated_at_epoch_ms") val generatedAtEpochMs: Long,
    @ColumnInfo(name = "model_label") val modelLabel: String,
    @ColumnInfo(name = "source_count") val sourceCount: Int,
    val title: String,
    val overview: String,
    @ColumnInfo(name = "highlights_json") val highlightsJson: String,
    @ColumnInfo(name = "themes_json") val themesJson: String,
    @ColumnInfo(name = "note_ids_json") val noteIdsJson: String,
)

@Entity(tableName = "semantic_entries")
data class SemanticEntryEntity(
    @PrimaryKey val entryId: String,
    val kind: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    val title: String,
    val body: String,
    @ColumnInfo(name = "note_id") val noteId: Long?,
    @ColumnInfo(name = "rollup_id") val rollupId: String?,
    val granularity: String?,
    @ColumnInfo(name = "embedding_json") val embeddingJson: String,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
)

@Entity(tableName = "aggregation_checkpoints")
data class AggregationCheckpointEntity(
    @PrimaryKey val granularity: String,
    @ColumnInfo(name = "last_completed_end_epoch_ms") val lastCompletedEndEpochMs: Long?,
    @ColumnInfo(name = "dirty_from_epoch_ms") val dirtyFromEpochMs: Long?,
    @ColumnInfo(name = "last_run_started_epoch_ms") val lastRunStartedEpochMs: Long?,
    @ColumnInfo(name = "last_run_finished_epoch_ms") val lastRunFinishedEpochMs: Long?,
    @ColumnInfo(name = "last_error") val lastError: String?,
)

data class LocalAiSettings(
    val summaryModelPath: String = "",
    val embeddingModelPath: String = "",
    val summaryStartDate: String = "",
    val maxSourcesPerRollup: Int = 18,
    val embeddingDimensions: Int = 192,
    val searchResultLimit: Int = 8,
    val maxTokens: Int = 1024,
    val topK: Int = 32,
    val temperature: Float = 0.2f,
    val backgroundProcessingEnabled: Boolean = true,
) {
    fun normalized(): LocalAiSettings {
        val normalizedStartDate = summaryStartDate.trim().takeIf {
            runCatching { LocalDate.parse(it) }.isSuccess
        }.orEmpty()
        return copy(
            summaryModelPath = summaryModelPath.trim(),
            embeddingModelPath = embeddingModelPath.trim(),
            summaryStartDate = normalizedStartDate,
            maxSourcesPerRollup = maxSourcesPerRollup.coerceIn(4, 64),
            embeddingDimensions = embeddingDimensions.coerceIn(32, 768),
            searchResultLimit = searchResultLimit.coerceIn(3, 20),
            maxTokens = maxTokens.coerceIn(256, 4096),
            topK = topK.coerceIn(1, 64),
            temperature = temperature.coerceIn(0f, 1f),
        )
    }
}

data class LocalStats(
    val totalNotes: Int = 0,
    val notesThisWeek: Int = 0,
    val notesThisMonth: Int = 0,
)
