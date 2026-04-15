package com.voiceledger.lite.data

import com.voiceledger.lite.ollama.InsightSnapshot
import com.voiceledger.lite.ollama.JournalInsight
import com.voiceledger.lite.ollama.ThemeBucket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LedgerRepository(
    private val noteDao: NoteDao,
    private val insightDao: InsightDao,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun observeNotes(): Flow<List<NoteEntity>> = noteDao.observeAll()

    fun observeInsight(kind: String): Flow<InsightSnapshot?> {
        return insightDao.observe(kind).map { entity ->
            entity?.let {
                InsightSnapshot(
                    kind = it.kind,
                    generatedAtEpochMs = it.generatedAtEpochMs,
                    model = it.model,
                    noteCount = it.noteCount,
                    windowDays = it.windowDays,
                    overview = it.overview,
                    highlights = json.decodeFromString<List<String>>(it.highlightsJson),
                    themes = json.decodeFromString<List<ThemeBucket>>(it.themesJson),
                    noteIds = json.decodeFromString<List<Long>>(it.noteIdsJson),
                )
            }
        }
    }

    suspend fun saveNote(noteId: Long?, title: String, body: String): Long {
        val now = System.currentTimeMillis()
        val cleanedTitle = title.trim()
        val cleanedBody = body.trim()

        return if (noteId == null) {
            noteDao.insert(
                NoteEntity(
                    title = cleanedTitle,
                    body = cleanedBody,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                ),
            )
        } else {
            val existing = noteDao.getById(noteId)
            val note = if (existing == null) {
                NoteEntity(
                    id = noteId,
                    title = cleanedTitle,
                    body = cleanedBody,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                )
            } else {
                existing.copy(
                    title = cleanedTitle,
                    body = cleanedBody,
                    updatedAtEpochMs = now,
                )
            }
            if (existing == null) {
                noteDao.insert(note)
            } else {
                noteDao.update(note)
            }
            note.id
        }
    }

    suspend fun deleteNote(noteId: Long) {
        noteDao.deleteById(noteId)
    }

    suspend fun recentNotes(windowDays: Int, limit: Int): List<NoteEntity> {
        val cutoffMs = System.currentTimeMillis() - windowDays.toLong() * 24L * 60L * 60L * 1000L
        return noteDao.recentSince(cutoffMs, limit)
    }

    suspend fun saveInsight(
        kind: String,
        settings: OllamaSettings,
        notes: List<NoteEntity>,
        insight: JournalInsight,
    ) {
        insightDao.replace(
            InsightEntity(
                kind = kind,
                generatedAtEpochMs = System.currentTimeMillis(),
                model = settings.model,
                noteCount = notes.size,
                windowDays = settings.windowDays,
                overview = insight.overview,
                highlightsJson = json.encodeToString(insight.highlights),
                themesJson = json.encodeToString(insight.themes),
                noteIdsJson = json.encodeToString(notes.map(NoteEntity::id)),
            ),
        )
    }

    companion object {
        const val RECENT_INSIGHT_KIND = "recent_insight"
    }
}
