@file:Suppress("DEPRECATION")

package com.voiceledger.lite.semantic

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.voiceledger.lite.data.LocalAiSettings
import com.voiceledger.lite.data.RollupGranularity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class LocalSummaryEngine(
    private val context: Context,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    },
) {
    fun openSummarizer(settings: LocalAiSettings): PreparedSummarizer {
        val normalized = settings.normalized()
        val model = LocalModelLocator.resolveSummaryModel(context, normalized)
        if (model == null) {
            return HeuristicSummarizer()
        }
        val inference = LlmInference.createFromOptions(
            context,
            LlmInference.LlmInferenceOptions.builder()
                .setModelPath(model.path)
                .setMaxTokens(normalized.maxTokens)
                .setMaxTopK(normalized.topK)
                .build(),
        )
        return ModelSummarizer(
            inference = inference,
            model = model,
        )
    }

    suspend fun summarize(
        documents: List<SemanticDocument>,
        granularity: RollupGranularity,
        periodStartEpochMs: Long,
        periodEndEpochMs: Long,
        settings: LocalAiSettings,
    ): AggregateInsight {
        return openSummarizer(settings).use { summarizer ->
            summarizer.summarize(
                documents = documents,
                granularity = granularity,
                periodStartEpochMs = periodStartEpochMs,
                periodEndEpochMs = periodEndEpochMs,
            )
        }
    }

    interface PreparedSummarizer : AutoCloseable {
        fun summarize(
            documents: List<SemanticDocument>,
            granularity: RollupGranularity,
            periodStartEpochMs: Long,
            periodEndEpochMs: Long,
        ): AggregateInsight

        override fun close() = Unit
    }

    private inner class ModelSummarizer(
        private val inference: LlmInference,
        private val model: ResolvedLocalModel,
    ) : PreparedSummarizer {
        override fun summarize(
            documents: List<SemanticDocument>,
            granularity: RollupGranularity,
            periodStartEpochMs: Long,
            periodEndEpochMs: Long,
        ): AggregateInsight {
            val prompt = buildPrompt(documents, granularity, periodStartEpochMs, periodEndEpochMs)
            val rawResponse = inference.generateResponse(prompt)
            val parsed = json.decodeFromString<ModelInsightPayload>(extractJsonObject(rawResponse))
            return AggregateInsight(
                modelLabel = model.label,
                title = parsed.title.ifBlank { defaultTitle(granularity, periodStartEpochMs, periodEndEpochMs) },
                overview = parsed.overview.trim(),
                highlights = emptyList(),
                themes = emptyList(),
            )
        }

        override fun close() {
            inference.close()
        }
    }

    private inner class HeuristicSummarizer : PreparedSummarizer {
        override fun summarize(
            documents: List<SemanticDocument>,
            granularity: RollupGranularity,
            periodStartEpochMs: Long,
            periodEndEpochMs: Long,
        ): AggregateInsight {
            val rangeLabel = formatDateRange(periodStartEpochMs, periodEndEpochMs)
            val overview = buildString {
                append("Summary for $rangeLabel based on ${documents.size} source item(s).")
                val sampleLines = documents
                    .sortedByDescending(SemanticDocument::createdAtEpochMs)
                    .take(3)
                    .mapNotNull { document ->
                        document.body.lineSequence().firstOrNull()?.trim()?.takeIf(String::isNotBlank)?.let { line ->
                            "${document.title.ifBlank { "Untitled" }}: $line"
                        }
                    }
                if (sampleLines.isNotEmpty()) {
                    append(' ')
                    append(sampleLines.joinToString(" "))
                }
            }

            return AggregateInsight(
                modelLabel = "Built-in local summarizer (fallback)",
                title = defaultTitle(granularity, periodStartEpochMs, periodEndEpochMs),
                overview = overview,
                highlights = emptyList(),
                themes = emptyList(),
            )
        }
    }

    private fun buildPrompt(
        documents: List<SemanticDocument>,
        granularity: RollupGranularity,
        periodStartEpochMs: Long,
        periodEndEpochMs: Long,
    ): String {
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.systemDefault())
        val sourceBlock = buildSourceBlock(documents, granularity, formatter)

        return """
            Summarize and compress the following ${granularity.name.lowercase()} journal material from ${formatDateRange(periodStartEpochMs, periodEndEpochMs)} into one summary document.
            This rollup may itself be used as source material for the next level of summarization.
            Return strict JSON in this exact shape:
            {
              "title": "short title",
              "overview": "dense summary paragraph or short multi-sentence summary"
            }

            Rules:
            - Ground the summary strictly in the provided source text.
            - Write one coherent summary document, not bullets, themes, or metadata.
            - Preserve the most important facts, decisions, events, and repeated signals.
            - Prefer compression over verbosity.
            - Do not wrap the JSON in markdown fences.

            Sources:
            $sourceBlock
        """.trimIndent()
    }

    private fun buildSourceBlock(
        documents: List<SemanticDocument>,
        granularity: RollupGranularity,
        formatter: DateTimeFormatter,
    ): String {
        val totalBodyBudget = totalBodyBudget(granularity)
        val perDocumentBudget = (totalBodyBudget / documents.size.coerceAtLeast(1))
            .coerceIn(MIN_SOURCE_BODY_CHARS, MAX_SOURCE_BODY_CHARS)
        var remainingBudget = totalBodyBudget

        return documents.joinToString("\n\n") { document ->
            val bodyLimit = minOf(perDocumentBudget, remainingBudget).coerceAtLeast(MIN_SOURCE_BODY_CHARS)
            val body = document.body.take(bodyLimit)
            remainingBudget = (remainingBudget - body.length).coerceAtLeast(0)
            buildString {
                append("source_id=")
                append(document.sourceId)
                append('\n')
                append("created_at=")
                append(formatter.format(Instant.ofEpochMilli(document.createdAtEpochMs)))
                append('\n')
                append("note_ids=")
                append(document.noteIds.joinToString(","))
                append('\n')
                append("title=")
                append(document.title)
                append('\n')
                append("body=")
                append(body)
            }
        }
    }

    private fun totalBodyBudget(granularity: RollupGranularity): Int {
        return when (granularity) {
            RollupGranularity.DAILY -> 8_000
            RollupGranularity.WEEKLY -> 10_000
            RollupGranularity.MONTHLY -> 12_000
            RollupGranularity.YEARLY -> 14_000
        }
    }

    private fun defaultTitle(
        granularity: RollupGranularity,
        periodStartEpochMs: Long,
        periodEndEpochMs: Long,
    ): String {
        return "${granularity.name.lowercase().replaceFirstChar { it.uppercase() }} summary: ${formatDateRange(periodStartEpochMs, periodEndEpochMs)}"
    }

    private fun formatDateRange(startEpochMs: Long, endEpochMs: Long): String {
        val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault())
        return "${formatter.format(Instant.ofEpochMilli(startEpochMs))} to ${formatter.format(Instant.ofEpochMilli(endEpochMs))}"
    }

    private fun extractJsonObject(raw: String): String {
        val startIndex = raw.indexOf('{')
        val endIndex = raw.lastIndexOf('}')
        if (startIndex == -1 || endIndex <= startIndex) {
            return raw.trim()
        }
        return raw.substring(startIndex, endIndex + 1)
    }

    @Serializable
    private data class ModelInsightPayload(
        val title: String = "",
        val overview: String = "",
    )

    companion object {
        private const val MIN_SOURCE_BODY_CHARS = 600
        private const val MAX_SOURCE_BODY_CHARS = 2_500
    }
}
