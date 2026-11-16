package com.lperezmo.voicerelay.glyph

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.lperezmo.voicerelay.R
import kotlin.math.max

/**
 * The keyboard fallback of Ask the Page: a quiet keyboard button in the page's bottom-left
 * corner and, when it is tapped, a text field docked at the bottom above the system keyboard
 * (whatever keyboard the user has, swipe and voice input included: a plain [EditText] with a
 * text input type and the Send action). [PageController] decides what opening, closing and
 * sending mean; this class only owns the views, the keyboard and Back while the field is open.
 *
 * The draft stays in the field (in memory only) while the field is closed, and is cleared by
 * [clearDraft] after a send and by [dispose]. Nothing typed is logged or stored.
 */
class PageKeys(
    private val activity: Activity,
    private val root: FrameLayout,
    private val motion: Boolean,
    private val onOpen: () -> Unit,
    private val onClose: () -> Unit,
    private val onSend: (String) -> Unit,
) {
    companion object {
        /** The field stops taking text here; [com.lperezmo.voicerelay.Prompts.PAGE_TYPED_MAX] is the hard cap. */
        const val MAX_CHARS = 500
        private const val BAR_BG = 0xF0121316.toInt()
    }

    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()
    private val themed = ContextThemeWrapper(activity, R.style.Theme_Glyph)
    private val imm = activity.getSystemService(InputMethodManager::class.java)

    var isOpen = false
        private set
    private var disposed = false
    private var buttonWanted = false
    private var backCallback: Any? = null

    /** The corner button that opens the field. */
    private val button = ImageView(activity).apply {
        setImageResource(R.drawable.glyph_ic_keyboard)
        imageTintList = ColorStateList.valueOf(Ui.GOLD)
        contentDescription = "Type the question instead"
        val pad = dp(12f)
        setPadding(pad, pad, pad, pad)
        alpha = 0.7f
        visibility = View.GONE
        setOnClickListener { open() }
    }

    private val hideButton = ImageView(activity).apply {
        setImageResource(R.drawable.glyph_ic_keyboard)
        imageTintList = ColorStateList.valueOf(Ui.GOLD)
        contentDescription = "Close the keyboard and write by hand"
        val pad = dp(10f)
        setPadding(pad, pad, pad, pad)
        setOnClickListener { close() }
    }

    private val sendButton = ImageView(activity).apply {
        setImageResource(R.drawable.glyph_ic_send)
        imageTintList = ColorStateList.valueOf(Ui.GOLD)
        contentDescription = "Send the question"
        val pad = dp(10f)
        setPadding(pad, pad, pad, pad)
        setOnClickListener { submit() }
    }

    private val field = EditText(themed).apply {
        // a plain text field, so every keyboard offers its suggestions, swipe and voice input;
        // single-line input type keeps the Send action, and wrapping lets it grow to a few lines
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        setHorizontallyScrolling(false)
        maxLines = 4
        filters = arrayOf(InputFilter.LengthFilter(MAX_CHARS))
        hint = "Type your question"
        setHintTextColor(Ui.MUTED)
        setTextColor(Ui.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.DEFAULT
        background = null
        highlightColor = Color.argb(90, 242, 196, 109)
        textCursorDrawable = GradientDrawable().apply {
            setColor(Ui.GOLD)
            setSize(dp(2f), 1)
        }
        setPadding(dp(4f), dp(10f), dp(4f), dp(10f))
        contentDescription = "Your question"
    }

    private val bar = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        background = Ui.rounded(BAR_BG, 24 * density, dp(1f), Color.argb(130, 242, 196, 109))
        setPadding(dp(4f), dp(2f), dp(4f), dp(2f))
        elevation = 6 * density
        isClickable = true // taps on the bar do not reach the page under it
        visibility = View.GONE
        val icon = dp(44f)
        addView(hideButton, LinearLayout.LayoutParams(icon, icon))
        addView(field, LinearLayout.LayoutParams(0, -2, 1f))
        addView(sendButton, LinearLayout.LayoutParams(icon, icon))
    }

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = syncSend()
    }

    /** Add the views to [root] (on top of the page) and start following the insets. */
    fun attach() {
        root.addView(button, FrameLayout.LayoutParams(dp(48f), dp(48f), Gravity.BOTTOM or Gravity.START))
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        field.addTextChangedListener(watcher)
        field.setOnEditorActionListener { _, action, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER
            when {
                action == EditorInfo.IME_ACTION_SEND -> { submit(); true }
                enter && event!!.action == KeyEvent.ACTION_DOWN -> { submit(); true }
                enter -> true // the matching key up
                else -> false
            }
        }
        // clear of the navigation bar, the back gesture's edge and the home gesture's strip
        button.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val gestures = insets.getInsets(WindowInsets.Type.systemGestures())
            val mandatory = insets.getInsets(WindowInsets.Type.mandatorySystemGestures())
            (v.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = max(bars.left, gestures.left) + dp(8f)
                bottomMargin = max(bars.bottom, mandatory.bottom) + dp(12f)
            }
            v.requestLayout()
            insets
        }
        // docked above the keyboard when it is up, else above the navigation bar
        bar.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            (v.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = bars.left + dp(10f)
                rightMargin = bars.right + dp(10f)
                bottomMargin = max(bars.bottom, ime.bottom) + dp(8f)
            }
            v.requestLayout()
            insets
        }
        syncSend()
        root.requestApplyInsets()
    }

    /** Show the corner button (when the field is closed), or keep it hidden. */
    fun showButton(show: Boolean) {
        buttonWanted = show
        syncButton()
    }

    private fun syncButton() {
        if (disposed) return
        val show = buttonWanted && !isOpen
        button.animate().cancel()
        if (!show) {
            button.visibility = View.GONE
            return
        }
        if (button.visibility == View.VISIBLE) return
        button.visibility = View.VISIBLE
        if (motion) {
            button.alpha = 0f
            button.animate().alpha(0.7f).setDuration(200).start()
        } else {
            button.alpha = 0.7f
        }
    }

    /** Open the field, focus it and bring up the keyboard. */
    fun open() {
        if (disposed || isOpen) return
        isOpen = true
        syncButton()
        bar.visibility = View.VISIBLE
        bar.requestApplyInsets()
        field.requestFocus()
        field.setSelection(field.text.length)
        field.post { if (!disposed && isOpen) imm?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT) }
        registerBack()
        onOpen()
    }

    /** Hide the field and the keyboard and go back to handwriting; the draft stays. [quiet] skips [onClose]. */
    fun close(quiet: Boolean = false) {
        if (!isOpen) return
        isOpen = false
        unregisterBack()
        imm?.hideSoftInputFromWindow(field.windowToken, 0)
        field.clearFocus()
        bar.visibility = View.GONE
        syncButton()
        if (!quiet && !disposed) onClose()
    }

    fun clearDraft() {
        field.text?.clear()
    }

    /** Back from the activity's legacy path: closes the field if it is open. */
    fun handleBack(): Boolean {
        if (!isOpen) return false
        close()
        return true
    }

    private fun submit() {
        if (disposed || !isOpen) return
        val text = field.text?.toString().orEmpty()
        if (text.isBlank()) {
            field.performHapticFeedback(android.view.HapticFeedbackConstants.REJECT)
            return
        }
        onSend(text)
    }

    private fun syncSend() {
        val ready = field.text?.isNotBlank() == true
        sendButton.alpha = if (ready) 1f else 0.35f
    }

    // Back on Android 13+ with predictive back: only registered while the field is open.
    private fun registerBack() {
        if (Build.VERSION.SDK_INT < 33 || backCallback != null) return
        val cb = OnBackInvokedCallback { close() }
        activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        backCallback = cb
    }

    private fun unregisterBack() {
        if (Build.VERSION.SDK_INT < 33) return
        val cb = backCallback as? OnBackInvokedCallback ?: return
        backCallback = null
        try {
            activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
        } catch (_: Exception) {}
    }

    /** The canvas is closing: keyboard down, listeners off, draft gone. Safe to call more than once. */
    fun dispose() {
        if (disposed) return
        close(quiet = true)
        disposed = true
        unregisterBack()
        button.animate().cancel()
        button.setOnClickListener(null)
        hideButton.setOnClickListener(null)
        sendButton.setOnClickListener(null)
        field.removeTextChangedListener(watcher)
        field.setOnEditorActionListener(null)
        button.setOnApplyWindowInsetsListener(null)
        bar.setOnApplyWindowInsetsListener(null)
        field.text?.clear()
        button.visibility = View.GONE
        bar.visibility = View.GONE
    }
}
