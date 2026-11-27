package com.lperezmo.voicerelay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** The colours and small parts the two timer cards share. */
private object TimerCardUi {
    const val COLOR_DIM = 0xFFA9ACB5.toInt()
    const val COLOR_LIGHT = 0xFFE6E6EA.toInt()
    const val COLOR_OUTLINE = 0xFF74777F.toInt()
    const val COLOR_STOP = 0xFFF2B8B5.toInt()
    /** The pills' light edge, white at 20%. */
    const val COLOR_CARD_EDGE = PillSkin.COLOR_EDGE
    val MEDIUM: Typeface = PillSkin.MEDIUM

    fun cardBackground(dp: Float, radiusDp: Float, color: Int) = GradientDrawable().apply {
        cornerRadius = radiusDp * dp
        setColor(color)
        setStroke(dp.toInt().coerceAtLeast(1), COLOR_CARD_EDGE)
    }

    /** A round outlined button, [sizeDp] across. */
    fun roundButton(context: Context, label: String, description: String, sizeDp: Int, color: Int, outline: Int, onClick: () -> Unit) =
        TextView(context).apply {
            val dp = resources.displayMetrics.density
            text = label
            contentDescription = description
            gravity = Gravity.CENTER
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (sizeDp >= 44) 13f else 14f)
            typeface = MEDIUM
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0)
                setStroke(dp.toInt().coerceAtLeast(1), outline)
            }
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams((sizeDp * dp).toInt(), (sizeDp * dp).toInt())
        }

    /** A small dim icon ([ICON_DP]) in a round touch target ([ICON_TOUCH_DP]) that lights up while pressed. */
    fun iconButton(context: Context, icon: Int, onClick: () -> Unit) = ImageView(context).apply {
        val dp = resources.displayMetrics.density
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(COLOR_DIM)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = ((ICON_TOUCH_DP - ICON_DP) / 2 * dp).toInt()
        setPadding(pad, pad, pad, pad)
        background = RippleDrawable(
            ColorStateList.valueOf(COLOR_PRESSED), null,
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFFFFFF.toInt())
            },
        )
        isClickable = true
        setOnClickListener { onClick() }
    }

    const val ICON_DP = 16
    const val ICON_TOUCH_DP = 28
    /** White at 20%, under a pressed icon. */
    private const val COLOR_PRESSED = 0x33FFFFFF
}

/**
 * What a tap on the timer pill opens: the running timers ([list]) over the timer track
 * ([track]), where a V starts one. A touch outside the panel calls [onOutside].
 */
class TimerPanel(context: Context) : LinearLayout(context) {

    val list = TimerListCard(context)
    val track = TimerTrackView(context)
    var onOutside: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        val dp = resources.displayMetrics.density
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(track, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = (GAP_DP * dp).toInt()
        })
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutside?.invoke()
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    companion object {
        const val W_DP = VTrackView.W_DP
        /** Space between the running timers and the track. */
        const val GAP_DP = 8
    }
}

/**
 * The top of the timer panel: every timer, soonest first, with its label, a big
 * countdown, +1 (a minute more) and × (stop it, in one tap). With nothing running,
 * one short line says so.
 */
class TimerListCard(context: Context) : LinearLayout(context) {

    var onPlus: ((id: Int) -> Unit)? = null
    var onStop: ((id: Int) -> Unit)? = null
    /** The pencil after a timer's name: type a new one. */
    var onRename: ((id: Int) -> Unit)? = null
    /** The microphone after a timer's name: say a new one. */
    var onRenameVoice: ((id: Int) -> Unit)? = null

    /** Which rename icons each row shows ([TimerRename.icons]); none by default. */
    var icons = TimerRename.Icons(pencil = false, mic = false)
        set(v) {
            if (v == field) return
            field = v
            shownIds = null
        }

    /** The most timers listed; the rest are counted in a last line. Lower on a short screen, so the card fits. */
    var maxRows = MAX_ROWS
        set(v) {
            field = v.coerceIn(1, MAX_ROWS)
            shownIds = null
        }

