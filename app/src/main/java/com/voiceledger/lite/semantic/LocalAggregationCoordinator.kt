package com.voiceledger.lite.semantic

import android.content.Context
import com.voiceledger.lite.data.LabelEntity
import com.voiceledger.lite.data.LedgerRepository
import com.voiceledger.lite.data.LocalAiSettings
import com.voiceledger.lite.data.NoteEntity
import com.voiceledger.lite.data.NoteWithLabels
import com.voiceledger.lite.data.RollupGranularity
import com.voiceledger.lite.data.SemanticEntryEntity
import com.voiceledger.lite.data.SettingsStore
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LocalAggregationCoordinator(
    context: Context,
    private val repository: LedgerRepository,
    private val settingsStore: SettingsStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val modelProvisioner = LocalModelProvisioner(context, settingsStore)
    private val summaryEngine = LocalSummaryEngine(context)
    private val embeddingEngine = LocalEmbeddingEngine(context)
    private val answerEngine = LocalAnswerEngine(context)
    private val zoneId = ZoneId.systemDefault()

    suspend fun runAggregation(
        rebuildFromStartDate: Boolean = false,
        onProgress: suspend (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val runReferenceEpochMs = System.currentTimeMillis()
        val modelStatus = modelProvisioner.ensureInstalled()
        val settings = settingsStore.load().normalized()
        val notes = repository.allNotesAscending()

        if (notes.isEmpty()) {
            return@withContext "No notes yet. Update will begin after the first note."
        }

        val configuredFloor = settings.summaryStartDate
            .takeIf(String::isNotBlank)
            ?.let { LocalDate.parse(it).atStartOfDay(zoneId).toInstant().toEpochMilli() }

        if (rebuildFromStartDate && configuredFloor != null) {
            repository.markDirtyFrom(configuredFloor)
        }

        val checkpoints = RollupGranularity.entries.associateWith { granularity ->
            repository.checkpoint(granularity)
        }
        val dirtyFloor = checkpoints.values.mapNotNull(AggregationCheckpoint::dirtyFromEpochMs).minOrNull()
        val noteReindexFloor = when {
            rebuildFromStartDate -> configuredFloor ?: notes.first().createdAtEpochMs
            dirtyFloor != null -> dirtyFloor
            checkpoints.values.all { it.lastCompletedEndEpochMs == null } -> notes.first().createdAtEpochMs
            else -> null
        }
        val notesToReindex = noteReindexFloor?.let { floor ->
            notes.filter { note -> note.createdAtEpochMs >= floor }
        }.orEmpty()

        embeddingEngine.openEmbedder(settings).use { embedder ->
            if (notesToReindex.isNotEmpty()) {
                onProgress("Reindexing ${notesToReindex.size} note(s)")
                reindexNotes(notesToReindex, embedder)
            }

            summaryEngine.openSummarizer(settings).use { summarizer ->
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
                    onProgress("Building ${granularity.displayLabel().lowercase()} summaries")
                    val checkpoint = repository.checkpoint(granularity)
                    repository.updateCheckpoint(
                        checkpoint.copy(
                            lastRunStartedEpochMs = runReferenceEpochMs,
                            lastError = null,
                        ),
                    )

                    val sourceFloor = checkpoint.dirtyFromEpochMs
                        ?: checkpoint.lastCompletedEndEpochMs
                        ?: dailySources.firstOrNull()?.createdAtEpochMs
                        ?: run {
                            dailySources = repository.rollupsByGranularity(granularity).map { rollup ->
                                SemanticDocument(
                                    sourceId = rollup.id,
                                    title = rollup.title,
                                    body = rollup.overview,
                                    noteIds = rollup.noteIds,
                                    createdAtEpochMs = rollup.periodStartEpochMs,
                                )
                            }
                            return@forEach
                        }
                    val effectiveFloor = configuredFloor?.let { max(it, sourceFloor) } ?: sourceFloor

                    try {
                        val lastProcessedEnd = processGranularity(
                            granularity = granularity,
                            sourceDocuments = dailySources,
                            floorEpochMs = effectiveFloor,
                            maxSourcesPerRollup = summarySourceLimit(granularity, settings.maxSourcesPerRollup),
                            summarizer = summarizer,
                            embedder = embedder,
                            runReferenceEpochMs = runReferenceEpochMs,
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
                        if (exception is CancellationException) throw exception
                        val refreshedCheckpoint = repository.checkpoint(granularity)
                        val errorDetail = buildString {
                            var current: Throwable? = exception
                            var depth = 0
                            while (current != null && depth < 5) {
                                if (depth > 0) append(" → ")
                                append(current.javaClass.simpleName)
                                current.message?.takeIf { it.isNotBlank() }?.let { append(": $it") }
                                val next = current.cause
                                current = if (next != null && next !== current) next else null
                                depth++
                            }
                        }
                        repository.updateCheckpoint(
                            refreshedCheckpoint.copy(
                                lastRunFinishedEpochMs = System.currentTimeMillis(),
                                lastError = errorDetail,
                            ),
                        )
                        throw RuntimeException("${granularity.displayLabel()} summaries failed — $errorDetail", exception)
                    }

                    dailySources = repository.rollupsByGranularity(granularity).map { rollup ->
                        SemanticDocument(
                            sourceId = rollup.id,
                            title = rollup.title,
                            body = rollup.overview,
                            noteIds = rollup.noteIds,
                            createdAtEpochMs = rollup.periodStartEpochMs,
                        )
                    }
                }
            }
        }

        val notices = buildList {
            if (!modelStatus.summary.isReady) {
                add("Summary model is not installed yet, so summaries used the built-in fallback.")
            }
            if (!modelStatus.embedding.isReady) {
                add("Embedding model is not installed yet, so search index entries used hashed fallback vectors.")
            }
        }
        return@withContext if (notices.isEmpty()) {
            "Summaries and semantic search index refreshed."
        } else {
            "Summaries and semantic search index refreshed. ${notices.joinToString(" ")}"
        }
    }

    suspend fun search(query: String, labelIds: Set<Long> = emptySet()): SemanticSearchResponse = withContext(Dispatchers.IO) {
        val normalized = query.trim()
        if (normalized.isBlank()) {
            return@withContext SemanticSearchResponse(route = emptyList(), hits = emptyList())
        }

        val modelStatus = modelProvisioner.ensureInstalled()
        val settings = settingsStore.load().normalized()
        val queryVector = embeddingEngine.embed(normalized, settings)
        val decodedEntries = repository.allSemanticEntries().mapNotNull(::decodeEntry)
        if (decodedEntries.isEmpty()) {
            return@withContext SemanticSearchResponse(route = emptyList(), hits = emptyList())
        }

        val noteLabelScope = if (labelIds.isEmpty()) {
            null
        } else {
            repository.noteIdsWithAnyLabels(labelIds)
        }
        if (noteLabelScope != null && noteLabelScope.isEmpty()) {
            return@withContext SemanticSearchResponse(route = emptyList(), hits = emptyList())
        }

        val noteEntries = decodedEntries.filter { it.entry.kind == "note" && it.entry.noteId != null }
        val rollupEntriesById = decodedEntries
            .filter { it.entry.kind == "rollup" }
            .associateBy { it.entry.rollupId ?: it.entry.sourceId }
        val rollups = repository.allRollups()
        val rollupsById = rollups.associateBy(RollupSnapshot::id)

        val topYears = selectStage(
            rollups = rollups.filter { it.granularity == RollupGranularity.YEARLY },
            rollupEntriesById = rollupEntriesById,
            queryVector = queryVector,
            labelScope = noteLabelScope,
            limit = 2,
        )
        val topMonths = selectStage(
            rollups = rollups.filter { it.granularity == RollupGranularity.MONTHLY && withinParents(it, topYears) },
            rollupEntriesById = rollupEntriesById,
            queryVector = queryVector,
            labelScope = noteLabelScope,
            limit = 3,
        )
        val topWeeks = selectStage(
            rollups = rollups.filter { it.granularity == RollupGranularity.WEEKLY && withinParents(it, topMonths) },
            rollupEntriesById = rollupEntriesById,
            queryVector = queryVector,
            labelScope = noteLabelScope,
            limit = 4,
        )
        val topDays = selectStage(
            rollups = rollups.filter { it.granularity == RollupGranularity.DAILY && withinParents(it, topWeeks) },
            rollupEntriesById = rollupEntriesById,
            queryVector = queryVector,
            labelScope = noteLabelScope,
            limit = 5,
        )

        val candidateNoteIds = buildCandidateNoteIds(
            topDays = topDays,
            topWeeks = topWeeks,
            topMonths = topMonths,
            topYears = topYears,
            labelScope = noteLabelScope,
            allNoteIds = noteEntries.mapNotNull { it.entry.noteId }.toSet(),
        )
        val noteMap = repository.notesWithLabelsByIds(candidateNoteIds)

        val noteHits = noteEntries
            .filter { decoded ->
                val noteId = decoded.entry.noteId ?: return@filter false
                candidateNoteIds.contains(noteId)
            }
            .map { decoded ->
                val noteId = decoded.entry.noteId ?: error("Note id missing.")
                val note = noteMap[noteId]
                SemanticSearchHit(
                    entryId = decoded.entry.entryId,
                    kind = "note",
                    title = decoded.entry.title,
                    preview = note?.note?.body?.take(220) ?: decoded.entry.body,
                    score = cosineSimilarity(queryVector, decoded.embedding),
                    noteId = noteId,
                    rollupId = null,
                    granularity = null,
                    labels = note?.labels?.map(LabelEntity::name).orEmpty(),
                )
            }
            .sortedByDescending(SemanticSearchHit::score)
            .take(settings.searchResultLimit)

        val route = listOfNotNull(
            topYears.firstOrNull(),
            topMonths.firstOrNull(),
            topWeeks.firstOrNull(),
            topDays.firstOrNull(),
        ).map { scored ->
            SearchRouteStep(
                granularity = scored.rollup.granularity,
                title = scored.rollup.title,
                score = scored.score,
            )
        }

        val remainingResultSlots = (settings.searchResultLimit - noteHits.size).coerceAtLeast(0)
        val rollupHits = (topDays + topWeeks + topMonths + topYears)
            .distinctBy { it.rollup.id }
            .map { scored ->
                SemanticSearchHit(
                    entryId = "rollup:${scored.rollup.id}",
                    kind = "rollup",
                    title = scored.rollup.title,
                    preview = scored.rollup.overview,
                    score = scored.score,
                    noteId = scored.rollup.noteIds.firstOrNull(),
                    rollupId = scored.rollup.id,
                    granularity = scored.rollup.granularity,
                )
            }
            .filter { hit ->
                noteHits.none { existing -> existing.entryId == hit.entryId }
            }
            .take(remainingResultSlots)

        val hits = if (noteHits.size >= settings.searchResultLimit) {
            noteHits
        } else {
            noteHits + rollupHits
        }
        val answerDocuments = buildAnswerDocuments(
            hits = hits,
            notesById = noteMap,
            rollupsById = rollupsById,
        )
        val answer = runCatching {
            answerEngine.answer(normalized, answerDocuments, settings)
        }.getOrNull()
        val notices = buildList {
            if (!modelStatus.embedding.isReady) {
                add("Embedding model is not installed yet. Ask is using hashed fallback vectors for retrieval.")
            }
            if (hits.isNotEmpty() && answer == null) {
                if (!modelStatus.summary.isReady) {
                    add("Summary model is not installed yet. Ask is showing retrieved notes and summaries only.")
                } else {
                    add("The local answer model did not return an answer for these results.")
                }
            }
        }

        return@withContext SemanticSearchResponse(
            route = route,
            hits = hits,
            answer = answer,
            answerNotice = notices.takeIf(List<String>::isNotEmpty)?.joinToString(" "),
        )
    }

    private suspend fun reindexNotes(
        notes: List<NoteEntity>,
        embedder: LocalEmbeddingEngine.PreparedEmbedder,
    ) {
        notes.forEach { note ->
            val embedding = embedder.embed("${note.title}\n${note.body}")
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
        maxSourcesPerRollup: Int,
        summarizer: LocalSummaryEngine.PreparedSummarizer,
        embedder: LocalEmbeddingEngine.PreparedEmbedder,
        runReferenceEpochMs: Long,
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
            val bucketDocuments = sourceDocuments
                .filter { document ->
                    val sourceStart = startOfPeriod(document.createdAtEpochMs, granularity)
                    sourceStart == bucketStart
                }
                .sortedByDescending(SemanticDocument::createdAtEpochMs)
            val docsForSummary = bucketDocuments.take(maxSourcesPerRollup)

            val rollupId = "${granularity.name.lowercase()}:$bucketStart"
            if (docsForSummary.isEmpty()) {
                repository.deleteRollup(rollupId)
                return@forEach
            }

            val insight = summarizer.summarize(
                documents = docsForSummary,
                granularity = granularity,
                periodStartEpochMs = bucketStart,
                periodEndEpochMs = bucketEnd,
            )
            val noteIds = bucketDocuments.flatMap(SemanticDocument::noteIds).distinct()

            repository.replaceRollup(
                id = rollupId,
                granularity = granularity,
                periodStartEpochMs = bucketStart,
                periodEndEpochMs = bucketEnd,
                sourceCount = bucketDocuments.size,
                insight = insight,
                noteIds = noteIds,
            )

            val rollupEmbedding = embedder.embed(
                "${insight.title}\n${insight.overview}",
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
            lastProcessedEnd = min(bucketEnd, runReferenceEpochMs)
        }
        return lastProcessedEnd
    }

    private fun buildCandidateNoteIds(
        topDays: List<ScoredRollup>,
        topWeeks: List<ScoredRollup>,
        topMonths: List<ScoredRollup>,
        topYears: List<ScoredRollup>,
        labelScope: Set<Long>?,
        allNoteIds: Set<Long>,
    ): Set<Long> {
        val timelineIds = when {
            topDays.isNotEmpty() -> topDays.flatMap { it.rollup.noteIds }
            topWeeks.isNotEmpty() -> topWeeks.flatMap { it.rollup.noteIds }
            topMonths.isNotEmpty() -> topMonths.flatMap { it.rollup.noteIds }
            topYears.isNotEmpty() -> topYears.flatMap { it.rollup.noteIds }
            else -> emptyList()
        }.toSet()

        return when {
            labelScope == null && timelineIds.isNotEmpty() -> timelineIds
            labelScope == null -> allNoteIds
            timelineIds.isEmpty() -> labelScope
            else -> timelineIds.intersect(labelScope).ifEmpty { labelScope }
        }
    }

    private fun buildAnswerDocuments(
        hits: List<SemanticSearchHit>,
        notesById: Map<Long, NoteWithLabels>,
        rollupsById: Map<String, RollupSnapshot>,
    ): List<SemanticDocument> {
        return hits.asSequence()
            .mapNotNull { hit ->
                when {
                    hit.noteId != null -> {
                        val note = notesById[hit.noteId] ?: return@mapNotNull null
                        SemanticDocument(
                            sourceId = "note:${note.note.id}",
                            title = note.note.title,
                            body = note.note.body,
                            noteIds = listOf(note.note.id),
                            createdAtEpochMs = note.note.createdAtEpochMs,
                        )
                    }
                    hit.rollupId != null -> {
                        val rollup = rollupsById[hit.rollupId] ?: return@mapNotNull null
                        SemanticDocument(
                            sourceId = rollup.id,
                            title = rollup.title,
                            body = rollup.overview,
                            noteIds = rollup.noteIds,
                            createdAtEpochMs = rollup.periodStartEpochMs,
                        )
                    }
                    else -> null
                }
            }
            .distinctBy(SemanticDocument::sourceId)
            .take(6)
            .toList()
    }

    private fun selectStage(
        rollups: List<RollupSnapshot>,
        rollupEntriesById: Map<String, DecodedEntry>,
        queryVector: FloatArray,
        labelScope: Set<Long>?,
        limit: Int,
    ): List<ScoredRollup> {
        return rollups.mapNotNull { rollup ->
            val decoded = rollupEntriesById[rollup.id] ?: return@mapNotNull null
            val semanticScore = cosineSimilarity(queryVector, decoded.embedding)
            val labelBoost = if (labelScope.isNullOrEmpty() || rollup.noteIds.isEmpty()) {
                0f
            } else {
                val matchingCount = rollup.noteIds.count(labelScope::contains)
                0.15f * (matchingCount.toFloat() / rollup.noteIds.size.toFloat())
            }
            ScoredRollup(
                rollup = rollup,
                score = semanticScore + labelBoost,
            )
        }
            .sortedByDescending(ScoredRollup::score)
            .take(limit)
    }

    private fun withinParents(candidate: RollupSnapshot, parents: List<ScoredRollup>): Boolean {
        if (parents.isEmpty()) {
            return true
        }
        return parents.any { parent ->
            candidate.periodStartEpochMs >= parent.rollup.periodStartEpochMs &&
                candidate.periodEndEpochMs <= parent.rollup.periodEndEpochMs
        }
    }

    private fun decodeEntry(entry: SemanticEntryEntity): DecodedEntry? {
        val embedding = runCatching {
            json.decodeFromString<List<Float>>(entry.embeddingJson).toFloatArray()
        }.getOrNull() ?: return null
        return DecodedEntry(entry = entry, embedding = embedding)
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

private fun RollupGranularity.displayLabel(): String {
    return name.lowercase().replaceFirstChar { it.uppercase() }
}

private fun summarySourceLimit(granularity: RollupGranularity, configuredMaxSources: Int): Int {
    val hardCap = when (granularity) {
        RollupGranularity.DAILY -> 8
        RollupGranularity.WEEKLY -> 7
        RollupGranularity.MONTHLY -> 6
        RollupGranularity.YEARLY -> 6
    }
    return configuredMaxSources.coerceAtMost(hardCap)
}

private data class DecodedEntry(
    val entry: SemanticEntryEntity,
    val embedding: FloatArray,
)

private data class ScoredRollup(
    val rollup: RollupSnapshot,
    val score: Float,
)
