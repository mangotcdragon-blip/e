package com.datatracker.usage

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.datatracker.usage.databinding.ActivityMainBinding
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: DataUsageRepository
    private lateinit var prefs: PrefsStore
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dateFormatter = DateTimeFormatter.ofPattern("MMM d")

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = DataUsageRepository(this)
        prefs = PrefsStore(this)

        binding.refreshBtn.setOnClickListener { refresh() }
        binding.settingsBtn.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.openSetupBtn.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.grantAccessBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        maybeRequestNotificationPermission()
        UpdateScheduler.ensureScheduled(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun refresh() {
        val hasAccess = repo.hasUsageAccess()
        binding.permissionBanner.visibility = if (hasAccess) View.GONE else View.VISIBLE
        binding.setupBanner.visibility = if (prefs.isConfigured) View.GONE else View.VISIBLE
        binding.usageCard.visibility = if (hasAccess && prefs.isConfigured) View.VISIBLE else View.GONE

        if (!hasAccess || !prefs.isConfigured) return

        Thread {
            val snapshot = repo.currentSnapshot(prefs)
            mainHandler.post { render(snapshot) }
        }.start()

        UpdateScheduler.refreshNow(this)
    }

    private fun render(snapshot: DataUsageRepository.UsageSnapshot) {
        binding.remainingText.text = getString(
            R.string.remaining_of,
            ByteFormat.format(snapshot.remainingBytes),
            ByteFormat.format(snapshot.totalBytes)
        )
        binding.usedText.text = getString(R.string.used_label, ByteFormat.format(snapshot.usedBytes))
        binding.usageProgress.progress = (snapshot.usedFraction * 100).toInt()

        val start = Instant.ofEpochMilli(snapshot.cycleStartMillis).atZone(ZoneId.systemDefault()).format(dateFormatter)
        val end = Instant.ofEpochMilli(snapshot.cycleEndMillis).atZone(ZoneId.systemDefault()).format(dateFormatter)
        binding.cycleRangeText.text = getString(R.string.cycle_range, start, end)

        if (snapshot.rolloverBytes > 0) {
            binding.rolloverText.visibility = View.VISIBLE
            binding.rolloverText.text = getString(R.string.rollover_note, ByteFormat.format(snapshot.rolloverBytes))
        } else {
            binding.rolloverText.visibility = View.GONE
        }
    }
}
