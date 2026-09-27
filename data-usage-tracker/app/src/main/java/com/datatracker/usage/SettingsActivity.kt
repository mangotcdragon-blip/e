package com.datatracker.usage

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.datatracker.usage.databinding.ActivitySettingsBinding
import java.time.ZoneId
import java.time.ZonedDateTime

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: PrefsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsStore(this)
        loadCurrentValues()

        binding.saveBtn.setOnClickListener { save() }
        binding.grantAccessBtn2.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    private fun loadCurrentValues() {
        val allowanceGb = ByteFormat.toGb(prefs.allowanceBytes)
        binding.allowanceInput.setText(trimTrailingZero(allowanceGb))
        binding.resetDayInput.setText(prefs.resetDay.toString())
        binding.resetHourInput.setText(prefs.resetHour.toString().padStart(2, '0'))
        binding.resetMinuteInput.setText(prefs.resetMinute.toString().padStart(2, '0'))
        binding.rolloverSwitch.isChecked = prefs.rolloverEnabled
        binding.rolloverBalanceInput.setText(trimTrailingZero(ByteFormat.toGb(prefs.carriedRolloverBytes)))
    }

    private fun trimTrailingZero(value: Double): String {
        return if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    }

    private fun save() {
        val allowanceGb = binding.allowanceInput.text.toString().toDoubleOrNull()
        val resetDay = binding.resetDayInput.text.toString().toIntOrNull()
        val resetHour = binding.resetHourInput.text.toString().toIntOrNull() ?: 0
        val resetMinute = binding.resetMinuteInput.text.toString().toIntOrNull() ?: 0
        val rolloverBalanceGb = binding.rolloverBalanceInput.text.toString().toDoubleOrNull()

        if (allowanceGb == null || allowanceGb <= 0) {
            binding.allowanceInput.error = "Enter your monthly data allowance"
            return
        }
        if (resetDay == null || resetDay !in 1..31) {
            binding.resetDayInput.error = "Enter a day between 1 and 31"
            return
        }
        if (resetHour !in 0..23) {
            binding.resetHourInput.error = "0–23"
            return
        }
        if (resetMinute !in 0..59) {
            binding.resetMinuteInput.error = "0–59"
            return
        }
        if (rolloverBalanceGb != null && rolloverBalanceGb < 0) {
            binding.rolloverBalanceInput.error = "Can't be negative"
            return
        }

        if (!prefs.isConfigured) {
            prefs.firstConfiguredAtMillis = System.currentTimeMillis()
        }

        prefs.allowanceBytes = ByteFormat.gbToBytes(allowanceGb)
        prefs.resetDay = resetDay
        prefs.resetHour = resetHour
        prefs.resetMinute = resetMinute
        prefs.rolloverEnabled = binding.rolloverSwitch.isChecked
        prefs.isConfigured = true

        if (rolloverBalanceGb != null) {
            val now = ZonedDateTime.now(ZoneId.systemDefault())
            val (current, _) = CycleCalculator.currentAndPreviousCycle(now, resetDay, resetHour, resetMinute)
            prefs.overrideCarriedRollover(ByteFormat.gbToBytes(rolloverBalanceGb), current.startEpochMillis)
        }

        UpdateScheduler.refreshNow(this)
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
