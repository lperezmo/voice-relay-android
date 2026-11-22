package com.lperezmo.voicerelay

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** The colours and small parts the two timer cards share. */
private object TimerCardUi {
    const val COLOR_DIM = 0xFFA9ACB5.toInt()
    const val COLOR_LIGHT = 0xFFE6E6EA.toInt()
    const val COLOR_OUTLINE = 0xFF74777F.toInt()
    const val COLOR_STOP = 0xFFF2B8B5.toInt()
    /** White at 12%. */
    const val COLOR_CARD_EDGE = 0x1FFFFFFF
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

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
}

/**
 * The card a tap on the timer pill opens: every timer, soonest first, with its
 * label, a big countdown, +1 (a minute more) and × (stop it, in one tap). With
 * nothing running it says how to start one. A touch outside calls [onOutside].
 */
class TimerListCard(context: Context) : LinearLayout(context) {

    var onPlus: ((id: Int) -> Unit)? = null
    var onStop: ((id: Int) -> Unit)? = null
    var onOutside: (() -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private var shownIds: List<Int>? = null
    private val countdowns = mutableListOf<TextView>()
    private val names = mutableListOf<TextView>()

    init {
        orientation = VERTICAL
        background = TimerCardUi.cardBackground(dp, 24f, COLOR_CARD)
        setPadding((16 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
        contentDescription = "Running timers"
    }

    /** Shows [sorted] as they stand at [now]. Rows are rebuilt only when the set of timers changes. */
    fun show(sorted: List<TimerEntry>, now: Long) {
        val shown = sorted.take(MAX_ROWS)
        val ids = shown.map { it.id }
        if (ids != shownIds) rebuild(shown, sorted.size - shown.size, now)
        shownIds = ids
        shown.forEachIndexed { i, t ->
            names[i].text = t.label
            countdowns[i].text = TimerText.clock(t.secondsLeft(now))
            countdowns[i].setTextColor(if (t.done(now)) PillSkin.COLOR_GOLD else TimerCardUi.COLOR_LIGHT)
        }
    }

    private fun rebuild(shown: List<TimerEntry>, more: Int, now: Long) {
        removeAllViews()
        names.clear()
        countdowns.clear()
        if (shown.isEmpty()) {
            addView(note("No timers running. Hold the pill to start one."))
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
            row.addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(name)
                addView(countdown)
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            row.addView(
                TimerCardUi.roundButton(context, "+1", "Add a minute to ${t.label}", 44, TimerCardUi.COLOR_LIGHT, TimerCardUi.COLOR_OUTLINE) {
                    onPlus?.invoke(t.id)
                },
                LayoutParams((44 * dp).toInt(), (44 * dp).toInt()).apply { marginStart = (8 * dp).toInt() },
            )
            row.addView(
                TimerCardUi.roundButton(
                    context, "✕", (if (t.done(now)) "Dismiss " else "Stop ") + t.label, 44,
                    TimerCardUi.COLOR_STOP, TimerCardUi.COLOR_STOP,
                ) { onStop?.invoke(t.id) },
                LayoutParams((44 * dp).toInt(), (44 * dp).toInt()).apply { marginStart = (8 * dp).toInt() },
            )
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

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutside?.invoke()
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    companion object {
        const val W_DP = 272
        private const val MAX_ROWS = 6
        /** #1C1C1E at 96%. */
        private const val COLOR_CARD = 0xF51C1C1E.toInt()
    }
}

/**
 * The card "···" on the fan opens, for a time of your own: −5, −1, a minutes
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
            onCancel?.invoke()
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
