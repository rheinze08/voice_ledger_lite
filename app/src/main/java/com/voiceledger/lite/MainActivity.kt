package com.voiceledger.lite

import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voiceledger.lite.data.LedgerRepository
import com.voiceledger.lite.data.SettingsStore
import com.voiceledger.lite.semantic.AggregationScheduler
import com.voiceledger.lite.semantic.LedgerDatabaseFactory
import com.voiceledger.lite.semantic.LocalAggregationCoordinator
import com.voiceledger.lite.semantic.ModelImportTarget
import com.voiceledger.lite.semantic.ModelStore
import com.voiceledger.lite.ui.LedgerMiniApp
import com.voiceledger.lite.ui.LedgerTheme
import com.voiceledger.lite.ui.LedgerViewModel
import com.voiceledger.lite.ui.LedgerViewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = LedgerDatabaseFactory.open(applicationContext)
        val repository = LedgerRepository(database)
        val settingsStore = SettingsStore(applicationContext)
        val coordinator = LocalAggregationCoordinator(applicationContext, repository, settingsStore)
        val modelStore = ModelStore(applicationContext)
        val factory = LedgerViewModelFactory(
            appContext = applicationContext,
            repository = repository,
            settingsStore = settingsStore,
            coordinator = coordinator,
            modelStore = modelStore,
        )

        if (settingsStore.load().backgroundProcessingEnabled) {
            AggregationScheduler.schedulePeriodic(applicationContext)
        }

        setContent {
            LedgerTheme {
                val viewModel: LedgerViewModel = viewModel(factory = factory)
                val summaryModelPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    uri?.let { viewModel.importModel(ModelImportTarget.SUMMARY, it) }
                }
                val embeddingModelPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    uri?.let { viewModel.importModel(ModelImportTarget.EMBEDDING, it) }
                }
                LedgerMiniApp(
                    viewModel = viewModel,
                    onImportSummaryModel = { summaryModelPicker.launch(arrayOf("*/*")) },
                    onImportEmbeddingModel = { embeddingModelPicker.launch(arrayOf("*/*")) },
                )
            }
        }
    }
}
