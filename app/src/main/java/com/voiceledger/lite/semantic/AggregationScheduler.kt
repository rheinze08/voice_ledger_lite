package com.voiceledger.lite.semantic

import android.content.Context
import android.content.ComponentName
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.multiprocess.RemoteListenableWorker
import com.voiceledger.lite.data.LocalAiSettings
import com.voiceledger.lite.data.normalizeBackgroundProcessingTime
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow

object AggregationScheduler {
    private const val PERIODIC_WORK_NAME = "voice-ledger-periodic-aggregation"
    private const val SCHEDULED_WORK_NAME = "voice-ledger-scheduled-aggregation"
    private const val IMMEDIATE_WORK_NAME = "voice-ledger-immediate-aggregation"
    private const val KEY_MANUAL_TRIGGER = "manual_trigger"
    private const val KEY_REBUILD_FROM_START = "rebuild_from_start"
    private const val KEY_PROGRESS_MESSAGE = "progress_message"
    private const val KEY_RESULT_MESSAGE = "result_message"
    private const val KEY_ERROR_MESSAGE = "error_message"

    fun scheduleDaily(context: Context, settings: LocalAiSettings) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()
        val request = OneTimeWorkRequestBuilder<AggregationWorker>()
            .setInputData(remoteInputData(context, manualTrigger = false, rebuildFromStartDate = false))
            .setConstraints(constraints)
            .setInitialDelay(nextScheduledDelay(settings.backgroundProcessingTime))
            .build()
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
        workManager.enqueueUniqueWork(
            SCHEDULED_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun scheduleNextFromWorker(context: Context, settings: LocalAiSettings) {
        if (!settings.backgroundProcessingEnabled) {
            cancelScheduled(context)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()
        val request = OneTimeWorkRequestBuilder<AggregationWorker>()
            .setInputData(remoteInputData(context, manualTrigger = false, rebuildFromStartDate = false))
            .setConstraints(constraints)
            .setInitialDelay(nextScheduledDelay(settings.backgroundProcessingTime))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            SCHEDULED_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun enqueueImmediate(context: Context, rebuildFromStartDate: Boolean) {
        val request = OneTimeWorkRequestBuilder<AggregationWorker>()
            .setInputData(remoteInputData(context, manualTrigger = true, rebuildFromStartDate = rebuildFromStartDate))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun immediateWorkInfosFlow(context: Context): Flow<List<WorkInfo>> {
        return WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(IMMEDIATE_WORK_NAME)
    }

    fun isImmediateActive(workInfos: List<WorkInfo>): Boolean {
        return mostRelevantImmediateWork(workInfos)?.state.let { state ->
            state == WorkInfo.State.ENQUEUED ||
                state == WorkInfo.State.BLOCKED ||
                state == WorkInfo.State.RUNNING
        }
    }

    fun immediateWorkIsRebuild(workInfos: List<WorkInfo>): Boolean {
        val work = mostRelevantImmediateWork(workInfos)
        return work?.progress?.getBoolean(KEY_REBUILD_FROM_START, false)
            ?: work?.outputData?.getBoolean(KEY_REBUILD_FROM_START, false)
            ?: false
    }

    fun immediateProgressMessage(workInfos: List<WorkInfo>): String? {
        val work = mostRelevantImmediateWork(workInfos) ?: return null
        return work.progress.getString(KEY_PROGRESS_MESSAGE)
            ?: work.outputData.getString(KEY_PROGRESS_MESSAGE)
    }

    fun immediateTerminalResult(workInfos: List<WorkInfo>): AggregationImmediateResult? {
        val work = mostRelevantImmediateWork(workInfos) ?: return null
        if (work.state == WorkInfo.State.ENQUEUED || work.state == WorkInfo.State.BLOCKED || work.state == WorkInfo.State.RUNNING) {
            return null
        }
        val message = when (work.state) {
            WorkInfo.State.SUCCEEDED -> work.outputData.getString(KEY_RESULT_MESSAGE)
            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> work.outputData.getString(KEY_ERROR_MESSAGE)
            else -> null
        }
        return AggregationImmediateResult(
            workId = work.id.toString(),
            state = work.state,
            message = message,
        )
    }

    fun cancelScheduled(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
        workManager.cancelUniqueWork(SCHEDULED_WORK_NAME)
    }

    fun inputData(manualTrigger: Boolean, rebuildFromStartDate: Boolean): Data {
        return workDataOf(
            KEY_MANUAL_TRIGGER to manualTrigger,
            KEY_REBUILD_FROM_START to rebuildFromStartDate,
        )
    }

    fun progressData(message: String, rebuildFromStartDate: Boolean): Data {
        return workDataOf(
            KEY_PROGRESS_MESSAGE to message,
            KEY_REBUILD_FROM_START to rebuildFromStartDate,
        )
    }

    fun successData(message: String, rebuildFromStartDate: Boolean): Data {
        return workDataOf(
            KEY_RESULT_MESSAGE to message,
            KEY_REBUILD_FROM_START to rebuildFromStartDate,
        )
    }

    fun failureData(message: String, rebuildFromStartDate: Boolean): Data {
        return workDataOf(
            KEY_ERROR_MESSAGE to message,
            KEY_REBUILD_FROM_START to rebuildFromStartDate,
        )
    }

    fun isManualTrigger(params: Data): Boolean = params.getBoolean(KEY_MANUAL_TRIGGER, false)

    fun isRebuildFromStart(params: Data): Boolean = params.getBoolean(KEY_REBUILD_FROM_START, false)

    private fun remoteInputData(
        context: Context,
        manualTrigger: Boolean,
        rebuildFromStartDate: Boolean,
    ): Data {
        val componentName = ComponentName(context, androidx.work.multiprocess.RemoteWorkerService::class.java)
        return Data.Builder()
            .putAll(inputData(manualTrigger = manualTrigger, rebuildFromStartDate = rebuildFromStartDate))
            .putString(RemoteListenableWorker.ARGUMENT_PACKAGE_NAME, componentName.packageName)
            .putString(RemoteListenableWorker.ARGUMENT_CLASS_NAME, componentName.className)
            .build()
    }

    private fun mostRelevantImmediateWork(workInfos: List<WorkInfo>): WorkInfo? {
        return workInfos
            .sortedWith(
                compareByDescending<WorkInfo> { it.state.sortOrder() }
                    .thenByDescending { it.runAttemptCount }
            )
            .firstOrNull()
    }

    private fun nextScheduledDelay(timeString: String): Duration {
        val now = LocalDateTime.now()
        val scheduledTime = LocalTime.parse(normalizeBackgroundProcessingTime(timeString))
        var nextRun = now.toLocalDate().atTime(scheduledTime)
        if (!nextRun.isAfter(now)) {
            nextRun = nextRun.plusDays(1)
        }
        return Duration.between(now, nextRun)
    }
}

data class AggregationImmediateResult(
    val workId: String,
    val state: WorkInfo.State,
    val message: String?,
)

private fun WorkInfo.State.sortOrder(): Int {
    return when (this) {
        WorkInfo.State.RUNNING -> 5
        WorkInfo.State.ENQUEUED -> 4
        WorkInfo.State.BLOCKED -> 3
        WorkInfo.State.FAILED -> 2
        WorkInfo.State.SUCCEEDED -> 1
        WorkInfo.State.CANCELLED -> 0
    }
}
