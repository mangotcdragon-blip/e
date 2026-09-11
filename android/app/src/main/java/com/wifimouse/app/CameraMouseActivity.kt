package com.wifimouse.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.wifimouse.app.databinding.ActivityCameraMouseBinding
import com.wifimouse.app.net.ConnectionController
import com.wifimouse.app.vision.CameraMouse
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Mouse mode: hold the phone like a mouse and slide it around.
 *
 * The rear camera does what an optical mouse's sensor does — watches the
 * surface go past and turns that into pointer movement — while the buttons sit
 * under your fingers at the top of the screen with a scroll wheel between them.
 */
class CameraMouseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraMouseBinding
    private lateinit var settings: Settings
    private lateinit var connection: ConnectionController
    private lateinit var cameraMouse: CameraMouse

    private var cameraRunning = false
    private var lastQualityPost = 0L

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

        cameraMouse = CameraMouse(this, ::onCameraMotion)

        bindMouseButton(binding.leftButton, 'l')
        bindMouseButton(binding.rightButton, 'r')

        with(binding.wheel) {
            hapticsEnabled = settings.haptics
            wheelColor = ContextCompat.getColor(this@CameraMouseActivity, R.color.touchpad_surface)
            ribColor = ContextCompat.getColor(this@CameraMouseActivity, R.color.touchpad_hint)
            onNotch = { notches ->
                val direction = if (settings.naturalScroll) -1 else 1
                connection.scroll(0f, notches * settings.scrollSpeed * direction)
            }
            // Pressing a real wheel is a middle click, so this one does too.
            onWheelClick = { connection.click('m') }
        }

        binding.backButton.setOnClickListener { finish() }
        binding.focusButton.setOnClickListener { cameraMouse.refocus() }
        binding.torchButton.setOnClickListener { toggleTorch() }
        binding.trackingLabel.setOnClickListener { showHelp() }
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

        updateTorchButton()
    }

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

    // -- camera ------------------------------------------------------------ #

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestPermissionOrExplain() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.camera_mouse)
                .setMessage(R.string.camera_permission_needed)
                .setPositiveButton(R.string.grant_camera) { _, _ ->
                    requestCamera.launch(Manifest.permission.CAMERA)
                }
                .setNegativeButton(R.string.cancel) { _, _ -> finish() }
                .show()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        if (cameraRunning) return
        cameraRunning = true
        binding.cameraHint.visibility = View.GONE
        if (settings.cameraTorch) cameraMouse.setTorch(true)
        cameraMouse.start(this, binding.preview) { reason ->
            cameraRunning = false
            binding.cameraHint.visibility = View.VISIBLE
            binding.cameraHint.text = getString(R.string.camera_failed, reason)
        }
        updateTorchButton()
    }

    private fun showPermissionRefused() {
        binding.cameraHint.visibility = View.VISIBLE
        binding.cameraHint.setText(R.string.camera_permission_denied)
    }

    /**
     * Called on the camera's analysis thread. Sending is safe from here — the
     * client queue is thread-safe — but the meter has to go to the main thread,
     * and only occasionally, since this runs 30 times a second.
     */
    private fun onCameraMotion(dx: Float, dy: Float, quality: Float) {
        val gain = CURSOR_GAIN * settings.cameraSensitivity
        val verticalSign = if (settings.cameraInvertY) -1f else 1f

        // Below the noise floor this is sensor grain, not movement; passing it
        // on would leave the pointer creeping across the screen on its own.
        if (hypot(dx, dy) >= NOISE_FLOOR) {
            connection.move(dx * gain, dy * gain * verticalSign)
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastQualityPost >= QUALITY_INTERVAL_MS) {
            lastQualityPost = now
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    binding.qualityBar.setProgressCompat((quality * 100).roundToInt(), true)
                }
            }
        }
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

    // -- buttons ----------------------------------------------------------- #

    /** Held for as long as your finger is down, exactly like a real button. */
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
        view.setOnClickListener { connection.click(button) }
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
    }
}
