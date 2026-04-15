package com.voiceledger.lite.data

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): OllamaSettings {
        return OllamaSettings(
            baseUrl = prefs.getString(KEY_BASE_URL, null) ?: DEFAULTS.baseUrl,
            model = prefs.getString(KEY_MODEL, null) ?: DEFAULTS.model,
            windowDays = prefs.getInt(KEY_WINDOW_DAYS, DEFAULTS.windowDays),
            noteLimit = prefs.getInt(KEY_NOTE_LIMIT, DEFAULTS.noteLimit),
            timeoutMs = prefs.getInt(KEY_TIMEOUT_MS, DEFAULTS.timeoutMs),
        ).normalized()
    }

    fun save(settings: OllamaSettings) {
        val normalized = settings.normalized()
        prefs.edit()
            .putString(KEY_BASE_URL, normalized.baseUrl)
            .putString(KEY_MODEL, normalized.model)
            .putInt(KEY_WINDOW_DAYS, normalized.windowDays)
            .putInt(KEY_NOTE_LIMIT, normalized.noteLimit)
            .putInt(KEY_TIMEOUT_MS, normalized.timeoutMs)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "voice_ledger_lite_settings"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_WINDOW_DAYS = "window_days"
        private const val KEY_NOTE_LIMIT = "note_limit"
        private const val KEY_TIMEOUT_MS = "timeout_ms"

        private val DEFAULTS = OllamaSettings()
    }
}
