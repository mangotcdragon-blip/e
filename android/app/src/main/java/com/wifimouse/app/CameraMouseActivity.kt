package com.wifimouse.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.wifimouse.app.databinding.ActivityCameraMouseBinding
import com.wifimouse.app.net.ConnectionController
import com.wifimouse.app.net.Discovery
import com.wifimouse.app.net.MouseClient
import com.wifimouse.app.net.Protocol
import com.wifimouse.app.vision.CameraMouse
import com.wifimouse.app.vision.MotionTracker
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The whole app: a phone held like a mouse.
 *
 * The rear camera does what an optical mouse's sensor does — watches the
 * surface go past and turns that into pointer movement — while the buttons sit
 * under your fingers at the top of the screen with a scroll wheel between them.
 *
 * Nothing below the buttons reacts to touch, so the hand holding the phone can
 * rest on the glass. The controls are behind a deliberate double-tap, which a
 * resting palm cannot produce.
 */
class CameraMouseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraMouseBinding
    private lateinit var settings: Settings
    private lateinit var connection: ConnectionController
    private lateinit var cameraMouse: CameraMouse

    private var cameraRunning = false
    private var lastQualityPost = 0L

    /** Mirror of the text field, used to turn edits into keystrokes. */
    private var typedSoFar = ""
    private var suppressTextWatcher = false

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showPermissionRefused()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraMouseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        connection = ConnectionController(this, settings, binding.statusDot, binding.statusText)
        connection.onState = ::renderConnection

        cameraMouse = CameraMouse(this, ::onCameraMotion)

        goImmersive()
        claimEdgeGestures()
        setUpPalmGuard()
        setUpMouseControls()
        setUpControlsPanel()
        setUpBackHandling()

        renderConnection(MouseClient.State.Idle)
    }

    /**
     * Hides the system bars and claims the screen edges.
     *
     * Swallowing touches is only half of resting a palm on the phone: the other
     * half is Android's own gesture handling, which will happily read the heel
     * of a hand near an edge as a back swipe and close the app. Immersive mode
     * takes the bars away, and the exclusion rects tell the system to leave
     * edge gestures to us. The bars are still one swipe away when wanted.
     */
    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // A notch or punch-hole still has to be avoided even with the bars gone.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            view.setPadding(cutout.left, cutout.top, cutout.right, 0)
            insets
        }
    }

    /** Re-hides the bars after a transient swipe brings them back. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    /**
     * Asks the system not to treat touches near the edges as navigation
     * gestures. Android caps how much of an edge an app may claim, so this
     * reduces stray back swipes rather than abolishing them.
     */
    private fun claimEdgeGestures() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        binding.root.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            view.systemGestureExclusionRects = listOf(Rect(0, 0, view.width, view.height))
        }
    }

    // -- touch handling ---------------------------------------------------- #

    /**
     * The guard swallows every touch below the buttons. It only listens for a
     * double-tap, which is the one gesture a hand resting on the screen will
     * not produce by accident.
     */
    private fun setUpPalmGuard() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true

            override fun onDoubleTap(event: MotionEvent): Boolean {
                showControls(true)
                return true
            }
        })
        binding.palmGuard.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            true // consumed either way: nothing here is meant to be pressable
        }
    }

    private fun setUpMouseControls() {
        bindMouseButton(binding.leftButton, 'l')
        bindMouseButton(binding.rightButton, 'r')

        with(binding.wheel) {
            hapticsEnabled = settings.haptics
            wheelColor = ContextCompat.getColor(this@CameraMouseActivity, R.color.wheel_body)
            ribColor = ContextCompat.getColor(this@CameraMouseActivity, R.color.wheel_rib)
            onNotch = { notches ->
                val direction = if (settings.naturalScroll) -1 else 1
                connection.scroll(0f, notches * settings.scrollSpeed * direction)
            }
            // Pressing a real wheel is a middle click, so this one does too.
            onWheelClick = { connection.click('m') }
        }
    }

    /** Held for as long as your finger is down, exactly like a real button. */
    private fun bindMouseButton(view: View, button: Char) {
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
        view.setOnClickListener { connection.click(button) }
    }

    // -- controls panel ---------------------------------------------------- #

    private fun setUpControlsPanel() {
        binding.connectButton.setOnClickListener {
            if (connection.isLive) connection.disconnect() else if (!connection.connect()) discover()
        }
        binding.findButton.setOnClickListener { discover() }
        binding.torchButton.setOnClickListener { toggleTorch() }
        binding.focusButton.setOnClickListener { cameraMouse.refocus() }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.helpButton.setOnClickListener { showHelp() }
        binding.closeControlsButton.setOnClickListener { showControls(false) }
        binding.keyboardButton.setOnClickListener { toggleKeyboard() }

        binding.qualityBar.max = 100
        binding.speedSlider.valueFrom = Settings.MIN_CAMERA_SENSITIVITY
        binding.speedSlider.valueTo = Settings.MAX_CAMERA_SENSITIVITY
        binding.speedSlider.value = settings.cameraSensitivity
            .coerceIn(Settings.MIN_CAMERA_SENSITIVITY, Settings.MAX_CAMERA_SENSITIVITY)
        showSpeed(binding.speedSlider.value)
        binding.speedSlider.addOnChangeListener { _, value, _ ->
            settings.cameraSensitivity = value
            showSpeed(value)
        }

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

        updateTorchButton()
    }

    /** The guard steps aside while the panel is up, so its controls can be used. */
    private fun showControls(visible: Boolean) {
        binding.controlsPanel.visibility = if (visible) View.VISIBLE else View.GONE
        binding.palmGuard.visibility = if (visible) View.GONE else View.VISIBLE
        if (!visible) hideKeyboard()
    }

    private val controlsVisible: Boolean
        get() = binding.controlsPanel.visibility == View.VISIBLE

    /** Back closes the controls panel first, then leaves the app. */
    private fun setUpBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (controlsVisible) {
                    showControls(false)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    // -- lifecycle --------------------------------------------------------- #

    override fun onResume() {
        super.onResume()
        binding.wheel.hapticsEnabled = settings.haptics
        connection.resume()
        if (hasCameraPermission()) startCamera() else requestPermissionOrExplain()
    }

    override fun onPause() {
        super.onPause()
        cameraMouse.stop()
        cameraRunning = false
        connection.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraMouse.release()
        connection.destroy()
    }

    // -- connection -------------------------------------------------------- #

    private fun renderConnection(state: MouseClient.State) {
        val live = state is MouseClient.State.Connected || state is MouseClient.State.Connecting
        binding.connectButton.setText(if (live) R.string.disconnect else R.string.connect)
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

    // -- camera ------------------------------------------------------------ #

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestPermissionOrExplain() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(R.string.camera_permission_needed)
                .setPositiveButton(R.string.grant_camera) { _, _ ->
                    requestCamera.launch(Manifest.permission.CAMERA)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        if (cameraRunning) return
        cameraRunning = true
        showMessage(null)
        if (settings.cameraTorch) cameraMouse.setTorch(true)
        cameraMouse.start(this) { reason ->
            cameraRunning = false
            showMessage(getString(R.string.camera_failed, reason))
        }
        updateTorchButton()
    }

    private fun showPermissionRefused() {
        showMessage(getString(R.string.camera_permission_denied))
    }

    /** The empty half of the screen doubles as the only place for a message. */
    private fun showMessage(text: String?) {
        binding.messageText.text = text.orEmpty()
        binding.messageText.visibility = if (text == null) View.GONE else View.VISIBLE
        binding.hintText.visibility = if (text == null) View.VISIBLE else View.GONE
    }

    /**
     * Called on the camera's analysis thread. Sending is safe from here — the
     * client queue is thread-safe — but the readout has to go to the main
     * thread, and only occasionally, since this runs 30 times a second.
     */
    private fun onCameraMotion(reading: CameraMouse.Reading) {
        val gain = CURSOR_GAIN * settings.cameraSensitivity
        val verticalSign = if (settings.cameraInvertY) -1f else 1f

        // Below the noise floor this is sensor grain, not movement; passing it
        // on would leave the pointer creeping across the screen on its own.
        val moved = reading.usable && hypot(reading.dx, reading.dy) >= NOISE_FLOOR
        if (moved) {
            connection.move(reading.dx * gain, reading.dy * gain * verticalSign)
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastQualityPost >= QUALITY_INTERVAL_MS) {
            lastQualityPost = now
            runOnUiThread { if (!isFinishing && !isDestroyed) showReading(reading) }
        }
    }

    /**
     * Puts the real numbers on screen. Whether the camera can see the surface,
     * how well the frames match, how far it thinks the phone moved, and how
     * many datagrams have gone out — which between them say exactly where the
     * chain is broken when the pointer will not move.
     */
    private fun showReading(reading: CameraMouse.Reading) {
        binding.qualityBar.setProgressCompat((reading.quality * 100).roundToInt(), true)
        binding.diagnosticsText.text = getString(
            R.string.diagnostics,
            reading.texture,
            MotionTracker.MIN_TEXTURE,
            reading.confidence,
            reading.dx,
            reading.dy,
            connection.packetsSent,
        )
        binding.hintText.setText(
            if (reading.usable) R.string.controls_hint else R.string.surface_too_plain
        )
    }

    private fun toggleTorch() {
        val next = !cameraMouse.torchOn
        cameraMouse.setTorch(next)
        settings.cameraTorch = next
        updateTorchButton()
    }

    private fun updateTorchButton() {
        binding.torchButton.setText(if (cameraMouse.torchOn) R.string.torch_off else R.string.torch_on)
    }

    private fun showSpeed(value: Float) {
        binding.speedLabel.text = getString(R.string.camera_speed, value)
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.camera_help_title)
            .setMessage(R.string.camera_help)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    // -- keyboard ---------------------------------------------------------- #

    private fun bindKey(view: MaterialButton, name: String) {
        view.setOnClickListener { connection.key(name) }
    }

    private fun toggleKeyboard() {
        val showing = binding.keyboardArea.visibility == View.VISIBLE
        binding.keyboardArea.visibility = if (showing) View.GONE else View.VISIBLE
        if (showing) {
            hideKeyboard()
        } else {
            resetTypedText()
            binding.keyInput.requestFocus()
            getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(binding.keyInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(binding.keyInput.windowToken, 0)
        binding.keyInput.clearFocus()
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
        /**
         * Cursor pixels per pixel of image movement, before the user's speed
         * setting. Chosen so a hand-sized sweep crosses a desktop display.
         */
        const val CURSOR_GAIN = 24f

        /** Image movement below this is sensor noise. Measured at about 0.03 px. */
        const val NOISE_FLOOR = 0.08f

        const val QUALITY_INTERVAL_MS = 150L
        const val TYPED_BUFFER_LIMIT = 120
    }
}
