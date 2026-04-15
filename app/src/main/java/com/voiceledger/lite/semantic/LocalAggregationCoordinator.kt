package com.voiceledger.lite.semantic

import android.content.Context
import com.voiceledger.lite.data.LedgerRepository
import com.voiceledger.lite.data.LocalAiSettings
import com.voiceledger.lite.data.NoteEntity
import com.voiceledger.lite.data.RollupGranularity
import com.voiceledger.lite.data.SemanticEntryEntity
import com.voiceledger.lite.data.SettingsStore
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth
import kotlin.math.max
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LocalAggregationCoordinator(
    context: Context,
    private val repository: LedgerRepository,
    private val settingsStore: SettingsStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val summaryEngine = LocalSummaryEngine(context)
    private val embeddingEngine = LocalEmbeddingEngine(context)
    private val zoneId = ZoneId.systemDefault()

    suspend fun runAggregation(rebuildFromStartDate: Boolean = false): String {
        val settings = settingsStore.load().normalized()
        val notes = repository.allNotesAscending()
        reindexNotes(notes, settings)

        if (notes.isEmpty()) {
            return "No notes yet. Local aggregation will start after the first note."
        }

        val configuredFloor = settings.summaryStartDate
            .takeIf(String::isNotBlank)
            ?.let { LocalDate.parse(it).atStartOfDay(zoneId).toInstant().toEpochMilli() }

        if (rebuildFromStartDate && configuredFloor != null) {
            repository.markDirtyFrom(configuredFloor)
        }

        var dailySources = notes.map { note ->
            SemanticDocument(
                sourceId = "note:${note.id}",
                title = note.title,
                body = note.body,
                noteIds = listOf(note.id),
                createdAtEpochMs = note.createdAtEpochMs,
            )
        }

        RollupGranularity.entries.forEach { granularity ->
            val checkpoint = repository.checkpoint(granularity)
            repository.updateCheckpoint(
                checkpoint.copy(
                    lastRunStartedEpochMs = System.currentTimeMillis(),
                    lastError = null,
                ),
            )

            val sourceFloor = checkpoint.dirtyFromEpochMs
                ?: checkpoint.lastCompletedEndEpochMs
                ?: dailySources.first().createdAtEpochMs
            val effectiveFloor = configuredFloor?.let { max(it, sourceFloor) } ?: sourceFloor

            try {
                val lastProcessedEnd = processGranularity(
                    granularity = granularity,
                    sourceDocuments = dailySources,
                    floorEpochMs = effectiveFloor,
                    settings = settings,
                )
                val refreshedCheckpoint = repository.checkpoint(granularity)
                repository.updateCheckpoint(
                    refreshedCheckpoint.copy(
                        dirtyFromEpochMs = null,
                        lastCompletedEndEpochMs = lastProcessedEnd ?: refreshedCheckpoint.lastCompletedEndEpochMs,
                        lastRunFinishedEpochMs = System.currentTimeMillis(),
                        lastError = null,
                    ),
                )
            } catch (exception: Exception) {
                val refreshedCheckpoint = repository.checkpoint(granularity)
                repository.updateCheckpoint(
                    refreshedCheckpoint.copy(
                        lastRunFinishedEpochMs = System.currentTimeMillis(),
                        lastError = exception.message ?: "Aggregation failed.",
                    ),
                )
                throw exception
            }

            dailySources = repository.rollupsByGranularity(granularity).map { rollup ->
                SemanticDocument(
                    sourceId = rollup.id,
                    title = rollup.title,
                    body = buildString {
                        appendLine(rollup.overview)
                        rollup.highlights.forEach { appendLine(it) }
                    }.trim(),
                    noteIds = rollup.noteIds,
                    createdAtEpochMs = rollup.periodStartEpochMs,
                )
            }
        }

        return "Local rollups and semantic search index refreshed."
    }

    suspend fun search(query: String): List<SemanticSearchHit> {
        val normalized = query.trim()
        if (normalized.isBlank()) {
            return emptyList()
        }
        val settings = settingsStore.load().normalized()
        val queryVector = embeddingEngine.embed(normalized, settings)
        return repository.allSemanticEntries()
            .mapNotNull { entry ->
                val embedding = runCatching {
                    json.decodeFromString<List<Float>>(entry.embeddingJson).toFloatArray()
                }.getOrNull() ?: return@mapNotNull null
                val score = cosineSimilarity(queryVector, embedding)
                SemanticSearchHit(
                    entryId = entry.entryId,
                    kind = entry.kind,
                    title = entry.title,
                    preview = entry.body,
                    score = score,
                    noteId = entry.noteId,
                    rollupId = entry.rollupId,
                    granularity = entry.granularity?.let(RollupGranularity::valueOf),
                )
            }
            .sortedByDescending(SemanticSearchHit::score)
            .take(settings.searchResultLimit)
    }

    private suspend fun reindexNotes(notes: List<NoteEntity>, settings: LocalAiSettings) {
        notes.forEach { note ->
            val embedding = embeddingEngine.embed("${note.title}\n${note.body}", settings)
            repository.replaceSemanticEntry(
                SemanticEntryEntity(
                    entryId = "note:${note.id}",
                    kind = "note",
                    sourceId = "note:${note.id}",
                    title = note.title,
                    body = note.body.take(220),
                    noteId = note.id,
                    rollupId = null,
                    granularity = null,
                    embeddingJson = json.encodeToString(embedding.toList()),
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    private suspend fun processGranularity(
        granularity: RollupGranularity,
        sourceDocuments: List<SemanticDocument>,
        floorEpochMs: Long,
        settings: LocalAiSettings,
    ): Long? {
        val floorStart = startOfPeriod(floorEpochMs, granularity)
        val existingBucketStarts = repository.rollupsByGranularity(granularity)
            .map(RollupSnapshot::periodStartEpochMs)
            .filter { it >= floorStart }
        val sourceBucketStarts = sourceDocuments
            .map { startOfPeriod(it.createdAtEpochMs, granularity) }
            .filter { it >= floorStart }
        val bucketStarts = (sourceBucketStarts + existingBucketStarts)
            .distinct()
            .sorted()

        if (bucketStarts.isEmpty()) {
            return null
        }

        var lastProcessedEnd: Long? = null
        bucketStarts.forEach { bucketStart ->
            val bucketEnd = endOfPeriod(bucketStart, granularity)
            val docs = sourceDocuments
                .filter { document ->
                    val sourceStart = startOfPeriod(document.createdAtEpochMs, granularity)
                    sourceStart == bucketStart
                }
                .sortedByDescending(SemanticDocument::createdAtEpochMs)
                .take(settings.maxSourcesPerRollup)

            val rollupId = "${granularity.name.lowercase()}:$bucketStart"
            if (docs.isEmpty()) {
                repository.deleteRollup(rollupId)
                return@forEach
            }

            val insight = summaryEngine.summarize(
                documents = docs,
                granularity = granularity,
                periodStartEpochMs = bucketStart,
                periodEndEpochMs = bucketEnd,
                settings = settings,
            )
            val noteIds = docs.flatMap(SemanticDocument::noteIds).distinct()

            repository.replaceRollup(
                id = rollupId,
                granularity = granularity,
                periodStartEpochMs = bucketStart,
                periodEndEpochMs = bucketEnd,
                sourceCount = docs.size,
                insight = insight,
                noteIds = noteIds,
            )

            val rollupEmbedding = embeddingEngine.embed(
                buildString {
                    appendLine(insight.title)
                    appendLine(insight.overview)
                    insight.highlights.forEach { appendLine(it) }
                },
                settings,
            )
            repository.replaceSemanticEntry(
                SemanticEntryEntity(
                    entryId = "rollup:$rollupId",
                    kind = "rollup",
                    sourceId = rollupId,
                    title = insight.title,
                    body = insight.overview.take(220),
                    noteId = noteIds.firstOrNull(),
                    rollupId = rollupId,
                    granularity = granularity.name,
                    embeddingJson = json.encodeToString(rollupEmbedding.toList()),
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
            lastProcessedEnd = bucketEnd
        }
        return lastProcessedEnd
    }

    private fun startOfPeriod(epochMs: Long, granularity: RollupGranularity): Long {
        val dateTime = Instant.ofEpochMilli(epochMs).atZone(zoneId).toLocalDateTime()
        return when (granularity) {
            RollupGranularity.DAILY -> dateTime.toLocalDate().atStartOfDay(zoneId).toInstant().toEpochMilli()
            RollupGranularity.WEEKLY -> {
                val start = dateTime.toLocalDate().with(DayOfWeek.MONDAY)
                start.atStartOfDay(zoneId).toInstant().toEpochMilli()
            }
            RollupGranularity.MONTHLY -> {
                YearMonth.from(dateTime).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            }
            RollupGranularity.YEARLY -> {
                LocalDate.of(dateTime.year, 1, 1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            }
        }
    }

    private fun endOfPeriod(startEpochMs: Long, granularity: RollupGranularity): Long {
        val start = Instant.ofEpochMilli(startEpochMs).atZone(zoneId).toLocalDateTime()
        val next = when (granularity) {
            RollupGranularity.DAILY -> start.plusDays(1)
            RollupGranularity.WEEKLY -> start.plusWeeks(1)
            RollupGranularity.MONTHLY -> start.plusMonths(1)
            RollupGranularity.YEARLY -> start.plusYears(1)
        }
        return next.atZone(zoneId).toInstant().toEpochMilli() - 1
    }

    private fun cosineSimilarity(left: FloatArray, right: FloatArray): Float {
        if (left.isEmpty() || right.isEmpty() || left.size != right.size) {
            return 0f
        }
        var sum = 0f
        for (index in left.indices) {
            sum += left[index] * right[index]
        }
        return sum
    }
}
