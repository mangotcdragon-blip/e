package com.datatracker.usage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object NotificationHelper {
    const val CHANNEL_ID = "data_usage_channel"
    const val NOTIFICATION_ID = 1001

    private val dateFormatter = DateTimeFormatter.ofPattern("MMM d")

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun show(context: Context, snapshot: DataUsageRepository.UsageSnapshot) {
        ensureChannel(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ActivityCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val openIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val remaining = ByteFormat.format(snapshot.remainingBytes)
        val total = ByteFormat.format(snapshot.totalBytes)
        val cycleStart = java.time.Instant.ofEpochMilli(snapshot.cycleStartMillis)
            .atZone(ZoneId.systemDefault()).format(dateFormatter)
        val today = java.time.Instant.ofEpochMilli(snapshot.cycleEndMillis)
            .atZone(ZoneId.systemDefault()).format(dateFormatter)

        val usedPercent = (snapshot.usedFraction * 100).toInt().coerceIn(0, 100)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_data)
            .setContentTitle(context.getString(R.string.remaining_of, remaining, total))
            .setContentText("Since $cycleStart · as of $today")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, usedPercent, false)
            .setContentIntent(pendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
}
