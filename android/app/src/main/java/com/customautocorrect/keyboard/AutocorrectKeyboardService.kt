package com.customautocorrect.keyboard

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.random.Random

class AutocorrectKeyboardService : InputMethodService(), android.inputmethodservice.KeyboardView.OnKeyboardActionListener {

    private enum class Page { LETTERS, SYMBOLS, EMOJI, CLIPBOARD }
    private enum class OneHandedState { OFF, RIGHT, LEFT }

    /** What the in-progress word currently shows as, and the myoom to save if it was generated. */
    private data class Preview(val display: String, val toSave: String?)

    companion object {
        private const val MODE_LETTERS = 0
        private const val MODE_SYMBOLS = 1
        private const val CODE_ENTER = 10
        private const val CODE_SPACE = 32
        private const val CODE_EMOJI = -10
        private const val UI_PREFS = "keyboard_ui_prefs"
        private const val KEY_ONE_HANDED = "one_handed_state"
        private const val KEYPRESS_VIBRATION_MS = 18L
    }

    private lateinit var keyboardView: GboardKeyboardView
    private lateinit var statusText: TextView
    private lateinit var qwertyKeyboard: Keyboard
    private lateinit var symbolsKeyboard: Keyboard
    private var currentMode = MODE_LETTERS
    private var currentPage = Page.LETTERS
    private var capsOn = false

    private lateinit var emojiPageView: View
    private lateinit var clipboardPageView: View
    private lateinit var emojiCategoryRow: LinearLayout
    private lateinit var emojiGrid: RecyclerView
    private lateinit var emojiAdapter: EmojiAdapter
    private lateinit var clipboardList: RecyclerView
    private lateinit var clipboardAdapter: ClipboardAdapter
    private lateinit var clipboardEmptyHint: TextView
    private lateinit var leftSpacer: View
    private lateinit var pageContainer: FrameLayout
    private lateinit var rightSpacer: View

    private var oneHandedState = OneHandedState.OFF

    private val handler = Handler(Looper.getMainLooper())
    private var statusClearRunnable: Runnable? = null

