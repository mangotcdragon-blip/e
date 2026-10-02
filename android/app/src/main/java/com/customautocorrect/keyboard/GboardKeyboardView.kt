package com.customautocorrect.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.util.AttributeSet
import androidx.core.content.ContextCompat

/**
 * KeyboardView that layers custom icons (instead of unicode glyphs) and an accent-colored
 * Enter key on top of the stock key backgrounds/press states, to approximate Gboard's look.
 */
class GboardKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet?,
    defStyle: Int = 0
) : KeyboardView(context, attrs, defStyle) {

    companion object {
        private const val CODE_ENTER = 10
        private const val CODE_SPACE = 32
        const val CODE_EMOJI = -10
    }

    var languageLabel: String = "English (US)"

    private val iconShift by lazy { requireIcon(R.drawable.ic_key_shift) }
    private val iconBackspace by lazy { requireIcon(R.drawable.ic_key_backspace) }
    private val iconEnter by lazy { requireIcon(R.drawable.ic_key_enter) }
    private val iconEmoji by lazy { requireIcon(R.drawable.ic_key_emoji) }
    private val accentBackground by lazy {
        ContextCompat.getDrawable(context, R.drawable.accent_key_background)!!.mutate()
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private fun requireIcon(resId: Int): Drawable =
        ContextCompat.getDrawable(context, resId)!!.mutate()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val keys = keyboard?.keys ?: return
        val normalColor = ContextCompat.getColor(context, R.color.key_text_secondary)
        val accentIconColor = ContextCompat.getColor(context, R.color.accent_key_icon)

        for (key in keys) {
            when (key.codes.firstOrNull()) {
                Keyboard.KEYCODE_SHIFT -> drawIcon(canvas, iconShift, key, normalColor)
                Keyboard.KEYCODE_DELETE -> drawIcon(canvas, iconBackspace, key, normalColor)
                CODE_EMOJI -> drawIcon(canvas, iconEmoji, key, normalColor)
                CODE_ENTER -> {
                    accentBackground.setBounds(key.x + 2, key.y + 2, key.x + key.width - 2, key.y + key.height - 2)
                    accentBackground.state = if (key.pressed) intArrayOf(android.R.attr.state_pressed) else intArrayOf()
                    accentBackground.draw(canvas)
                    drawIcon(canvas, iconEnter, key, accentIconColor)
                }
                CODE_SPACE -> {
                    labelPaint.color = normalColor
                    labelPaint.textSize = 12f * resources.displayMetrics.density
                    val cx = (key.x + key.width / 2).toFloat()
                    val cy = key.y + key.height / 2f + labelPaint.textSize / 3f
                    canvas.drawText(languageLabel, cx, cy, labelPaint)
                }
            }
        }
    }

    private fun drawIcon(canvas: Canvas, icon: Drawable, key: Keyboard.Key, color: Int) {
        val iconSize = (key.height * 0.42f).toInt()
        val cx = key.x + key.width / 2
        val cy = key.y + key.height / 2
        icon.setBounds(cx - iconSize / 2, cy - iconSize / 2, cx + iconSize / 2, cy + iconSize / 2)
        icon.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        icon.draw(canvas)
    }
}
