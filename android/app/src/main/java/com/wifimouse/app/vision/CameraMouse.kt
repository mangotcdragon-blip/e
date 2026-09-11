package com.wifimouse.app.vision

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

/**
 * Reads movement from the rear camera, the way an optical mouse reads movement
 * from a desk.
 *
 * Frames arrive at the analysis resolution, get shrunk to [WORK_WIDTH] x
 * [WORK_HEIGHT], and consecutive pairs go to [MotionTracker]. The result is how
 * far the *picture* moved; the phone moved the opposite way, which is what the
 * pointer follows.
 *
 * What this cannot beat: a phone camera delivers 30 frames a second, where the
 * sensor in a real mouse manages thousands. Expect noticeably more lag than the
 * trackpad, and no tracking at all on a surface with no visible texture.
 */
class CameraMouse(
    private val context: Context,
    /**
     * Called on the analysis thread with pointer movement in frame pixels,
     * already rotated into display orientation, plus a 0..1 tracking quality.
     */
    private val onMotion: (dx: Float, dy: Float, quality: Float) -> Unit,
) {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wifimouse-vision").apply { isDaemon = true }
    }

    private val tracker = MotionTracker(WORK_WIDTH, WORK_HEIGHT)
    private val downsampler = LumaDownsampler(WORK_WIDTH, WORK_HEIGHT)
    private var previous = ByteArray(WORK_WIDTH * WORK_HEIGHT)
    private var current = ByteArray(WORK_WIDTH * WORK_HEIGHT)
    private var havePrevious = false

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var previewView: PreviewView? = null

    @Volatile
    var torchOn: Boolean = false
        private set

    fun start(owner: LifecycleOwner, view: PreviewView, onError: (String) -> Unit) {
        previewView = view
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                bind(future.get(), owner, view)
            } catch (exc: Exception) {
                Log.e(TAG, "camera failed to start", exc)
                onError(exc.message ?: "Camera unavailable")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bind(cameraProvider: ProcessCameraProvider, owner: LifecycleOwner, view: PreviewView) {
        provider = cameraProvider

        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(view.surfaceProvider)

        val resolution = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                )
            )
            .build()

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            // Always work on the newest frame: a queued one describes where the
            // phone used to be, which is worse than useless for a pointer.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(executor, ::analyze)

        cameraProvider.unbindAll()
        havePrevious = false
        camera = cameraProvider.bindToLifecycle(
            owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
        )
        if (torchOn) camera?.cameraControl?.enableTorch(true)
        refocus()
    }

    private fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            downsampler.downsample(
                source = plane.buffer,
                sourceWidth = image.width,
                sourceHeight = image.height,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
                destination = current,
            )

            if (havePrevious) {
                val result = tracker.track(previous, current)
                if (result.usable) {
                    val (dx, dy) = toDisplaySpace(result.dx, result.dy, image.imageInfo.rotationDegrees)
                    // The picture slides one way when the phone goes the other.
                    onMotion(-dx, -dy, result.quality)
                } else {
                    onMotion(0f, 0f, result.quality)
                }
            }

            val spare = previous
            previous = current
            current = spare
            havePrevious = true
        } catch (exc: Exception) {
            Log.w(TAG, "frame dropped", exc)
        } finally {
            image.close()
        }
    }

    /**
     * Camera frames arrive in sensor orientation; this turns a vector in that
     * space into one that matches what the user sees.
     */
    private fun toDisplaySpace(dx: Float, dy: Float, rotationDegrees: Int): Pair<Float, Float> =
        when ((rotationDegrees % 360 + 360) % 360) {
            90 -> -dy to dx
            180 -> -dx to -dy
            270 -> dy to -dx
            else -> dx to dy
        }

    fun setTorch(on: Boolean) {
        torchOn = on
        camera?.cameraControl?.enableTorch(on)
    }

    /**
     * Locks focus on the middle of the frame. Continuous autofocus is worse than
     * useless here: every hunt blurs the picture and stalls tracking.
     */
    fun refocus() {
        val view = previewView ?: return
        val control = camera?.cameraControl ?: return
        if (view.width == 0 || view.height == 0) return
        try {
            val point = view.meteringPointFactory.createPoint(view.width / 2f, view.height / 2f)
            control.startFocusAndMetering(
                FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                    .disableAutoCancel()
                    .build()
            )
        } catch (exc: Exception) {
            Log.d(TAG, "focus request refused: ${exc.message}")
        }
    }

    fun stop() {
        camera?.cameraControl?.enableTorch(false)
        provider?.unbindAll()
        provider = null
        camera = null
        previewView = null
        havePrevious = false
    }

    fun release() {
        stop()
        executor.shutdown()
    }

    companion object {
        private const val TAG = "CameraMouse"

        /** What the camera is asked for; the real size may differ slightly. */
        const val ANALYSIS_WIDTH = 640
        const val ANALYSIS_HEIGHT = 480

        /** What the tracker actually works on. Small is fast, and fast is smooth. */
        const val WORK_WIDTH = 80
        const val WORK_HEIGHT = 60
    }
}
