package com.wifimouse.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The trackpad surface.
 *
 * Gestures follow laptop trackpad conventions, so there is nothing to learn:
 *
 *  - one finger dragging      -> move the pointer
 *  - one-finger tap           -> left click
 *  - two-finger tap           -> right click
 *  - three-finger tap         -> middle click
 *  - two fingers dragging     -> scroll
 *  - tap, then press and drag -> hold the left button (drag and drop)
 *  - press and hold, then drag-> hold the left button
 *
 * A [android.view.GestureDetector] is not used: it has no concept of how many
 * fingers took part in a tap, which is exactly what distinguishes a left click
 * from a right click here.
 */
class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        fun onMove(dx: Float, dy: Float)

        /** Scroll in wheel notches; positive [dy] scrolls content down. */
        fun onScroll(dx: Float, dy: Float)
        fun onClick(button: Char)
        fun onPress(button: Char)
        fun onRelease(button: Char)
    }

    var listener: Listener? = null

    var sensitivity = 1.6f
    var scrollSpeed = 1.0f
    var naturalScroll = true
    var acceleration = true
    var tapToClick = true
    var hapticsEnabled = true

    /** Shown in the middle of the pad while there is nothing to control. */
    var hint: String? = null
        set(value) {
            field = value
            invalidate()
        }

    private enum class Mode {
        /** No gesture in progress. */
        IDLE,

        /** One finger is moving the pointer. */
        MOVE,

        /** Two or more fingers are scrolling. */
        SCROLL,

        /** The left button is held down and one finger is dragging. */
        DRAG,

        /**
         * A multi-finger gesture is being lifted. Movement is ignored until the
         * last finger is up, otherwise the pointer jumps when one of two
         * fingers leaves the glass first.
         */
        SETTLE,
    }

    private val uiHandler = Handler(Looper.getMainLooper())
    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val pxPerNotch = SCROLL_DP_PER_NOTCH * density

    private var mode = Mode.IDLE
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0f
    private var lastY = 0f
    private var lastSampleTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var maxPointers = 0
    private var movedBeyondSlop = false
    private var holdingLeftButton = false
    private var lastTapUpTime = 0L
    private var scrollAnchorX = 0f
    private var scrollAnchorY = 0f
    private var scrollTravel = 0f

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14f * density
        textAlign = Paint.Align.CENTER
    }
    private val touchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val activePoints = ArrayList<Pair<Float, Float>>(4)

    /** Colours come from the theme, which the activity resolves for us. */
    var hintColor: Int
        get() = hintPaint.color
        set(value) {
            hintPaint.color = value
            invalidate()
        }

    var touchColor: Int
        get() = touchPaint.color
        set(value) {
            touchPaint.color = value
            invalidate()
        }

    private val longPressRunnable = Runnable {
        if (mode == Mode.MOVE && !movedBeyondSlop && !holdingLeftButton) {
            startHold(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    init {
        isClickable = true
        isFocusable = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onFirstDown(event)
            MotionEvent.ACTION_POINTER_DOWN -> onExtraDown(event)
            MotionEvent.ACTION_MOVE -> onMoveEvent(event)
            MotionEvent.ACTION_POINTER_UP -> onExtraUp(event)
            MotionEvent.ACTION_UP -> onLastUp(event)
            MotionEvent.ACTION_CANCEL -> onCancel()
        }
        trackPointsForDrawing(event)
        return true
    }

    // -- gesture phases ---------------------------------------------------- #

    private fun onFirstDown(event: MotionEvent) {
        parent?.requestDisallowInterceptTouchEvent(true)
        cancelPendingHold()

        activePointerId = event.getPointerId(0)
        lastX = event.x
        lastY = event.y
        downX = lastX
        downY = lastY
        downTime = event.eventTime
        lastSampleTime = event.eventTime
        maxPointers = 1
        movedBeyondSlop = false
        scrollTravel = 0f
        mode = Mode.MOVE

        val sinceTap = event.eventTime - lastTapUpTime
        if (tapToClick && lastTapUpTime != 0L && sinceTap <= DOUBLE_TAP_WINDOW_MS) {
            // Tap, then press again straight away: the classic trackpad
            // "tap-and-a-half" drag.
            lastTapUpTime = 0L
            startHold(HapticFeedbackConstants.VIRTUAL_KEY)
        } else {
            uiHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
        }
    }

    private fun onExtraDown(event: MotionEvent) {
        maxPointers = maxOf(maxPointers, event.pointerCount)
        cancelPendingHold()
        if (holdingLeftButton) return // stay in DRAG; extra fingers are ignored

        mode = Mode.SCROLL
        val (cx, cy) = centroid(event)
        scrollAnchorX = cx
        scrollAnchorY = cy
        lastSampleTime = event.eventTime
    }

    private fun onMoveEvent(event: MotionEvent) {
        when (mode) {
            Mode.SCROLL -> handleScroll(event)
            Mode.MOVE, Mode.DRAG -> handlePointerMove(event)
            else -> Unit
        }
    }

    private fun handlePointerMove(event: MotionEvent) {
        val index = event.findPointerIndex(activePointerId)
        if (index < 0) return

        // Replaying the batched history keeps fast swipes smooth instead of
        // jumping between frames.
        for (sample in 0..event.historySize) {
            val isLive = sample == event.historySize
            val x = if (isLive) event.getX(index) else event.getHistoricalX(index, sample)
            val y = if (isLive) event.getY(index) else event.getHistoricalY(index, sample)
            val time = if (isLive) event.eventTime else event.getHistoricalEventTime(sample)

            val dx = x - lastX
            val dy = y - lastY
            val dt = (time - lastSampleTime).toFloat()
            lastX = x
            lastY = y
            lastSampleTime = time

            if (!movedBeyondSlop) {
                if (hypot(x - downX, y - downY) <= touchSlop && !holdingLeftButton) continue
                movedBeyondSlop = true
                cancelPendingHold()
            }

            if (dx == 0f && dy == 0f) continue
            val gain = PointerMath.gain(dx, dy, dt, sensitivity, acceleration)
            listener?.onMove(dx * gain, dy * gain)
        }
    }

    private fun handleScroll(event: MotionEvent) {
        val (cx, cy) = centroid(event)
        val dx = cx - scrollAnchorX
        val dy = cy - scrollAnchorY
        scrollAnchorX = cx
        scrollAnchorY = cy

        scrollTravel += hypot(dx, dy)
        if (scrollTravel > touchSlop) movedBeyondSlop = true

        if (abs(dx) < 0.01f && abs(dy) < 0.01f) return
        val direction = if (naturalScroll) 1f else -1f
        listener?.onScroll(
            dx = -dx / pxPerNotch * scrollSpeed * direction,
            dy = dy / pxPerNotch * scrollSpeed * direction,
        )
    }

    private fun onExtraUp(event: MotionEvent) {
        val goingUpId = event.getPointerId(event.actionIndex)
        if (mode == Mode.SCROLL) {
            // Wait for every finger to leave before trusting movement again.
            mode = Mode.SETTLE
            return
        }
        if (goingUpId == activePointerId) {
            // The finger that was steering left; hand over to another one so a
            // drag in progress survives.
            val replacement = (0 until event.pointerCount).firstOrNull {
                event.getPointerId(it) != goingUpId
            } ?: return
            activePointerId = event.getPointerId(replacement)
            lastX = event.getX(replacement)
            lastY = event.getY(replacement)
            lastSampleTime = event.eventTime
        }
    }

    private fun onLastUp(event: MotionEvent) {
        cancelPendingHold()
        val duration = event.eventTime - downTime

        if (holdingLeftButton) {
            releaseHold()
        } else if (tapToClick && !movedBeyondSlop && duration <= TAP_MAX_MS) {
            val button = when (maxPointers) {
                1 -> 'l'
                2 -> 'r'
                else -> 'm'
            }
            listener?.onClick(button)
            haptic(HapticFeedbackConstants.VIRTUAL_KEY)
            // Only a plain left tap opens the window for a tap-and-drag.
            lastTapUpTime = if (button == 'l') event.eventTime else 0L
        } else {
            lastTapUpTime = 0L
        }

        mode = Mode.IDLE
        activePointerId = MotionEvent.INVALID_POINTER_ID
        maxPointers = 0
    }

    private fun onCancel() {
        cancelPendingHold()
        if (holdingLeftButton) releaseHold()
        mode = Mode.IDLE
        activePointerId = MotionEvent.INVALID_POINTER_ID
        maxPointers = 0
        lastTapUpTime = 0L
    }

    // -- button holding ---------------------------------------------------- #

    private fun startHold(feedback: Int) {
        holdingLeftButton = true
        mode = Mode.DRAG
        movedBeyondSlop = true // the button is down; every movement counts now
        listener?.onPress('l')
        haptic(feedback)
    }

    private fun releaseHold() {
        holdingLeftButton = false
        listener?.onRelease('l')
        haptic(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** Named so it cannot be mistaken for [View.cancelLongPress]. */
    private fun cancelPendingHold() {
        uiHandler.removeCallbacks(longPressRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelPendingHold()
        if (holdingLeftButton) releaseHold()
    }

    // -- helpers ----------------------------------------------------------- #

    private fun centroid(event: MotionEvent): Pair<Float, Float> {
        var sumX = 0f
        var sumY = 0f
        for (index in 0 until event.pointerCount) {
            sumX += event.getX(index)
            sumY += event.getY(index)
        }
        return (sumX / event.pointerCount) to (sumY / event.pointerCount)
    }

    private fun haptic(feedback: Int) {
        if (hapticsEnabled) performHapticFeedback(feedback)
    }

    private fun trackPointsForDrawing(event: MotionEvent) {
        activePoints.clear()
        val finished = event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        if (!finished) {
            val lifting = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                event.actionIndex
            } else {
                -1
            }
            for (index in 0 until event.pointerCount) {
                if (index == lifting) continue
                activePoints.add(event.getX(index) to event.getY(index))
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val text = hint
        if (text != null && activePoints.isEmpty()) {
            val lines = text.split("\n")
            val lineHeight = hintPaint.fontSpacing
            var y = height / 2f - (lines.size - 1) * lineHeight / 2f + lineHeight / 3f
            for (line in lines) {
                canvas.drawText(line, width / 2f, y, hintPaint)
                y += lineHeight
            }
        }

        val radius = TOUCH_INDICATOR_DP * density
        for ((x, y) in activePoints) {
            canvas.drawCircle(x, y, radius, touchPaint)
        }
    }

    private companion object {
        const val TAP_MAX_MS = 260L
        const val LONG_PRESS_MS = 420L
        const val DOUBLE_TAP_WINDOW_MS = 280L
        const val SCROLL_DP_PER_NOTCH = 26f
        const val TOUCH_INDICATOR_DP = 22f
    }
}
