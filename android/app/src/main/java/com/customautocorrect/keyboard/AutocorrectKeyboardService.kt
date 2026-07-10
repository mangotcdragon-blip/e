package com.customautocorrect.keyboard

import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.TextView

class AutocorrectKeyboardService : InputMethodService(), KeyboardView.OnKeyboardActionListener {

    companion object {
        private const val IDLE_DELAY_MS = 3000L
        private const val MODE_LETTERS = 0
        private const val MODE_SYMBOLS = 1
        private const val CODE_ENTER = 10
        private const val CODE_SPACE = 32
    }

    private lateinit var keyboardView: KeyboardView
    private lateinit var statusText: TextView
    private lateinit var qwertyKeyboard: Keyboard
    private lateinit var symbolsKeyboard: Keyboard
    private var currentMode = MODE_LETTERS
    private var capsOn = false

    private var ruleMap: Map<String, String> = emptyMap()
    private val handler = Handler(Looper.getMainLooper())
    private var idleRunnable: Runnable? = null
    private var statusClearRunnable: Runnable? = null

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> reloadDictionary() }

    override fun onCreate() {
        super.onCreate()
        DictionaryStore.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        reloadDictionary()
    }

    override fun onDestroy() {
        super.onDestroy()
        DictionaryStore.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        cancelIdleTimer()
    }

    private fun reloadDictionary() {
        val rules = DictionaryStore.loadRules(this)
        ruleMap = rules.associate { it.from.lowercase() to it.to }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val layout = layoutInflater.inflate(R.layout.keyboard_view, null)
        keyboardView = layout.findViewById(R.id.keyboard_view)
        statusText = layout.findViewById(R.id.status_text)
        qwertyKeyboard = Keyboard(this, R.xml.qwerty)
        symbolsKeyboard = Keyboard(this, R.xml.symbols)
        keyboardView.keyboard = qwertyKeyboard
        keyboardView.setOnKeyboardActionListener(this)
        currentMode = MODE_LETTERS
        return layout
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        currentMode = MODE_LETTERS
        capsOn = false
        keyboardView.keyboard = qwertyKeyboard
        qwertyKeyboard.isShifted = false
        keyboardView.invalidateAllKeys()
        cancelIdleTimer()
        statusText.text = ""
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        cancelIdleTimer()
    }

    // ---- Autocorrect engine ----

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\''

    private fun extractTrailingWord(text: String): String {
        var start = text.length
        while (start > 0 && isWordChar(text[start - 1])) start--
        return text.substring(start)
    }

    /** Looks at the word immediately before the cursor and replaces it in place if it matches a rule. */
    private fun correctWordBeforeCursor(ic: InputConnection) {
        val text = ic.getTextBeforeCursor(64, 0)?.toString() ?: return
        val word = extractTrailingWord(text)
        if (word.isEmpty()) return
        val replacement = ruleMap[word.lowercase()] ?: return
        if (replacement == word) return
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText(replacement, 1)
        flashStatus("Corrected \"$word\" → \"$replacement\"")
    }

    private fun scheduleIdleCorrection() {
        cancelIdleTimer()
        val r = Runnable {
            currentInputConnection?.let { correctWordBeforeCursor(it) }
        }
        idleRunnable = r
        handler.postDelayed(r, IDLE_DELAY_MS)
    }

    private fun cancelIdleTimer() {
        idleRunnable?.let { handler.removeCallbacks(it) }
        idleRunnable = null
    }

    private fun flashStatus(message: String) {
        if (!::statusText.isInitialized) return
        statusText.text = message
        statusClearRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { statusText.text = "" }
        statusClearRunnable = r
        handler.postDelayed(r, 2000)
    }

    // ---- OnKeyboardActionListener ----

    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        val ic = currentInputConnection ?: return
        when (primaryCode) {
            Keyboard.KEYCODE_SHIFT -> {
                capsOn = !capsOn
                qwertyKeyboard.isShifted = capsOn
                keyboardView.invalidateAllKeys()
            }
            Keyboard.KEYCODE_DELETE -> {
                ic.deleteSurroundingText(1, 0)
                scheduleIdleCorrection()
            }
            Keyboard.KEYCODE_MODE_CHANGE -> {
                currentMode = if (currentMode == MODE_LETTERS) MODE_SYMBOLS else MODE_LETTERS
                keyboardView.keyboard = if (currentMode == MODE_LETTERS) qwertyKeyboard else symbolsKeyboard
                keyboardView.invalidateAllKeys()
            }
            CODE_ENTER -> {
                correctWordBeforeCursor(ic)
                val info = currentInputEditorInfo
                val isMultiline = info != null &&
                    (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
                val imeAction = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
                if (!isMultiline && imeAction != null &&
                    imeAction != EditorInfo.IME_ACTION_NONE && imeAction != EditorInfo.IME_ACTION_UNSPECIFIED
                ) {
                    ic.performEditorAction(imeAction)
                } else {
                    ic.commitText("\n", 1)
                }
                scheduleIdleCorrection()
            }
            CODE_SPACE -> {
                correctWordBeforeCursor(ic)
                ic.commitText(" ", 1)
                scheduleIdleCorrection()
            }
            else -> {
                var code = primaryCode
                if (capsOn && code in 97..122) code -= 32
                ic.commitText(code.toChar().toString(), 1)
                if (capsOn && currentMode == MODE_LETTERS) {
                    capsOn = false
                    qwertyKeyboard.isShifted = false
                    keyboardView.invalidateAllKeys()
                }
                scheduleIdleCorrection()
            }
        }
    }

    override fun onPress(primaryCode: Int) {}
    override fun onRelease(primaryCode: Int) {}
    override fun onText(text: CharSequence?) {}
    override fun swipeLeft() {}
    override fun swipeRight() {}
    override fun swipeDown() {}
    override fun swipeUp() {}
}
