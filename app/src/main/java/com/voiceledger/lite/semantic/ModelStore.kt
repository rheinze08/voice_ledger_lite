package com.voiceledger.lite.semantic

import android.content.Context
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ModelImportTarget {
    SUMMARY,
    EMBEDDING,
}

class ModelStore(private val context: Context) {
    suspend fun importModel(uri: Uri, target: ModelImportTarget): String = withContext(Dispatchers.IO) {
        val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        val fileName = when (target) {
            ModelImportTarget.SUMMARY -> "summary-model.task"
            ModelImportTarget.EMBEDDING -> "embedding-model.tflite"
        }
        val destination = File(modelsDir, fileName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: error("Unable to open the selected model file.")
        destination.absolutePath
    }
}
