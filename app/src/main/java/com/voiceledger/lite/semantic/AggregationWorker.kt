package com.voiceledger.lite.semantic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteCoroutineWorker
import com.voiceledger.lite.data.LedgerDatabase
import com.voiceledger.lite.data.LedgerRepository
import com.voiceledger.lite.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AggregationWorker(
    appContext: Context,
    params: WorkerParameters,
) : RemoteCoroutineWorker(appContext, params) {
    override suspend fun doRemoteWork(): Result = withContext(Dispatchers.IO) {
        val database = LedgerDatabaseFactory.open(applicationContext)
        val repository = LedgerRepository(database)
        val settingsStore = SettingsStore(applicationContext)
        val isManualTrigger = AggregationScheduler.isManualTrigger(inputData)
        val rebuildFromStartDate = AggregationScheduler.isRebuildFromStart(inputData)

        val settings = settingsStore.load()
        if (!isManualTrigger && !settings.backgroundProcessingEnabled) {
            return@withContext Result.success()
        }

        return@withContext aggregationMutex.withLock {
            runCatching {
                if (isManualTrigger) {
                    val initialMessage = if (rebuildFromStartDate) {
                        "Preparing full rebuild"
                    } else {
                        "Preparing summary update"
                    }
                    setProgress(AggregationScheduler.progressData(initialMessage, rebuildFromStartDate))
                    setForegroundAsync(createForegroundInfo(initialMessage, rebuildFromStartDate)).get()
                }
                val message = LocalAggregationCoordinator(applicationContext, repository, settingsStore)
                    .runAggregation(rebuildFromStartDate) { progressMessage ->
                        if (isManualTrigger) {
                            setProgress(AggregationScheduler.progressData(progressMessage, rebuildFromStartDate))
                            setForegroundAsync(createForegroundInfo(progressMessage, rebuildFromStartDate)).get()
                        }
                    }
                if (isManualTrigger) {
                    Result.success(AggregationScheduler.successData(message, rebuildFromStartDate))
                } else {
                    AggregationScheduler.scheduleNextFromWorker(applicationContext, settingsStore.load())
                    Result.success()
                }
            }.getOrElse { exception ->
                if (exception is CancellationException) throw exception
                val cause = (exception as? java.util.concurrent.ExecutionException)?.cause ?: exception
                if (cause is CancellationException) throw cause
                val message = cause.message ?: exception.message ?: "Summary rebuild failed."
                if (isManualTrigger) {
                    Result.failure(AggregationScheduler.failureData(message, rebuildFromStartDate))
                } else {
                    Result.retry()
                }
            }
        }
    }

    private fun createForegroundInfo(message: String, rebuildFromStartDate: Boolean): ForegroundInfo {
        createNotificationChannelIfNeeded()
        val title = if (rebuildFromStartDate) {
            "Rebuilding Voice Ledger summaries"
        } else {
            "Updating Voice Ledger summaries"
        }
        // PendingIntent is intentionally omitted: ForegroundInfo is marshalled to bytes when
        // crossing from the :aggregation process to the main process, and Parcel.marshall()
        // rejects Parcels that contain Binder objects (which PendingIntent holds internally).
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) {
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Summary rebuilds",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Builds local summaries and semantic indexes for Voice Ledger Lite."
            },
        )
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "voice_ledger_aggregation"
        private const val NOTIFICATION_ID = 1102
        private val aggregationMutex = Mutex()
    }
}
