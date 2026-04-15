package com.voiceledger.lite.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.voiceledger.lite.data.LedgerRepository
import com.voiceledger.lite.data.SettingsStore
import com.voiceledger.lite.ollama.OllamaClient

class LedgerViewModelFactory(
    private val repository: LedgerRepository,
    private val settingsStore: SettingsStore,
    private val ollamaClient: OllamaClient,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LedgerViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return LedgerViewModel(repository, settingsStore, ollamaClient) as T
        }
        error("Unknown ViewModel class: ${modelClass.name}")
    }
}
