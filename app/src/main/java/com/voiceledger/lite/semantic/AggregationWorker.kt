package com.voiceledger.lite.semantic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.voiceledger.lite.data.LedgerDatabase
import com.voiceledger.lite.data.LedgerRepository
import com.voiceledger.lite.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AggregationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val database = LedgerDatabaseFactory.open(applicationContext)
        val repository = LedgerRepository(database)
        val settingsStore = SettingsStore(applicationContext)
        val isManualTrigger = AggregationScheduler.isManualTrigger(inputData)
        val rebuildFromStartDate = AggregationScheduler.isRebuildFromStart(inputData)

        if (!isManualTrigger && !settingsStore.load().backgroundProcessingEnabled) {
            return@withContext Result.success()
        }

        if (isManualTrigger) {
            val initialMessage = if (rebuildFromStartDate) {
                "Preparing full rebuild"
            } else {
                "Preparing summary update"
            }
            setProgress(AggregationScheduler.progressData(initialMessage, rebuildFromStartDate))
            setForeground(createForegroundInfo(initialMessage, rebuildFromStartDate))
        }

        return@withContext aggregationMutex.withLock {
            runCatching {
                val message = LocalAggregationCoordinator(applicationContext, repository, settingsStore)
                    .runAggregation(rebuildFromStartDate) { progressMessage ->
                        if (isManualTrigger) {
                            setProgress(AggregationScheduler.progressData(progressMessage, rebuildFromStartDate))
                            setForeground(createForegroundInfo(progressMessage, rebuildFromStartDate))
                        }
                    }
                if (isManualTrigger) {
                    Result.success(AggregationScheduler.successData(message, rebuildFromStartDate))
                } else {
                    Result.success()
                }
            }.getOrElse { exception ->
                val message = exception.message ?: "Summary rebuild failed."
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
        val launchIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                applicationContext,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val title = if (rebuildFromStartDate) {
            "Rebuilding Voice Ledger summaries"
        } else {
            "Updating Voice Ledger summaries"
        }
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(pendingIntent)
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