    private val dp = resources.displayMetrics.density
    private var shownIds: List<Int>? = null
    private val countdowns = mutableListOf<TextView>()
    private val names = mutableListOf<TextView>()
    private val pluses = mutableListOf<View>()
    private val stops = mutableListOf<View>()
    private val pencils = mutableListOf<View?>()
    private val mics = mutableListOf<View?>()

    init {
        orientation = VERTICAL
        background = TimerCardUi.cardBackground(dp, 22f, COLOR_CARD)
        setPadding((16 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
        contentDescription = "Running timers"
    }

    /** Shows [sorted] as they stand at [now]. Rows are rebuilt only when the set of timers changes. */
    fun show(sorted: List<TimerEntry>, now: Long) {
        val shown = sorted.take(maxRows)
        val ids = shown.map { it.id }
        if (ids != shownIds) rebuild(shown, sorted.size - shown.size, now)
        shownIds = ids
        shown.forEachIndexed { i, t ->
            names[i].text = t.label
            countdowns[i].text = TimerText.clock(t.secondsLeft(now))
            countdowns[i].setTextColor(if (t.done(now)) PillSkin.COLOR_GOLD else TimerCardUi.COLOR_LIGHT)
            // Every tick, so a renamed or finished timer is read out as it is now (unchanged text costs nothing).
            pluses[i].contentDescription = "Add a minute to ${t.label}"
            stops[i].contentDescription = (if (t.done(now)) "Dismiss " else "Stop ") + t.label
            pencils[i]?.contentDescription = "Rename ${t.label}"
            mics[i]?.contentDescription = "Say a new name for ${t.label}"
        }
    }

    private fun rebuild(shown: List<TimerEntry>, more: Int, now: Long) {
        removeAllViews()
        names.clear()
        countdowns.clear()
        pluses.clear()
        stops.clear()
        pencils.clear()
        mics.clear()
        if (shown.isEmpty()) {
            addView(note("No timers running."))
            return
        }
        shown.forEachIndexed { i, t ->
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val name = TextView(context).apply {
                setTextColor(TimerCardUi.COLOR_DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val countdown = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22f)
                typeface = TimerCardUi.MEDIUM
            }
            names += name
            countdowns += countdown
            // The name, then the icons right after its text: the line is only as wide as it needs,
            // and a long name gives way (weight 1) so it ellipsizes before it pushes the icons out.
            val nameLine = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(name, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
            val touch = (TimerCardUi.ICON_TOUCH_DP * dp).toInt()
            val pencil = if (icons.pencil) TimerCardUi.iconButton(context, R.drawable.ic_timer_pencil) { onRename?.invoke(t.id) } else null
            val mic = if (icons.mic) TimerCardUi.iconButton(context, R.drawable.ic_timer_mic) { onRenameVoice?.invoke(t.id) } else null
            pencil?.let { nameLine.addView(it, LayoutParams(touch, touch).apply { marginStart = (2 * dp).toInt() }) }
            mic?.let { nameLine.addView(it, LayoutParams(touch, touch)) }
            pencils += pencil
            mics += mic
            val withIcons = pencil != null || mic != null
            row.addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(nameLine, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
                // The icons make the name line taller; the countdown tucks up under it so the row grows little.
                addView(countdown, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    if (withIcons) topMargin = -(COUNTDOWN_TUCK_DP * dp).toInt()
                })
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            val plus = TimerCardUi.roundButton(context, "+1", "Add a minute to ${t.label}", 44, TimerCardUi.COLOR_LIGHT, TimerCardUi.COLOR_OUTLINE) {
                onPlus?.invoke(t.id)
            }
            val stop = TimerCardUi.roundButton(
                context, "✕", (if (t.done(now)) "Dismiss " else "Stop ") + t.label, 44,
                TimerCardUi.COLOR_STOP, TimerCardUi.COLOR_STOP,
            ) { onStop?.invoke(t.id) }
            pluses += plus
            stops += stop
            row.addView(plus, LayoutParams((44 * dp).toInt(), (44 * dp).toInt()).apply { marginStart = (8 * dp).toInt() })
            row.addView(stop, LayoutParams((44 * dp).toInt(), (44 * dp).toInt()).apply { marginStart = (8 * dp).toInt() })
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) topMargin = (8 * dp).toInt()
            })
        }
        if (more > 0) addView(note("And $more more."))
    }

