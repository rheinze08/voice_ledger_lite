package com.voiceledger.lite.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
)

@Entity(tableName = "insights")
data class InsightEntity(
    @PrimaryKey val kind: String,
    @ColumnInfo(name = "generated_at_epoch_ms") val generatedAtEpochMs: Long,
    val model: String,
    @ColumnInfo(name = "note_count") val noteCount: Int,
    @ColumnInfo(name = "window_days") val windowDays: Int,
    val overview: String,
    @ColumnInfo(name = "highlights_json") val highlightsJson: String,
    @ColumnInfo(name = "themes_json") val themesJson: String,
    @ColumnInfo(name = "note_ids_json") val noteIdsJson: String,
)

data class OllamaSettings(
    val baseUrl: String = "http://10.0.2.2:11434",
    val model: String = "gemma4:e2b",
    val windowDays: Int = 30,
    val noteLimit: Int = 18,
    val timeoutMs: Int = 60_000,
) {
    fun normalized(): OllamaSettings {
        return copy(
            baseUrl = baseUrl.trim().trimEnd('/').ifBlank { "http://10.0.2.2:11434" },
            model = model.trim().ifBlank { "gemma4:e2b" },
            windowDays = windowDays.coerceIn(7, 365),
            noteLimit = noteLimit.coerceIn(4, 40),
            timeoutMs = timeoutMs.coerceIn(10_000, 180_000),
        )
    }
}

data class LocalStats(
    val totalNotes: Int = 0,
    val notesThisWeek: Int = 0,
    val notesThisMonth: Int = 0,
)
