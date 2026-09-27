package com.datatracker.usage

import android.app.AppOpsManager
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import java.time.Instant
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
        /** Whether [previousUsedBytes] reflects a real reading -- purely informational display,
         * separate from whether rollover itself was applied (see [RolloverLedger]). */
        val previousUsageAvailable: Boolean,
        /** False if this cycle's own usage query threw despite having usage access -- [usedBytes]
         * may be incomplete/zero rather than reflecting genuine zero usage. */
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

        // Purely informational: how much was used in the single cycle right before this one,
        // shown alongside the running rollover balance so it can be sanity-checked. This does
        // NOT drive the rollover total below -- that's the compounding ledger.
        val previousUsed = if (prefs.rolloverEnabled) {
            queryMobileBytes(previous.startEpochMillis, previous.endEpochMillis)
        } else {
            null
        }

        if (prefs.lastProcessedCycleStartMillis <= 0L) {
            // First-ever run: bootstrap the ledger from just the one previous cycle, since older
            // cycles generally aren't reliably queryable on a fresh install. From here on,
            // advance() takes over and properly compounds across however many cycles pass.
            val bootstrapRollover = if (prefs.rolloverEnabled && previousUsed != null) {
                (prefs.allowanceBytes - previousUsed).coerceAtLeast(0)
            } else {
                0L
            }
            prefs.carriedRolloverBytes = bootstrapRollover
            prefs.lastProcessedCycleStartMillis = current.startEpochMillis
        } else {
            val result = RolloverLedger.advance(
                lastProcessedCycleStartMillis = prefs.lastProcessedCycleStartMillis,
                currentCycleStartMillis = current.startEpochMillis,
                allowanceBytes = prefs.allowanceBytes,
                rolloverEnabled = prefs.rolloverEnabled,
                carriedRolloverBytes = prefs.carriedRolloverBytes,
                nextCycleBoundary = { afterMillis ->
                    val afterZdt = Instant.ofEpochMilli(afterMillis).atZone(now.zone)
                    CycleCalculator.nextCycleStart(afterZdt, prefs.resetDay, prefs.resetHour, prefs.resetMinute)
                        .toInstant().toEpochMilli()
                },
                queryUsedBytes = ::queryMobileBytes
            )
            prefs.carriedRolloverBytes = result.carriedRolloverBytes
            prefs.lastProcessedCycleStartMillis = result.lastProcessedCycleStartMillis
        }

        val rolloverBytes = prefs.carriedRolloverBytes
        val rolloverApplied = prefs.rolloverEnabled && rolloverBytes > 0

        return UsageSnapshot(
            allowanceBytes = prefs.allowanceBytes,
            rolloverBytes = rolloverBytes,
            rolloverApplied = rolloverApplied,
            usedBytes = usedThisCycle ?: 0L,
            cycleStartMillis = current.startEpochMillis,
            cycleEndMillis = current.endEpochMillis,
            previousCycleStartMillis = previous.startEpochMillis,
            previousCycleEndMillis = previous.endEpochMillis,
            previousUsedBytes = previousUsed ?: 0L,
            previousUsageAvailable = previousUsed != null,
            usageDataAvailable = usedThisCycle != null
        )
    }

    /** Returns null (rather than 0) when the reading genuinely couldn't be obtained, so callers
     * can distinguish "no usage happened" from "we don't actually know." */
    private fun queryMobileBytes(startMillis: Long, endMillis: Long): Long? {
        if (endMillis <= startMillis) return 0L
        if (!hasUsageAccess()) return null

        // Apps with just "Usage access" (not carrier/system-privileged) cannot read a specific
        // SIM's real subscriber ID (IMSI) on Android 10+, so there's no way to scope this query
        // to one SIM on a dual-SIM device. Passing null is the documented way to get *combined*
        // mobile data across all active subscriptions; an empty string is a different value that
        // does not reliably mean the same thing and produced wrong/missing totals on dual-SIM
        // devices. Kept as a fallback in case some OS version rejects null outright.
        return try {
            val statsManager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
            try {
                queryBytes(statsManager, startMillis, endMillis, subscriberId = null)
            } catch (e: Exception) {
                queryBytes(statsManager, startMillis, endMillis, subscriberId = "")
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun queryBytes(
        statsManager: NetworkStatsManager,
        startMillis: Long,
        endMillis: Long,
        subscriberId: String?
    ): Long {
        val bucket = statsManager.querySummaryForDevice(
            ConnectivityManager.TYPE_MOBILE, subscriberId, startMillis, endMillis
        )
        return bucket.rxBytes + bucket.txBytes
    }
}