    private fun note(message: String) = TextView(context).apply {
        text = message
        setTextColor(TimerCardUi.COLOR_DIM)
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
    }

    companion object {
        const val MAX_ROWS = 6
        /** One row with the space under it, and the card's own padding plus the "more" line. */
        const val ROW_DP = 52
        /** A row with the rename icons: their 28 dp line over the countdown, less the tuck, and the space under it. */
        const val ROW_RENAME_DP = 60
        const val EXTRA_DP = 56
        /** How far the countdown tucks up under a name line that has icons. */
        private const val COUNTDOWN_TUCK_DP = 6

        /** How tall one row is, for working out how many fit. */
        fun rowDp(icons: TimerRename.Icons) = if (icons.pencil || icons.mic) ROW_RENAME_DP else ROW_DP
        /** #1C1C1E at 96%. */
        private const val COLOR_CARD = 0xF51C1C1E.toInt()
    }
}

/**
 * The card "Own" on the timer track opens, for a time of your own: −5, −1, a minutes
 * input you can type in, +1, +5, then Cancel and Start. A touch outside, or
 * Back, calls [onCancel].
 */
class TimerOwnCard(context: Context, startMinutes: Int) : LinearLayout(context) {

    var onStart: ((minutes: Int) -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val input: EditText

    /** What the input holds now, kept inside 1 to 1440. */
    val minutes: Int get() = TimerText.clampMinutes(input.text.toString().toIntOrNull() ?: TimerText.MIN_MINUTES)

    init {
        orientation = VERTICAL
        background = TimerCardUi.cardBackground(dp, 22f, COLOR_CARD)
        setPadding((14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
        contentDescription = "Your own time"
        // Takes the first focus, so the keyboard only comes up when the input is tapped.
        isFocusableInTouchMode = true

        input = EditText(context).apply {
            setText(TimerText.clampMinutes(startMinutes).toString())
            contentDescription = "Minutes"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(4))
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(PillSkin.COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24f)
            typeface = TimerCardUi.MEDIUM
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            background = null
            setPadding(0, 0, 0, 0)
            minWidth = (44 * dp).toInt()
            isLongClickable = false
            setTextIsSelectable(false)
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) onStart?.invoke(minutes)
                action == EditorInfo.IME_ACTION_DONE
            }
        }
        val steps = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun space() = steps.addView(View(context), LayoutParams(0, 1, 1f))
        steps.addView(step("−5", "5 minutes less", -5))
        space()
        steps.addView(step("−1", "1 minute less", -1))
        space()
        steps.addView(input, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        steps.addView(TextView(context).apply {
            text = "min"
            setTextColor(TimerCardUi.COLOR_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            setPadding((4 * dp).toInt(), 0, 0, 0)
        })
        space()
        steps.addView(step("+1", "1 minute more", 1))
        space()
        steps.addView(step("+5", "5 minutes more", 5))
        addView(steps, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val actions = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END
        }
        actions.addView(action("Cancel", filled = false) { onCancel?.invoke() })
        actions.addView(
            action("Start", filled = true) { onStart?.invoke(minutes) },
            LayoutParams(LayoutParams.WRAP_CONTENT, (40 * dp).toInt()).apply { marginStart = (8 * dp).toInt() },
        )
        addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = (10 * dp).toInt()
        })
    }

    private fun step(label: String, description: String, by: Int) =
        TimerCardUi.roundButton(context, label, description, 40, TimerCardUi.COLOR_LIGHT, TimerCardUi.COLOR_OUTLINE) {
            input.setText(TimerText.clampMinutes(minutes + by).toString())
            input.setSelection(input.text.length)
        }

