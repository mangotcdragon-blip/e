package com.datatracker.usage

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class UsageUpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val prefs = PrefsStore(applicationContext)
        if (!prefs.isConfigured) return Result.success()

        val repo = DataUsageRepository(applicationContext)
        if (!repo.hasUsageAccess()) return Result.success()

        val snapshot = repo.currentSnapshot(prefs)
        NotificationHelper.show(applicationContext, snapshot)
        return Result.success()
    }
}
