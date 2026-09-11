package com.wifimouse.app

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.Slider
import com.google.android.material.materialswitch.MaterialSwitch
import com.wifimouse.app.databinding.ActivitySettingsBinding
import com.wifimouse.app.net.Discovery
import com.wifimouse.app.net.Protocol

/** Connection details and feel. Every change is saved as it is made. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = Settings(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.hostInput.setText(settings.host)
        binding.portInput.setText(settings.port.toString())
        binding.tokenInput.setText(settings.token)

        binding.findButton.setOnClickListener { discover() }

        bindSlider(binding.sensitivitySlider, settings.sensitivity) { value ->
            settings.sensitivity = value
            binding.sensitivityLabel.text = getString(R.string.settings_sensitivity, value)
        }
        bindSlider(binding.scrollSlider, settings.scrollSpeed) { value ->
            settings.scrollSpeed = value
            binding.scrollLabel.text = getString(R.string.settings_scroll_speed, value)
        }

        bindSwitch(binding.accelerationSwitch, settings.acceleration) { settings.acceleration = it }
        bindSwitch(binding.naturalScrollSwitch, settings.naturalScroll) { settings.naturalScroll = it }
        bindSwitch(binding.tapToClickSwitch, settings.tapToClick) { settings.tapToClick = it }
        bindSwitch(binding.hapticsSwitch, settings.haptics) { settings.haptics = it }
        bindSwitch(binding.keepAwakeSwitch, settings.keepScreenOn) { settings.keepScreenOn = it }
        bindSwitch(binding.autoConnectSwitch, settings.autoConnect) { settings.autoConnect = it }
        bindSwitch(binding.resendSwitch, settings.resendClicks) { settings.resendClicks = it }
    }

    override fun onPause() {
        super.onPause()
        saveTextFields()
    }

    /**
     * @param showErrors whether a bad port may raise a dialog. It may not while
     *   the screen is going away, which is where onPause() calls this from.
     */
    private fun saveTextFields(showErrors: Boolean = false) {
        settings.host = binding.hostInput.text?.toString()?.trim().orEmpty()
        settings.token = binding.tokenInput.text?.toString()?.trim().orEmpty()

        val typed = binding.portInput.text?.toString()?.trim().orEmpty()
        val port = typed.toIntOrNull()
        if (port != null && port in 1..65535) {
            settings.port = port
        } else {
            // Keep the stored port rather than silently writing a broken one.
            binding.portInput.setText(settings.port.toString())
            if (showErrors && typed.isNotEmpty()) {
                AlertDialog.Builder(this)
                    .setMessage(R.string.invalid_port)
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
        }
    }

    private fun bindSlider(slider: Slider, initial: Float, onChange: (Float) -> Unit) {
        // A Slider rejects any value that is not exactly on a step, so snap
        // whatever was stored onto the nearest one.
        val clamped = initial.coerceIn(slider.valueFrom, slider.valueTo)
        val steps = Math.round((clamped - slider.valueFrom) / slider.stepSize)
        slider.value = (slider.valueFrom + steps * slider.stepSize)
            .coerceIn(slider.valueFrom, slider.valueTo)
        onChange(slider.value)
        slider.addOnChangeListener { _, value, _ -> onChange(value) }
    }

    private fun bindSwitch(view: MaterialSwitch, initial: Boolean, onChange: (Boolean) -> Unit) {
        view.isChecked = initial
        view.setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun discover() {
        saveTextFields(showErrors = true)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.find_pc)
            .setMessage(R.string.searching)
            .show()

        Discovery.search(settings.port) { servers ->
            // The search outlives the screen if the user backs out mid-way.
            if (isFinishing || isDestroyed) return@search
            dialog.dismiss()
            if (servers.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.find_pc)
                    .setMessage(getString(R.string.none_found, settings.port))
                    .setPositiveButton(R.string.ok, null)
                    .show()
                return@search
            }
            val labels = servers
                .map { getString(R.string.server_entry, it.name, it.host) }
                .toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(R.string.choose_pc)
                .setItems(labels) { _, index -> useServer(servers[index]) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun useServer(server: Protocol.ServerInfo) {
        settings.host = server.host
        settings.port = server.port
        binding.hostInput.setText(server.host)
        binding.portInput.setText(server.port.toString())
    }
}
