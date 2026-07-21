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
        val rolloverApplied: Boolean,
        val usedBytes: Long,
        val cycleStartMillis: Long,
        val cycleEndMillis: Long,
        val previousCycleStartMillis: Long,
        val previousCycleEndMillis: Long,
        val previousUsedBytes: Long,
        /** False if a usage query threw despite having usage access -- the numbers above may
         * be incomplete/zero rather than reflecting a genuine zero-usage cycle. */
        val usageDataAvailable: Boolean
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
        val rolloverEligible = RolloverPolicy.shouldApplyRollover(
            prefs.rolloverEnabled, prefs.firstConfiguredAtMillis, previous.startEpochMillis
        )
        val previousUsed = if (rolloverEligible) {
            queryMobileBytes(previous.startEpochMillis, previous.endEpochMillis)
        } else {
            null
        }
        // Only actually apply rollover once we have a *real* reading for the previous cycle --
        // falling back to "assume 0 used" when the query fails would credit a full extra
        // allowance's worth of rollover, the same bug this whole flow exists to avoid.
        val rolloverApplied = rolloverEligible && previousUsed != null
        val rollover = if (rolloverApplied) {
            (prefs.allowanceBytes - previousUsed!!).coerceAtLeast(0)
        } else {
            0L
        }

        return UsageSnapshot(
            allowanceBytes = prefs.allowanceBytes,
            rolloverBytes = rollover,
            rolloverApplied = rolloverApplied,
            usedBytes = usedThisCycle ?: 0L,
            cycleStartMillis = current.startEpochMillis,
            cycleEndMillis = current.endEpochMillis,
            previousCycleStartMillis = previous.startEpochMillis,
            previousCycleEndMillis = previous.endEpochMillis,
            previousUsedBytes = previousUsed ?: 0L,
            usageDataAvailable = usedThisCycle != null && (!rolloverEligible || previousUsed != null)
        )
    }

    /** Returns null (rather than 0) when the reading genuinely couldn't be obtained, so callers
     * can distinguish "no usage happened" from "we don't actually know." */
    private fun queryMobileBytes(startMillis: Long, endMillis: Long): Long? {
        if (endMillis <= startMillis) return 0L
        if (!hasUsageAccess()) return null
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
            null
        }
    }
}
