package com.datatracker.usage

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object UpdateScheduler {
    private const val PERIODIC_WORK_NAME = "usage_update_periodic"

    /** 15 minutes is WorkManager's minimum periodic interval; there's no lighter-weight way to
     * get more frequent background updates without a persistent foreground service. */
    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<UsageUpdateWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    fun refreshNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<UsageUpdateWorker>().build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
