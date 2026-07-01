package com.example.accellogger

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object LogSyncScheduler {

    fun enable(context: Context) {
        ensurePeriodic(context)
        enqueueImmediate(context)
    }

    fun enqueueImmediate(context: Context) {
        if (!AutoSyncPreferences(context).loadConfig().isEnabled) {
            return
        }

        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_SYNC_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LogSyncWorker>()
                .setConstraints(syncConstraints())
                .build(),
        )
    }

    fun disable(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_SYNC_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_SYNC_WORK_NAME)
    }

    private fun ensurePeriodic(context: Context) {
        if (!AutoSyncPreferences(context).loadConfig().isEnabled) {
            return
        }

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LogSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(syncConstraints())
                .build(),
        )
    }

    private fun syncConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
    }

    private const val IMMEDIATE_SYNC_WORK_NAME = "accellogger-log-sync-immediate"
    private const val PERIODIC_SYNC_WORK_NAME = "accellogger-log-sync-periodic"
}