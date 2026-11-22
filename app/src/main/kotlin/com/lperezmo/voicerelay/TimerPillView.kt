package com.lperezmo.voicerelay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * The timer pill: "Timer" when nothing runs, else the countdown of the timer
 * that ends first, with a count when there are several, and gold once one is
 * done. A tap lists the running timers and a hold fans out times to start one;
 * the view only reports the touches ([onPress]) and [FloatingPills] decides,
 * through [TimerPress]. The drawn pill is at least [PillLayout.TIMER_PILL_MIN_W_DP]
 * wide; the view is [PillLayout.TIMER_W_DP] x [PillLayout.TIMER_H_DP] so the
 * touch target reaches 44 dp.
 */
class TimerPillView(context: Context) : View(context) {

    /** The pill was clicked without a touch (a screen reader's double tap): show what is running. */
    var onTap: (() -> Unit)? = null

    /** Every touch on the pill, with where it is on the screen. */
    var onPress: ((action: Int, rawX: Float, rawY: Float) -> Unit)? = null

    /** Stacked over the volume pill, the pill is drawn at the bottom of the view; beside the button, in its middle. */
    var stacked = false
        set(v) { field = v; invalidate() }

    /** True while the fan or a card is open: the edge takes the timer colour. */
    var active = false
        set(v) { field = v; invalidate() }

    private val dp = context.resources.displayMetrics.density
    private var face = TimerText.Face("Timer", 0, TimerText.State.IDLE)

    private val skin = PillSkin(dp)
    private val text = skin.text
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = ICON_DP * dp * 3f / 24f
    }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PillSkin.COLOR_TIMER }
    private val badgeText = skin.label(8f).apply {
        color = PillSkin.COLOR_ON_TIMER
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    init {
        isHapticFeedbackEnabled = true
        isClickable = true
        contentDescription = "Timer. Hold, then slide to a time."
    }

    /** [description] is for screen readers; it is only taken when the timers change, not on every tick. */
    fun update(new: TimerText.Face, description: String) {
        if (new == face) return
        if (new.count != face.count || new.state != face.state) contentDescription = description
        face = new
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) =
        setMeasuredDimension((PillLayout.TIMER_W_DP * dp).toInt(), (PillLayout.TIMER_H_DP * dp).toInt())

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                onPress?.invoke(event.actionMasked, event.rawX, event.rawY)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap?.invoke()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val done = face.state == TimerText.State.DONE
        val count = if (face.count > 1) face.count.toString() else null
        val iconW = ICON_DP * dp
        val gap = 3f * dp
        val labelW = text.measureText(face.text)
        val badgeW = if (count == null) 0f else maxOf(BADGE_DP * dp, badgeText.measureText(count) + 4f * dp)
        val content = iconW + gap + labelW + if (count == null) 0f else 2f * dp + badgeW

        val h = PillLayout.TIMER_PILL_H_DP * dp
        val w = maxOf(PillLayout.TIMER_PILL_MIN_W_DP * dp, content + 10f * dp).coerceAtMost(width.toFloat())
        val left = (width - w) / 2f
        val top = if (stacked) height - PillLayout.TIMER_BOTTOM_PAD_DP * dp - h else (height - h) / 2f
        rect.set(left, top, left + w, top + h)
        skin.drawFill(canvas, rect, if (done) PillSkin.COLOR_GOLD else PillSkin.COLOR_FILL)
        if (!done) skin.drawDrain(canvas, rect, face.drain)
        skin.drawEdge(
            canvas, rect,
            when {
                done -> PillSkin.COLOR_GOLD
                active || face.state == TimerText.State.RUNNING -> PillSkin.COLOR_TIMER
                else -> PillSkin.COLOR_EDGE
            },
        )

        val cy = top + h / 2f
        var x = left + (w - content) / 2f
        icon.color = if (done) PillSkin.COLOR_ON_GOLD else PillSkin.COLOR_TIMER
        drawClock(canvas, x, cy - iconW / 2f, iconW / 24f)
        x += iconW + gap
        text.color = if (done) PillSkin.COLOR_ON_GOLD else PillSkin.COLOR_TEXT
        canvas.drawText(face.text, x, skin.baseline(cy), text)
        if (count != null) {
            x += labelW + 2f * dp
            val bh = BADGE_DP * dp
            rect.set(x, cy - bh / 2f, x + badgeW, cy + bh / 2f)
            canvas.drawRoundRect(rect, bh / 2f, bh / 2f, badge)
            canvas.drawText(count, rect.centerX(), cy - (badgeText.ascent() + badgeText.descent()) / 2f, badgeText)
        }
    }

    /** A small stopwatch, drawn on a 24 unit grid of [k] px from ([x], [y]). */
    private fun drawClock(canvas: Canvas, x: Float, y: Float, k: Float) {
        canvas.drawCircle(x + 12 * k, y + 13 * k, 8 * k, icon)
        canvas.drawLine(x + 12 * k, y + 9 * k, x + 12 * k, y + 13 * k, icon)
        canvas.drawLine(x + 12 * k, y + 13 * k, x + 14 * k, y + 15 * k, icon)
        canvas.drawLine(x + 9 * k, y + 2 * k, x + 15 * k, y + 2 * k, icon)
    }

    private companion object {
        const val ICON_DP = 9f
        const val BADGE_DP = 11f
    }
}
