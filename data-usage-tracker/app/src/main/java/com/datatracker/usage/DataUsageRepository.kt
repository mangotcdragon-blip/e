package com.datatracker.usage

import android.app.AppOpsManager
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import java.time.ZoneId
import java.time.ZonedDateTime

class DataUsageRepository(private val context: Context) {

    data class UsageSnapshot(
        val allowanceBytes: Long,
        val rolloverBytes: Long,
        val usedBytes: Long,
        val cycleStartMillis: Long,
        val cycleEndMillis: Long
    ) {
        val totalBytes: Long get() = allowanceBytes + rolloverBytes
        val remainingBytes: Long get() = (totalBytes - usedBytes).coerceAtLeast(0)
        val usedFraction: Float
            get() = if (totalBytes <= 0) 0f else (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    }

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun currentSnapshot(prefs: PrefsStore): UsageSnapshot {
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val (current, previous) = CycleCalculator.currentAndPreviousCycle(
            now, prefs.resetDay, prefs.resetHour, prefs.resetMinute
        )
        val usedThisCycle = queryMobileBytes(current.startEpochMillis, current.endEpochMillis)
        val rollover = if (prefs.rolloverEnabled) {
            val usedPreviousCycle = queryMobileBytes(previous.startEpochMillis, previous.endEpochMillis)
            (prefs.allowanceBytes - usedPreviousCycle).coerceAtLeast(0)
        } else {
            0L
        }
        return UsageSnapshot(
            allowanceBytes = prefs.allowanceBytes,
            rolloverBytes = rollover,
            usedBytes = usedThisCycle,
            cycleStartMillis = current.startEpochMillis,
            cycleEndMillis = current.endEpochMillis
        )
    }

    private fun queryMobileBytes(startMillis: Long, endMillis: Long): Long {
        if (endMillis <= startMillis) return 0L
        if (!hasUsageAccess()) return 0L
        return try {
            val statsManager =
                context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
            // Empty subscriberId matches any mobile subscription; apps cannot read the real IMSI
            // without a privileged/carrier permission, and this is the standard workaround.
            val bucket = statsManager.querySummaryForDevice(
                ConnectivityManager.TYPE_MOBILE, "", startMillis, endMillis
            )
            bucket.rxBytes + bucket.txBytes
        } catch (e: Exception) {
            0L
        }
    }
}
