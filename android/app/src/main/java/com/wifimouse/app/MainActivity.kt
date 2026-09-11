package com.wifimouse.app

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.wifimouse.app.databinding.ActivityMainBinding
import com.wifimouse.app.net.Discovery
import com.wifimouse.app.net.MouseClient
import com.wifimouse.app.net.Protocol
import com.wifimouse.app.ui.TouchpadView

class MainActivity : AppCompatActivity(), TouchpadView.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: Settings
    private val client = MouseClient()

    /** Buttons the user is physically holding, so nothing is left stuck down. */
    private val heldButtons = mutableSetOf<Char>()

    /** Mirror of the text field, used to turn edits into keystrokes. */
    private var typedSoFar = ""
    private var suppressTextWatcher = false

    private var connecting = false

    private val copies: Int get() = if (settings.resendClicks) 2 else 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        settings = Settings(this)

        binding.touchpad.listener = this
        binding.touchpad.hintColor = ContextCompat.getColor(this, R.color.touchpad_hint)
        binding.touchpad.touchColor = ContextCompat.getColor(this, R.color.touchpad_touch)

        binding.connectButton.setOnClickListener {
            if (client.isConnected || connecting) disconnect() else connect()
        }

        bindMouseButton(binding.leftButton, 'l')
        bindMouseButton(binding.middleButton, 'm')
        bindMouseButton(binding.rightButton, 'r')

        binding.keyboardButton.setOnClickListener { toggleKeyboard() }
        bindKey(binding.keyEsc, "esc")
        bindKey(binding.keyTab, "tab")
        bindKey(binding.keyLeft, "left")
        bindKey(binding.keyUp, "up")
        bindKey(binding.keyDown, "down")
        bindKey(binding.keyRight, "right")
        bindKey(binding.keyBackspace, "backspace")
        binding.keyEnter.setOnClickListener {
            client.key("enter", copies)
            resetTypedText()
        }

        binding.keyInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!suppressTextWatcher) sendTextDiff(s?.toString() ?: "")
            }
        })
        binding.keyInput.setOnEditorActionListener { _, _, _ ->
            client.key("enter", copies)
            resetTypedText()
            true
        }

        client.onState = ::render
        render(MouseClient.State.Idle)
    }

    override fun onResume() {
        super.onResume()
        applySettings()
        if (settings.autoConnect && settings.isConfigured && !client.isConnected) connect()
    }

    override fun onPause() {
        super.onPause()
        // Leaving the app releases anything held, so the desktop never gets
        // stuck mid-drag.
        releaseEverything()
        client.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        client.stop()
    }

    // -- menu -------------------------------------------------------------- #

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_find -> {
            discover()
            true
        }

        R.id.action_gestures -> {
            AlertDialog.Builder(this)
                .setTitle(R.string.gestures)
                .setMessage(R.string.gesture_guide)
                .setPositiveButton(R.string.close, null)
                .show()
            true
        }

        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    // -- connection -------------------------------------------------------- #

    private fun connect() {
        if (!settings.isConfigured) {
            discover()
            return
        }
        applySettings()
        client.start(settings.host, settings.port, settings.token)
    }

    private fun disconnect() {
        releaseEverything()
        client.stop()
    }

    private fun applySettings() {
        with(binding.touchpad) {
            sensitivity = settings.sensitivity
            scrollSpeed = settings.scrollSpeed
            naturalScroll = settings.naturalScroll
            acceleration = settings.acceleration
            tapToClick = settings.tapToClick
            hapticsEnabled = settings.haptics
        }
    }

    private fun discover() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.find_pc)
            .setMessage(R.string.searching)
            .setCancelable(true)
            .show()

        Discovery.search(settings.port) { servers ->
            dialog.dismiss()
            if (isFinishing || isDestroyed) return@search
            if (servers.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.find_pc)
                    .setMessage(getString(R.string.none_found, settings.port))
                    .setPositiveButton(R.string.ok, null)
                    .show()
                return@search
            }
            if (servers.size == 1) {
                use(servers.first())
                return@search
            }
            val labels = servers
                .map { getString(R.string.server_entry, it.name, it.host) }
                .toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(R.string.choose_pc)
                .setItems(labels) { _, index -> use(servers[index]) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun use(server: Protocol.ServerInfo) {
        settings.host = server.host
        settings.port = server.port
        if (server.needsToken && settings.token.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.find_pc)
                .setMessage(getString(R.string.needs_code, server.name))
                .setPositiveButton(R.string.settings) { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        connect()
    }

    // -- rendering --------------------------------------------------------- #

    private fun render(state: MouseClient.State) {
        connecting = state is MouseClient.State.Connecting

        val (text, colorRes) = when (state) {
            is MouseClient.State.Idle ->
                (if (settings.isConfigured) getString(R.string.status_offline)
                else getString(R.string.status_no_host)) to R.color.status_offline

            is MouseClient.State.Connecting ->
                getString(R.string.status_connecting, state.host) to R.color.status_pending

            is MouseClient.State.Connected ->
                getString(R.string.status_connected, state.serverName, state.rttMs) to
                    R.color.status_connected

            is MouseClient.State.Failed -> state.reason to R.color.status_error
        }

        binding.statusText.text = text
        binding.statusDot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))

        val live = state is MouseClient.State.Connected || state is MouseClient.State.Connecting
        binding.connectButton.setText(if (live) R.string.disconnect else R.string.connect)
        binding.touchpad.hint = getString(
            if (state is MouseClient.State.Connected) R.string.touchpad_hint
            else R.string.touchpad_hint_offline
        )

        if (settings.keepScreenOn && state is MouseClient.State.Connected) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // -- touchpad callbacks ------------------------------------------------ #

    override fun onMove(dx: Float, dy: Float) = client.move(dx, dy)

    override fun onScroll(dx: Float, dy: Float) = client.scroll(dx, dy)

    override fun onClick(button: Char) = client.click(button, copies)

    override fun onPress(button: Char) {
        heldButtons.add(button)
        client.buttonDown(button, copies)
    }

    override fun onRelease(button: Char) {
        heldButtons.remove(button)
        client.buttonUp(button, copies)
    }

    // -- buttons and keys -------------------------------------------------- #

    /**
     * The on-screen buttons stay held for as long as the finger is down, so the
     * user can hold one and drag on the pad with another finger.
     */
    private fun bindMouseButton(view: MaterialButton, button: Char) {
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    target.isPressed = true
                    onPress(button)
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    target.isPressed = false
                    if (button in heldButtons) onRelease(button)
                }
            }
            true
        }
        // Reached by TalkBack and keyboard activation, which never send touches.
        view.setOnClickListener { client.click(button, copies) }
    }

    private fun bindKey(view: MaterialButton, name: String) {
        view.setOnClickListener { client.key(name, copies) }
    }

    private fun releaseEverything() {
        for (button in heldButtons.toList()) onRelease(button)
    }

    // -- keyboard ---------------------------------------------------------- #

    private fun toggleKeyboard() {
        val showing = binding.keyboardPanel.visibility == View.VISIBLE
        binding.keyboardPanel.visibility = if (showing) View.GONE else View.VISIBLE
        val ime = getSystemService(InputMethodManager::class.java)
        if (showing) {
            ime?.hideSoftInputFromWindow(binding.keyInput.windowToken, 0)
            binding.keyInput.clearFocus()
        } else {
            resetTypedText()
            binding.keyInput.requestFocus()
            ime?.showSoftInput(binding.keyInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /**
     * Turns edits in the text field into keystrokes. Diffing against the
     * previous value is what makes backspace and autocorrect work: soft
     * keyboards do not report key presses, only the resulting text.
     */
    private fun sendTextDiff(next: String) {
        if (next == typedSoFar) return

        var shared = 0
        while (shared < next.length && shared < typedSoFar.length && next[shared] == typedSoFar[shared]) {
            shared++
        }
        repeat(typedSoFar.length - shared) { client.key("backspace", copies) }
        val added = next.substring(shared)
        if (added.isNotEmpty()) client.type(added, copies)
        typedSoFar = next

        if (next.length > TYPED_BUFFER_LIMIT) resetTypedText()
    }

    /** Empties the field without the watcher mistaking it for a backspace run. */
    private fun resetTypedText() {
        suppressTextWatcher = true
        binding.keyInput.setText("")
        suppressTextWatcher = false
        typedSoFar = ""
    }

    private companion object {
        const val TYPED_BUFFER_LIMIT = 120
    }
}
