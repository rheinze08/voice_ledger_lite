package com.voiceledger.lite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import com.voiceledger.lite.data.LedgerDatabase
import com.voiceledger.lite.data.LedgerRepository
import com.voiceledger.lite.data.SettingsStore
import com.voiceledger.lite.ollama.OllamaClient
import com.voiceledger.lite.ui.LedgerMiniApp
import com.voiceledger.lite.ui.LedgerTheme
import com.voiceledger.lite.ui.LedgerViewModel
import com.voiceledger.lite.ui.LedgerViewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = Room.databaseBuilder(
            applicationContext,
            LedgerDatabase::class.java,
            "voice_ledger_lite.db",
        ).fallbackToDestructiveMigration().build()
        val repository = LedgerRepository(database.noteDao(), database.insightDao())
        val settingsStore = SettingsStore(applicationContext)
        val ollamaClient = OllamaClient()
        val factory = LedgerViewModelFactory(repository, settingsStore, ollamaClient)

        setContent {
            LedgerTheme {
                val viewModel: LedgerViewModel = viewModel(factory = factory)
                LedgerMiniApp(viewModel)
            }
        }
    }
}
