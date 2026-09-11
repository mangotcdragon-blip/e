package com.wifimouse.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.wifimouse.app.databinding.ActivityMainBinding
import com.wifimouse.app.net.ConnectionController
import com.wifimouse.app.net.Discovery
import com.wifimouse.app.net.MouseClient
import com.wifimouse.app.net.Protocol
import com.wifimouse.app.ui.TouchpadView

/** Trackpad mode: the phone lies flat and you use it like a laptop touchpad. */
class MainActivity : AppCompatActivity(), TouchpadView.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: Settings
    private lateinit var connection: ConnectionController

    /** Mirror of the text field, used to turn edits into keystrokes. */
    private var typedSoFar = ""
    private var suppressTextWatcher = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        settings = Settings(this)
        connection = ConnectionController(this, settings, binding.statusDot, binding.statusText)
        connection.onState = ::renderExtras
        renderExtras(MouseClient.State.Idle)

        binding.touchpad.listener = this
        binding.touchpad.hintColor = ContextCompat.getColor(this, R.color.touchpad_hint)
        binding.touchpad.touchColor = ContextCompat.getColor(this, R.color.touchpad_touch)

        binding.connectButton.setOnClickListener {
            if (connection.isLive) connection.disconnect() else if (!connection.connect()) discover()
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
            connection.key("enter")
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
            connection.key("enter")
            resetTypedText()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        applySettings()
        connection.resume()
    }

    override fun onPause() {
        super.onPause()
        connection.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        connection.destroy()
    }

    // -- menu -------------------------------------------------------------- #

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_camera_mouse -> {
            startActivity(Intent(this, CameraMouseActivity::class.java))
            true
        }

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

    private fun renderExtras(state: MouseClient.State) {
        val live = state is MouseClient.State.Connected || state is MouseClient.State.Connecting
        binding.connectButton.setText(if (live) R.string.disconnect else R.string.connect)
        binding.touchpad.hint = getString(
            if (state is MouseClient.State.Connected) R.string.touchpad_hint
            else R.string.touchpad_hint_offline
        )
    }

    private fun discover() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.find_pc)
            .setMessage(R.string.searching)
            .setCancelable(true)
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
            if (servers.size == 1) {
                useServer(servers.first())
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
        connection.connect()
    }

    // -- touchpad callbacks ------------------------------------------------ #

    override fun onMove(dx: Float, dy: Float) = connection.move(dx, dy)

    override fun onScroll(dx: Float, dy: Float) = connection.scroll(dx, dy)

    override fun onClick(button: Char) = connection.click(button)

    override fun onPress(button: Char) = connection.press(button)

    override fun onRelease(button: Char) = connection.release(button)

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
                    connection.press(button)
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    target.isPressed = false
                    connection.release(button)
                }
            }
            true
        }
        // Reached by TalkBack and keyboard activation, which never send touches.
        view.setOnClickListener { connection.click(button) }
    }

    private fun bindKey(view: MaterialButton, name: String) {
        view.setOnClickListener { connection.key(name) }
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
        repeat(typedSoFar.length - shared) { connection.key("backspace") }
        val added = next.substring(shared)
        if (added.isNotEmpty()) connection.type(added)
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
