package com.voiceledger.lite.ollama

import kotlinx.serialization.Serializable

@Serializable
data class ThemeBucket(
    val label: String,
    val summary: String,
    val noteIds: List<Long>,
)

@Serializable
data class JournalInsight(
    val overview: String,
    val highlights: List<String>,
    val themes: List<ThemeBucket>,
)

data class InsightSnapshot(
    val kind: String,
    val generatedAtEpochMs: Long,
    val model: String,
    val noteCount: Int,
    val windowDays: Int,
    val overview: String,
    val highlights: List<String>,
    val themes: List<ThemeBucket>,
    val noteIds: List<Long>,
)