    private lateinit var systemClipboard: ClipboardManager
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClipboard() }

    // The word being typed right now, exactly as typed. The editor shows its myoom (as composing
    // text) instead, so whatever is in the text box is always already converted.
    private val typedWord = StringBuilder()
    private var preview: Preview? = null

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    override fun onCreate() {
        super.onCreate()
        oneHandedState = loadOneHandedState()
        systemClipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        systemClipboard.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        systemClipboard.removePrimaryClipChangedListener(clipListener)
    }

    private fun loadOneHandedState(): OneHandedState {
        val name = getSharedPreferences(UI_PREFS, MODE_PRIVATE).getString(KEY_ONE_HANDED, OneHandedState.OFF.name)
        return try {
            OneHandedState.valueOf(name ?: OneHandedState.OFF.name)
        } catch (e: IllegalArgumentException) {
            OneHandedState.OFF
        }
    }

    private fun saveOneHandedState() {
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit()
            .putString(KEY_ONE_HANDED, oneHandedState.name)
            .apply()
    }

    private fun captureClipboard() {
        val clip = systemClipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
        ClipboardStore.addEntry(this, text)
        if (::clipboardAdapter.isInitialized && currentPage == Page.CLIPBOARD) refreshClipboard()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.keyboard_view, null)

        keyboardView = root.findViewById(R.id.keyboard_view)
        statusText = root.findViewById(R.id.status_text)
        leftSpacer = root.findViewById(R.id.leftSpacer)
        pageContainer = root.findViewById(R.id.pageContainer)
        rightSpacer = root.findViewById(R.id.rightSpacer)
        emojiPageView = root.findViewById(R.id.emojiPage)
        clipboardPageView = root.findViewById(R.id.clipboardPage)

        qwertyKeyboard = Keyboard(this, R.xml.qwerty)
        symbolsKeyboard = Keyboard(this, R.xml.symbols)
        keyboardView.keyboard = qwertyKeyboard
        keyboardView.setOnKeyboardActionListener(this)
        currentMode = MODE_LETTERS

        setupToolbar(root)
        setupEmojiPage()
        setupClipboardPage()
        applyOneHandedLayout()

        return root
    }

    private fun setupToolbar(root: View) {
        root.findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        root.findViewById<ImageButton>(R.id.btnEmoji).setOnClickListener { showPage(Page.EMOJI) }
        root.findViewById<ImageButton>(R.id.btnClipboard).setOnClickListener { showPage(Page.CLIPBOARD) }
        root.findViewById<ImageButton>(R.id.btnOneHanded).setOnClickListener { cycleOneHanded() }
    }

    private fun setupEmojiPage() {
        emojiCategoryRow = emojiPageView.findViewById(R.id.emojiCategoryRow)
        emojiGrid = emojiPageView.findViewById(R.id.emojiGrid)
        emojiGrid.layoutManager = GridLayoutManager(this, 8)
        emojiAdapter = EmojiAdapter { emoji ->
            val ic = currentInputConnection ?: return@EmojiAdapter
            vibrateKeypress()
            finishWord(ic)
            ic.commitText(emoji, 1)
        }
        emojiGrid.adapter = emojiAdapter

        val density = resources.displayMetrics.density
        EmojiData.categories.forEachIndexed { index, category ->
            val tab = TextView(this).apply {
                text = category.label
                textSize = 20f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams((44 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
                setOnClickListener { emojiAdapter.submitList(category.emojis) }
            }
            emojiCategoryRow.addView(tab)
            if (index == 0) emojiAdapter.submitList(category.emojis)
        }

        emojiPageView.findViewById<View>(R.id.emojiBackBtn).setOnClickListener { switchToLetters() }
        emojiPageView.findViewById<ImageButton>(R.id.emojiBackspaceBtn).setOnClickListener {
            val ic = currentInputConnection ?: return@setOnClickListener
            vibrateKeypress()
            backspace(ic)
        }
    }

    private fun setupClipboardPage() {
        clipboardList = clipboardPageView.findViewById(R.id.clipboardList)
        clipboardEmptyHint = clipboardPageView.findViewById(R.id.clipboardEmptyHint)
        clipboardList.layoutManager = LinearLayoutManager(this)
        clipboardAdapter = ClipboardAdapter(
            onItemClick = { text ->
                val ic = currentInputConnection ?: return@ClipboardAdapter
                finishWord(ic)
                ic.commitText(text, 1)
            },
            onRemoveClick = { text ->
                ClipboardStore.removeEntry(this, text)
                refreshClipboard()
            }
        )
        clipboardList.adapter = clipboardAdapter

        clipboardPageView.findViewById<View>(R.id.clipboardBackBtn).setOnClickListener { switchToLetters() }
        clipboardPageView.findViewById<View>(R.id.clipboardClearBtn).setOnClickListener {
            ClipboardStore.clear(this)
            refreshClipboard()
        }
    }

    private fun refreshClipboard() {
        val items = ClipboardStore.load(this)
        clipboardAdapter.submitList(items)
        clipboardList.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        clipboardEmptyHint.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showPage(page: Page) {
        currentPage = page
        keyboardView.visibility = if (page == Page.LETTERS || page == Page.SYMBOLS) View.VISIBLE else View.GONE
        emojiPageView.visibility = if (page == Page.EMOJI) View.VISIBLE else View.GONE
        clipboardPageView.visibility = if (page == Page.CLIPBOARD) View.VISIBLE else View.GONE
        if (page == Page.CLIPBOARD) refreshClipboard()
    }

    private fun switchToLetters() {
        currentMode = MODE_LETTERS
        keyboardView.keyboard = qwertyKeyboard
        showPage(Page.LETTERS)
    }

    private fun cycleOneHanded() {
        oneHandedState = when (oneHandedState) {
            OneHandedState.OFF -> OneHandedState.RIGHT
            OneHandedState.RIGHT -> OneHandedState.LEFT
            OneHandedState.LEFT -> OneHandedState.OFF
        }
        saveOneHandedState()
        applyOneHandedLayout()
    }

    private fun applyOneHandedLayout() {
        val (leftWeight, pageWeight, rightWeight) = when (oneHandedState) {
            OneHandedState.OFF -> Triple(0f, 1f, 0f)
            OneHandedState.RIGHT -> Triple(0.25f, 0.75f, 0f)
            OneHandedState.LEFT -> Triple(0f, 0.75f, 0.25f)
        }
        (leftSpacer.layoutParams as LinearLayout.LayoutParams).weight = leftWeight
        (pageContainer.layoutParams as LinearLayout.LayoutParams).weight = pageWeight
        (rightSpacer.layoutParams as LinearLayout.LayoutParams).weight = rightWeight
        leftSpacer.requestLayout()
        pageContainer.requestLayout()
        rightSpacer.requestLayout()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        resetWord()
        currentMode = MODE_LETTERS
        capsOn = false
        keyboardView.keyboard = qwertyKeyboard
        qwertyKeyboard.isShifted = false
        keyboardView.invalidateAllKeys()
        showPage(Page.LETTERS)
        applyOneHandedLayout()
        statusText.text = ""
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        currentInputConnection?.let { lockInWord(it) }
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // The user moved the cursor away from the word being typed: keep its myoom where it is.
        if (typedWord.isNotEmpty() && candidatesEnd != -1 &&
            (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)
        ) {
            currentInputConnection?.let { lockInWord(it) }
        }
    }

    // ---- Myoom engine ----

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\''

    private fun extractTrailingWord(text: String): String {
        var start = text.length
        while (start > 0 && isWordChar(text[start - 1])) start--
        return text.substring(start)
    }

    private fun previewFor(word: String): Preview {
        val saved = DictionaryStore.lookup(this, word)
        return when {
            saved != null && MyoomGenerator.isMyoomish(saved) -> Preview(MyoomGenerator.matchCase(word, saved), null)
            saved != null -> Preview(saved, null)
            !MyoomGenerator.isEligible(word) -> Preview(word, null)
            else -> {
                // Seeded by the word so the preview doesn't flicker between variants as you type.
                val generated = MyoomGenerator.generateUnique(word, Random(word.lowercase().hashCode())) {
                    DictionaryStore.isReplacementTaken(this, it)
                }
                Preview(MyoomGenerator.matchCase(word, generated), generated)
            }
        }
    }

    private fun typeWordChar(ic: InputConnection, c: Char) {
        typedWord.append(c)
        val p = previewFor(typedWord.toString())
        preview = p
        ic.setComposingText(p.display, 1)
    }

    private fun backspace(ic: InputConnection) {
        if (typedWord.isEmpty()) {
            ic.deleteSurroundingText(1, 0)
            return
        }
        typedWord.deleteCharAt(typedWord.length - 1)
        if (typedWord.isEmpty()) {
            preview = null
            ic.commitText("", 1)
        } else {
            val p = previewFor(typedWord.toString())
            preview = p
            ic.setComposingText(p.display, 1)
        }
    }

    /** Saves the in-progress word's myoom and leaves it in the text box as normal text. */
    private fun lockInWord(ic: InputConnection) {
        val p = preview
        val word = typedWord.toString()
        resetWord()
        if (p == null || word.isEmpty()) return
        p.toSave?.let { DictionaryStore.upsert(this, word, it) }
        ic.commitText(p.display, 1)
        if (p.display != word) flashStatus("\"$word\" → \"${p.display}\"")
    }

    private fun resetWord() {
        typedWord.setLength(0)
        preview = null
    }

    /**
     * Called before any separator (space, enter, punctuation, emoji, paste). Locks in the word
     * being typed, or -- if nothing is being typed, e.g. the cursor was moved into existing
     * text -- applies a saved rule to the word before the cursor.
     */
    private fun finishWord(ic: InputConnection) {
        if (typedWord.isNotEmpty()) {
            lockInWord(ic)
            return
        }
        if (isSensitiveField()) return
        val text = ic.getTextBeforeCursor(64, 0)?.toString() ?: return
        val word = extractTrailingWord(text)
        if (word.isEmpty()) return
        val saved = DictionaryStore.lookup(this, word) ?: return
        val replacement = if (MyoomGenerator.isMyoomish(saved)) MyoomGenerator.matchCase(word, saved) else saved
        if (replacement == word) return
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText(replacement, 1)
        flashStatus("\"$word\" → \"$replacement\"")
    }

    private fun isSensitiveField(): Boolean {
        val type = currentInputEditorInfo?.inputType ?: return false
        if ((type and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return true
        return when (type and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI -> true
            else -> false
        }
    }

    private fun flashStatus(message: String) {
        if (!::statusText.isInitialized) return
        statusText.text = message
        statusClearRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { statusText.text = "" }
        statusClearRunnable = r
        handler.postDelayed(r, 2000)
    }

    private fun vibrateKeypress() {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(KEYPRESS_VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(KEYPRESS_VIBRATION_MS)
            }
        } catch (e: Exception) {
            // Some devices/emulators have no usable vibrator; typing must still work.
        }
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
            Keyboard.KEYCODE_DELETE -> backspace(ic)
            Keyboard.KEYCODE_MODE_CHANGE -> {
                currentMode = if (currentMode == MODE_LETTERS) MODE_SYMBOLS else MODE_LETTERS
                keyboardView.keyboard = if (currentMode == MODE_LETTERS) qwertyKeyboard else symbolsKeyboard
                showPage(if (currentMode == MODE_LETTERS) Page.LETTERS else Page.SYMBOLS)
            }
            CODE_EMOJI -> showPage(Page.EMOJI)
            CODE_ENTER -> {
                finishWord(ic)
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
            }
            CODE_SPACE -> {
                finishWord(ic)
                ic.commitText(" ", 1)
            }
            else -> {
                var code = primaryCode
                if (capsOn && code in 97..122) code -= 32
                val typedChar = code.toChar()
                if (isWordChar(typedChar) && !isSensitiveField()) {
                    typeWordChar(ic, typedChar)
                } else {
                    if (!isWordChar(typedChar)) finishWord(ic)
                    ic.commitText(typedChar.toString(), 1)
                }
                if (capsOn && currentMode == MODE_LETTERS) {
                    capsOn = false
                    qwertyKeyboard.isShifted = false
                    keyboardView.invalidateAllKeys()
                }
            }
        }
    }

    override fun onPress(primaryCode: Int) {
        vibrateKeypress()
    }

    override fun onRelease(primaryCode: Int) {}
    override fun onText(text: CharSequence?) {}
    override fun swipeLeft() {}
    override fun swipeRight() {}
    override fun swipeDown() {}
    override fun swipeUp() {}
}
