package com.wifimouse.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.sin

/**
 * A scroll wheel that behaves like the one on a real mouse.
 *
 * Three things make it feel like hardware rather than a slider: it clicks
 * through discrete notches instead of scrolling continuously, each notch fires
 * a short haptic tick, and a flick keeps spinning and coasts to a stop. The
 * ribs drawn across it move with the wheel, so the notches are visible as well
 * as felt.
 */
class ScrollWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Fired once per notch: +1 is a push away from you, which scrolls up. */
    var onNotch: ((Int) -> Unit)? = null

    /** Pressing the wheel without turning it, like clicking a real one. */
    var onWheelClick: (() -> Unit)? = null

    var hapticsEnabled = true

    private val density = resources.displayMetrics.density
    private val notchDistance = NOTCH_DP * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ribPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f * density
    }
    private val body = RectF()

    /** How far the wheel has turned, in pixels; only the remainder is drawn. */
    private var travel = 0f
    private var sinceLastNotch = 0f
    private var lastY = 0f
    private var downY = 0f
    private var turned = false
    private var velocityTracker: VelocityTracker? = null

    /** Pixels per second while coasting after a flick. */
    private var coastVelocity = 0f
    private var lastCoastTime = 0L

    var wheelColor: Int
        get() = bodyPaint.color
        set(value) {
            bodyPaint.color = value
            invalidate()
        }

    var ribColor: Int
        get() = ribPaint.color
        set(value) {
            ribPaint.color = value
            invalidate()
        }

    init {
        isClickable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                stopCoasting()
                isPressed = true
                lastY = event.y
                downY = event.y
                turned = false
                sinceLastNotch = 0f
                velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val delta = event.y - lastY
                lastY = event.y
                if (!turned && abs(event.y - downY) > touchSlop) turned = true
                if (turned) turn(delta)
            }

            MotionEvent.ACTION_UP -> {
                isPressed = false
                velocityTracker?.addMovement(event)
                if (!turned) {
                    onWheelClick?.invoke()
                    tick()
                } else {
                    velocityTracker?.let { tracker ->
                        tracker.computeCurrentVelocity(1000)
                        val velocity = tracker.yVelocity
                        if (abs(velocity) > MIN_FLING_VELOCITY) startCoasting(velocity)
                    }
                }
                recycleTracker()
            }

            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                recycleTracker()
            }
        }
        return true
    }

    private fun recycleTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    /** Turns the wheel by [delta] pixels, firing a notch each time one passes. */
    private fun turn(delta: Float) {
        travel += delta
        sinceLastNotch += delta

        while (abs(sinceLastNotch) >= notchDistance) {
            val direction = if (sinceLastNotch > 0) 1 else -1
            sinceLastNotch -= direction * notchDistance
            // Dragging up is the wheel going away from you, which scrolls up.
            onNotch?.invoke(-direction)
            tick()
        }
        invalidate()
    }

    private fun tick() {
        if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    // -- coasting ---------------------------------------------------------- #

    private val coastStep = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val seconds = ((now - lastCoastTime) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.05f)
            lastCoastTime = now

            turn(coastVelocity * seconds)
            coastVelocity *= COAST_DECAY

            if (abs(coastVelocity) > MIN_COAST_VELOCITY) {
                postOnAnimation(this)
            } else {
                coastVelocity = 0f
            }
        }
    }

    private fun startCoasting(velocity: Float) {
        coastVelocity = velocity.coerceIn(-MAX_FLING_VELOCITY, MAX_FLING_VELOCITY)
        lastCoastTime = System.nanoTime()
        postOnAnimation(coastStep)
    }

    private fun stopCoasting() {
        removeCallbacks(coastStep)
        coastVelocity = 0f
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopCoasting()
        recycleTracker()
    }

    // -- drawing ----------------------------------------------------------- #

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val inset = 2f * density
        body.set(inset, inset, width - inset, height - inset)
        val radius = body.width() / 2f
        canvas.drawRoundRect(body, radius, radius, bodyPaint)

        // Ribs march with the wheel, and fade towards the ends so the strip
        // reads as a cylinder rather than a ladder.
        val spacing = notchDistance
        val phase = ((travel % spacing) + spacing) % spacing
        val baseAlpha = ribPaint.alpha
        var y = body.top + phase
        while (y < body.bottom) {
            val position = (y - body.top) / body.height()
            ribPaint.alpha = (baseAlpha * sin(position * Math.PI).toFloat()).toInt().coerceIn(0, 255)
            canvas.drawLine(body.left + radius * 0.45f, y, body.right - radius * 0.45f, y, ribPaint)
            y += spacing
        }
        ribPaint.alpha = baseAlpha
    }

    private companion object {
        /** Travel between notches. Roughly the pitch of a real mouse wheel. */
        const val NOTCH_DP = 16f

        const val MIN_FLING_VELOCITY = 220f
        const val MAX_FLING_VELOCITY = 4200f
        const val MIN_COAST_VELOCITY = 90f

        /** Per-frame decay; gives about a second of coasting from a hard flick. */
        const val COAST_DECAY = 0.94f
    }
}
