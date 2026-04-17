package com.voiceledger.lite.semantic

import android.content.Context
import com.voiceledger.lite.data.LocalAiSettings
import com.voiceledger.lite.data.RollupGranularity
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class LocalSummaryEngine(
    private val context: Context,
) {
    private val liteRtLmEngine = LocalLiteRtLmEngine(context)

    fun openSummarizer(settings: LocalAiSettings): PreparedSummarizer {
        val normalized = settings.normalized()
        val model = LocalModelLocator.resolveSummaryModel(context, normalized)
        if (model == null) {
            return HeuristicSummarizer()
        }
        val session = liteRtLmEngine.openSession(model = model, settings = normalized)
        return ModelSummarizer(
            session = session,
            model = model,
            maxTokens = normalized.maxTokens.coerceAtMost(SUMMARY_MAX_TOKENS),
            topK = normalized.topK.coerceAtMost(SUMMARY_TOP_K),
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
        suspend fun summarize(
            documents: List<SemanticDocument>,
            granularity: RollupGranularity,
            periodStartEpochMs: Long,
            periodEndEpochMs: Long,
            onDiagnostic: suspend (String) -> Unit = {},
        ): AggregateInsight

        override fun close() = Unit
    }

    private inner class ModelSummarizer(
        private val session: LocalLiteRtLmEngine.PreparedSession,
        private val model: ResolvedLocalModel,
        private val maxTokens: Int,
        private val topK: Int,
    ) : PreparedSummarizer {
        override suspend fun summarize(
            documents: List<SemanticDocument>,
            granularity: RollupGranularity,
            periodStartEpochMs: Long,
            periodEndEpochMs: Long,
            onDiagnostic: suspend (String) -> Unit,
        ): AggregateInsight {
            onDiagnostic(
                "Preparing ${documents.size} source document(s), ${documents.sumOf { it.body.length }} body chars before sanitizing",
            )
            onDiagnostic("Using LiteRT-LM backend ${session.backendLabel}, maxTokens=$maxTokens, topK=$topK")
            val prompt = buildPrompt(documents, granularity, periodStartEpochMs, periodEndEpochMs, maxTokens)
            onDiagnostic(
                "Prompt built for ${granularity.name.lowercase()} ${formatDateRange(periodStartEpochMs, periodEndEpochMs)} (${prompt.length} chars)",
            )
            onDiagnostic("Calling local LiteRT-LM conversation.sendMessage")
            val rawResponse = runCatching {
                session.generate(prompt)
            }.getOrElse { exception ->
                throw IllegalStateException(
                    "LiteRT-LM generation failed for ${granularity.name.lowercase()} ${formatDateRange(periodStartEpochMs, periodEndEpochMs)} " +
                        "with ${documents.size} source document(s) and prompt length ${prompt.length}.",
                    exception,
                )
            }
            onDiagnostic("LiteRT-LM returned ${rawResponse.length} chars")
            val overview = sanitizeGeneratedSummary(rawResponse)
            if (overview.isBlank()) {
                throw IllegalStateException(
                    "LiteRT-LM returned no usable summary text for ${granularity.name.lowercase()} ${formatDateRange(periodStartEpochMs, periodEndEpochMs)}.",
                )
            }
            onDiagnostic("Summary response normalized successfully")
            return AggregateInsight(
                modelLabel = model.label,
                title = defaultTitle(granularity, periodStartEpochMs, periodEndEpochMs),
                overview = overview,
                highlights = emptyList(),
                themes = emptyList(),
            )
        }

        override fun close() {
            session.close()
        }
    }

    private inner class HeuristicSummarizer : PreparedSummarizer {
        override suspend fun summarize(
            documents: List<SemanticDocument>,
            granularity: RollupGranularity,
            periodStartEpochMs: Long,
            periodEndEpochMs: Long,
            onDiagnostic: suspend (String) -> Unit,
        ): AggregateInsight {
            val rangeLabel = formatDateRange(periodStartEpochMs, periodEndEpochMs)
            val overview = buildString {
                append("Summary for $rangeLabel based on ${documents.size} source item(s).")
                val sampleLines = documents
                    .sortedByDescending(SemanticDocument::createdAtEpochMs)
                    .take(3)
                    .mapNotNull { document ->
                        val excerpt = document.body.replace('\n', ' ').trim().take(120).trimEnd()
                        excerpt.takeIf(String::isNotBlank)?.let { line ->
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
        maxTokens: Int,
    ): String {
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.systemDefault())
        val sourceBlock = buildSourceBlock(documents, granularity, formatter, maxTokens)

        return """
            Summarize these notes for ${formatDateRange(periodStartEpochMs, periodEndEpochMs)}.
            Use only the source text.
            Plain text only.
            Write 2 to 4 concise sentences.
            Preserve important events, decisions, follow-ups, and repeated signals.
            Compress repetition aggressively.

            $sourceBlock
        """.trimIndent()
    }

    private fun buildSourceBlock(
        documents: List<SemanticDocument>,
        granularity: RollupGranularity,
        formatter: DateTimeFormatter,
        maxTokens: Int,
    ): String {
        val totalBodyBudget = totalBodyBudget(documents, granularity, maxTokens)
        val perDocumentBudget = (totalBodyBudget / documents.size.coerceAtLeast(1))
            .coerceIn(MIN_SOURCE_BODY_CHARS, MAX_SOURCE_BODY_CHARS)
        var remainingBudget = totalBodyBudget

        return documents.joinToString("\n\n") { document ->
            val bodyLimit = minOf(perDocumentBudget, remainingBudget).coerceAtLeast(MIN_SOURCE_BODY_CHARS)
            val body = sanitizePromptText(document.body, maxChars = bodyLimit)
            val title = sanitizePromptText(document.title, maxChars = 180)
            remainingBudget = (remainingBudget - body.length).coerceAtLeast(0)
            buildString {
                append('[')
                append(formatter.format(Instant.ofEpochMilli(document.createdAtEpochMs)))
                append("] ")
                append(title.ifBlank { "Untitled" })
                append('\n')
                append(body)
            }
        }
    }

    private fun sanitizePromptText(value: String, maxChars: Int = Int.MAX_VALUE): String {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace("\r\n", "\n")
            .replace(CONTROL_CHAR_REGEX, " ")
            .replace(EXCESS_BLANK_LINE_REGEX, "\n\n")
            .trim()
            .take(maxChars)
    }

    private fun sanitizeGeneratedSummary(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace("\r\n", "\n")
            .replace(CONTROL_CHAR_REGEX, " ")
            .replace(EXCESS_BLANK_LINE_REGEX, "\n\n")
            .trim()
    }

    private fun totalBodyBudget(
        documents: List<SemanticDocument>,
        granularity: RollupGranularity,
        maxTokens: Int,
    ): Int {
        val baseBudget = when (granularity) {
            RollupGranularity.DAILY -> 1_200
            RollupGranularity.WEEKLY -> 1_600
            RollupGranularity.MONTHLY -> 2_000
            RollupGranularity.YEARLY -> 2_400
        }
        // Reserve tokens for: instruction preamble (~60 tokens), per-doc timestamps (~6 tokens
        // each), conversation format special tokens (~15 tokens). These are not body text but
        // still consume context window space. Without accounting for them, the total prompt
        // can exceed the compiled model's fixed max sequence length and fail at the JNI layer.
        val promptOverheadTokens = INSTRUCTION_TEMPLATE_TOKENS + (documents.size * PER_DOC_METADATA_TOKENS) + CONVERSATION_SPECIAL_TOKENS
        val promptBudget = ((maxTokens - RESERVED_OUTPUT_TOKENS - promptOverheadTokens).coerceAtLeast(MIN_INPUT_TOKENS) * CHARS_PER_TOKEN_ESTIMATE)
        return minOf(baseBudget, promptBudget)
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

    companion object {
        private val CONTROL_CHAR_REGEX = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]")
        private val EXCESS_BLANK_LINE_REGEX = Regex("\\n{3,}")
        private const val MIN_SOURCE_BODY_CHARS = 180
        private const val MAX_SOURCE_BODY_CHARS = 800
        private const val SUMMARY_MAX_TOKENS = 512
        private const val SUMMARY_TOP_K = 8
        private const val RESERVED_OUTPUT_TOKENS = 96
        private const val MIN_INPUT_TOKENS = 160
        private const val CHARS_PER_TOKEN_ESTIMATE = 4
        // Tokens consumed by fixed prompt structure, not counted in the body budget:
        // ~60 for the instruction block text, ~15 for Gemma conversation format tokens.
        private const val INSTRUCTION_TEMPLATE_TOKENS = 60
        private const val CONVERSATION_SPECIAL_TOKENS = 15
        // Per-document: ISO timestamp prefix (~6 tokens) plus a conservative title allowance
        // (titles are capped at 180 chars but average ~60 chars; 60/4 = ~15 tokens each).
        private const val PER_DOC_METADATA_TOKENS = 21
    }
}