    private fun action(label: String, filled: Boolean, onClick: () -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        typeface = TimerCardUi.MEDIUM
        setTextColor(if (filled) PillSkin.COLOR_ON_TIMER else PillSkin.COLOR_TIMER)
        background = GradientDrawable().apply {
            cornerRadius = 20 * dp
            setColor(if (filled) PillSkin.COLOR_TIMER else 0)
        }
        setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, (40 * dp).toInt())
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            // While the minutes are being typed, the keyboard's keys are touches outside
            // this card too: only Cancel, Start or Back close it then.
            if (!input.hasFocus()) onCancel?.invoke()
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onCancel?.invoke()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val W_DP = 262
        /** #1C1C1E at 95%. */
        private const val COLOR_CARD = 0xF21C1C1E.toInt()
    }
}

/**
 * The card the pencil in a timer's row opens: a line saying so, the timer's name in a
 * text field (all selected, so typing replaces it), then Cancel and Save. Done on the
 * keyboard saves too. A touch outside while the keyboard is hidden, or Back, calls
 * [onCancel].
 */
class TimerRenameCard(context: Context, startName: String) : LinearLayout(context) {

    var onSave: ((name: String) -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val input: EditText

    /** What the field holds now. */
    val name: String get() = input.text.toString()

    init {
        orientation = VERTICAL
        background = TimerCardUi.cardBackground(dp, 22f, COLOR_CARD)
        setPadding((16 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
        contentDescription = "Rename timer"

        addView(TextView(context).apply {
            text = "Rename timer"
            setTextColor(TimerCardUi.COLOR_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        })
        input = EditText(context).apply {
            setText(startName)
            contentDescription = "Timer name"
            hint = "Name"
            setHintTextColor(TimerCardUi.COLOR_OUTLINE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(Timers.MAX_LABEL))
            imeOptions = EditorInfo.IME_ACTION_DONE
            maxLines = 1
            isSingleLine = true
            setTextColor(PillSkin.COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
            typeface = TimerCardUi.MEDIUM
            background = GradientDrawable().apply {
                cornerRadius = 12 * dp
                setColor(0)
                setStroke(dp.toInt().coerceAtLeast(1), TimerCardUi.COLOR_OUTLINE)
            }
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) onSave?.invoke(this@TimerRenameCard.name)
                action == EditorInfo.IME_ACTION_DONE
            }
        }
        addView(input, LayoutParams(LayoutParams.MATCH_PARENT, (44 * dp).toInt()).apply {
            topMargin = (8 * dp).toInt()
        })

        val actions = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END
        }
        actions.addView(action("Cancel", filled = false) { onCancel?.invoke() })
        actions.addView(
            action("Save", filled = true) { onSave?.invoke(this@TimerRenameCard.name) },
            LayoutParams(LayoutParams.WRAP_CONTENT, (40 * dp).toInt()).apply { marginStart = (8 * dp).toInt() },
        )
        addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = (10 * dp).toInt()
        })
    }

    /** Puts the cursor in the field with the name selected and asks for the keyboard: the pencil was tapped to type. */
    fun startTyping() {
        input.requestFocus()
        input.selectAll()
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    /** True while the keyboard is up for this card. Without insets, falls back to whether the field has focus. */
    private fun keyboardShowing(): Boolean =
        rootWindowInsets?.isVisible(WindowInsets.Type.ime()) ?: input.hasFocus()

    private fun action(label: String, filled: Boolean, onClick: () -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        typeface = TimerCardUi.MEDIUM
        setTextColor(if (filled) PillSkin.COLOR_ON_TIMER else PillSkin.COLOR_TIMER)
        background = GradientDrawable().apply {
            cornerRadius = 20 * dp
            setColor(if (filled) PillSkin.COLOR_TIMER else 0)
        }
        setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, (40 * dp).toInt())
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            // While the keyboard is up, its keys are touches outside this card too: only
            // Cancel, Save or Back close it then. Once it is hidden, a touch outside closes it.
            if (!keyboardShowing()) onCancel?.invoke()
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onCancel?.invoke()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val W_DP = TimerPanel.W_DP
        /** #1C1C1E at 95%. */
        private const val COLOR_CARD = 0xF21C1C1E.toInt()
    }
}
