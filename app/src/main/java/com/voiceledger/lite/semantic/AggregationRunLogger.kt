package com.voiceledger.lite.semantic

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class AggregationRunLogger(context: Context) {
    private val logDirectory = File(context.filesDir, LOG_DIRECTORY).apply { mkdirs() }
    private val latestLogFile = File(logDirectory, LATEST_LOG_FILE)
    private var sessionLogFile: File? = null

    fun beginRun(isManualTrigger: Boolean, rebuildFromStartDate: Boolean): AggregationLogSnapshot {
        val timestamp = FILE_NAME_FORMATTER.format(Instant.now())
        val mode = when {
            rebuildFromStartDate -> "rebuild"
            isManualTrigger -> "update"
            else -> "scheduled"
        }
        sessionLogFile = File(logDirectory, "aggregation-$timestamp-$mode.log")
        latestLogFile.parentFile?.mkdirs()
        latestLogFile.writeText("")
        append("Run started ($mode)")
        pruneHistory()
        return snapshot()
    }

    fun append(message: String) {
        writeLine(message.trim())
    }

    fun appendThrowable(prefix: String, throwable: Throwable) {
        writeLine(prefix.trim())
        writeRaw(stackTraceString(throwable))
    }

    fun snapshot(maxLines: Int = DEFAULT_TAIL_LINES): AggregationLogSnapshot {
        val path = latestLogFile.absolutePath.takeIf { latestLogFile.exists() }
        val tail = if (latestLogFile.exists()) {
            latestLogFile.readLines().takeLast(maxLines)
        } else {
            emptyList()
        }
        return AggregationLogSnapshot(
            path = path,
            tail = tail,
        )
    }

    private fun writeLine(message: String) {
        if (message.isBlank()) {
            return
        }
        writeRaw("[${LINE_TIMESTAMP_FORMATTER.format(Instant.now())}] $message\n")
    }

    private fun writeRaw(text: String) {
        latestLogFile.appendText(text)
        sessionLogFile?.appendText(text)
    }

    private fun pruneHistory() {
        logDirectory.listFiles { file ->
            file.isFile && file.name.startsWith("aggregation-") && file.name.endsWith(".log")
        }
            ?.sortedByDescending(File::lastModified)
            ?.drop(MAX_HISTORY_FILES)
            ?.forEach(File::delete)
    }

    private fun stackTraceString(throwable: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use { printWriter ->
            throwable.printStackTrace(printWriter)
        }
        return writer.toString()
    }

    companion object {
        private const val LOG_DIRECTORY = "logs/aggregation"
        private const val LATEST_LOG_FILE = "latest.log"
        private const val MAX_HISTORY_FILES = 8
        private const val DEFAULT_TAIL_LINES = 20
        private val FILE_NAME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())
        private val LINE_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
    }
}

data class AggregationLogSnapshot(
    val path: String?,
    val tail: List<String>,
)
