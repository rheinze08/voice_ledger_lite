package com.voiceledger.lite.data

import com.voiceledger.lite.semantic.AggregateInsight
import com.voiceledger.lite.semantic.AggregationCheckpoint
import com.voiceledger.lite.semantic.RollupSnapshot
import com.voiceledger.lite.semantic.SemanticSearchHit
import com.voiceledger.lite.semantic.ThemeBucket
import java.time.Instant
import java.time.ZoneId
import kotlin.math.min
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LedgerRepository(
    private val noteDao: NoteDao,
    private val rollupDao: RollupDao,
    private val semanticEntryDao: SemanticEntryDao,
    private val checkpointDao: AggregationCheckpointDao,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun observeNotes(): Flow<List<NoteEntity>> = noteDao.observeAll()

    fun observeRollups(): Flow<List<RollupSnapshot>> {
        return rollupDao.observeAll().map { entities ->
            entities.map(::toRollupSnapshot)
        }
    }

    fun observeCheckpoints(): Flow<List<AggregationCheckpoint>> {
        return checkpointDao.observeAll().map { entities ->
            entities.map(::toCheckpoint)
        }
    }

    suspend fun allNotesAscending(): List<NoteEntity> = noteDao.allAscending()

    suspend fun getNote(noteId: Long): NoteEntity? = noteDao.getById(noteId)

    suspend fun saveNote(noteId: Long?, title: String, body: String): Long {
        val now = System.currentTimeMillis()
        val cleanedTitle = title.trim()
        val cleanedBody = body.trim()
        var dirtyEpochMs = now

        val savedId = if (noteId == null) {
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
            dirtyEpochMs = existing?.createdAtEpochMs ?: now
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
                note.id
            }
        }

        val dirtyFrom = floorToDayStart(dirtyEpochMs)
        markDirtyFrom(dirtyFrom)
        return savedId
    }

    suspend fun deleteNote(noteId: Long) {
        val existing = noteDao.getById(noteId)
        noteDao.deleteById(noteId)
        semanticEntryDao.deleteById("note:$noteId")
        existing?.let { note ->
            markDirtyFrom(floorToDayStart(note.createdAtEpochMs))
        }
    }

    suspend fun replaceRollup(
        id: String,
        granularity: RollupGranularity,
        periodStartEpochMs: Long,
        periodEndEpochMs: Long,
        sourceCount: Int,
        insight: AggregateInsight,
        noteIds: List<Long>,
    ) {
        rollupDao.replace(
            RollupEntity(
                id = id,
                granularity = granularity.name,
                periodStartEpochMs = periodStartEpochMs,
                periodEndEpochMs = periodEndEpochMs,
                generatedAtEpochMs = System.currentTimeMillis(),
                modelLabel = insight.modelLabel,
                sourceCount = sourceCount,
                title = insight.title,
                overview = insight.overview,
                highlightsJson = json.encodeToString(insight.highlights),
                themesJson = json.encodeToString(insight.themes),
                noteIdsJson = json.encodeToString(noteIds.distinct()),
            ),
        )
    }

    suspend fun deleteRollup(id: String) {
        rollupDao.deleteById(id)
        semanticEntryDao.deleteBySourceId(id)
    }

    suspend fun rollupsByGranularity(granularity: RollupGranularity): List<RollupSnapshot> {
        return rollupDao.byGranularityAscending(granularity.name).map(::toRollupSnapshot)
    }

    suspend fun replaceSemanticEntry(entry: SemanticEntryEntity) {
        semanticEntryDao.replace(entry)
    }

    suspend fun allSemanticEntries(): List<SemanticEntryEntity> = semanticEntryDao.all()

    suspend fun checkpoint(granularity: RollupGranularity): AggregationCheckpoint {
        return checkpointDao.get(granularity.name)?.let(::toCheckpoint)
            ?: AggregationCheckpoint(
                granularity = granularity,
                lastCompletedEndEpochMs = null,
                dirtyFromEpochMs = null,
                lastRunStartedEpochMs = null,
                lastRunFinishedEpochMs = null,
                lastError = null,
            )
    }

    suspend fun updateCheckpoint(checkpoint: AggregationCheckpoint) {
        checkpointDao.replace(
            AggregationCheckpointEntity(
                granularity = checkpoint.granularity.name,
                lastCompletedEndEpochMs = checkpoint.lastCompletedEndEpochMs,
                dirtyFromEpochMs = checkpoint.dirtyFromEpochMs,
                lastRunStartedEpochMs = checkpoint.lastRunStartedEpochMs,
                lastRunFinishedEpochMs = checkpoint.lastRunFinishedEpochMs,
                lastError = checkpoint.lastError,
            ),
        )
    }

    suspend fun markDirtyFrom(epochMs: Long) {
        RollupGranularity.entries.forEach { granularity ->
            val current = checkpoint(granularity)
            val nextDirtyFrom = current.dirtyFromEpochMs?.let { min(it, epochMs) } ?: epochMs
            updateCheckpoint(
                current.copy(
                    dirtyFromEpochMs = nextDirtyFrom,
                    lastError = null,
                ),
            )
        }
    }

    suspend fun searchHits(limit: Int): List<SemanticSearchHit> {
        return semanticEntryDao.all()
            .take(limit)
            .map { entry ->
                SemanticSearchHit(
                    entryId = entry.entryId,
                    kind = entry.kind,
                    title = entry.title,
                    preview = entry.body,
                    score = 0f,
                    noteId = entry.noteId,
                    rollupId = entry.rollupId,
                    granularity = entry.granularity?.let(RollupGranularity::valueOf),
                )
            }
    }

    private fun toRollupSnapshot(entity: RollupEntity): RollupSnapshot {
        return RollupSnapshot(
            id = entity.id,
            granularity = RollupGranularity.valueOf(entity.granularity),
            periodStartEpochMs = entity.periodStartEpochMs,
            periodEndEpochMs = entity.periodEndEpochMs,
            generatedAtEpochMs = entity.generatedAtEpochMs,
            modelLabel = entity.modelLabel,
            sourceCount = entity.sourceCount,
            title = entity.title,
            overview = entity.overview,
            highlights = json.decodeFromString<List<String>>(entity.highlightsJson),
            themes = json.decodeFromString<List<ThemeBucket>>(entity.themesJson),
            noteIds = json.decodeFromString<List<Long>>(entity.noteIdsJson),
        )
    }

    private fun toCheckpoint(entity: AggregationCheckpointEntity): AggregationCheckpoint {
        return AggregationCheckpoint(
            granularity = RollupGranularity.valueOf(entity.granularity),
            lastCompletedEndEpochMs = entity.lastCompletedEndEpochMs,
            dirtyFromEpochMs = entity.dirtyFromEpochMs,
            lastRunStartedEpochMs = entity.lastRunStartedEpochMs,
            lastRunFinishedEpochMs = entity.lastRunFinishedEpochMs,
            lastError = entity.lastError,
        )
    }

    private fun floorToDayStart(epochMs: Long): Long {
        return Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
}
