package com.lperezmo.voicerelay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/**
 * The timer pill: an upright tab beside the button, [PillLayout.TIMER_STUB_W_DP] wide and
 * [PillLayout.TIMER_TAB_H_DP] tall. While nothing runs it shows a small gold ring and
 * "Timer", read bottom to top; while timers run, the countdown of the one that ends first,
 * a count when there are several and a gold line up its outer side that drains; gold and
 * blinking once one is done. A tap calls [onTap] (the timer panel opens or closes); holding
 * or dragging it does nothing ([TimerTap]). The view is [PillLayout.TIMER_W_DP] x
 * [PillLayout.TIMER_H_DP] for a full touch target; the tab sits at its inner edge, next to
 * the button.
 */
class TimerPillView(context: Context) : View(context) {

    /** A tap on the pill, or a screen reader's double tap. */
    var onTap: (() -> Unit)? = null

    /** True when the button is docked right, so the pill is left of it and the tab hugs the view's right edge. */
    var onRight = true
        set(v) { if (field != v) { field = v; invalidate() } }

    /** True while the timer panel or the minutes card is open: the edge takes the timer colour. */
    var active = false
        set(v) { field = v; invalidate() }

    private val dp = context.resources.displayMetrics.density
    private var face = TimerText.Face("Timer", 0, TimerText.State.IDLE)
    /** When the face turned done ([SystemClock.uptimeMillis]), for how long it blinks. */
    private var doneAt = 0L
    private val tap = TimerTap(TimerTap.SLOP_DP * dp, ViewConfiguration.getLongPressTimeout().toLong())

    private val skin = PillSkin(dp)
    private val text = skin.text
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = DOT_STROKE_DP * dp
        color = PillSkin.COLOR_TIMER
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
        contentDescription = TimerText.describe(emptyList(), 0)
    }

    /** [description] is for screen readers; it is only taken when the timers change, not on every tick. */
    fun update(new: TimerText.Face, description: String) {
        if (new == face) return
        if (new.count != face.count || new.state != face.state) contentDescription = description
        if (new.state == TimerText.State.DONE && face.state != TimerText.State.DONE) doneAt = SystemClock.uptimeMillis()
        face = new
        invalidate()
    }

    private fun idle() = face.state == TimerText.State.IDLE

    private fun badgeLabel(): String? = if (face.count > 1) face.count.toString() else null

    private fun badgeSize(count: String): Float = maxOf(BADGE_DP * dp, badgeText.measureText(count) + 4f * dp)

    /** How long the dot, the text and the badge are, end to end along the tab. */
    private fun contentLength(): Float {
        val count = badgeLabel()
        return (if (idle()) (DOT_DP + GAP_DP) * dp else 0f) +
            text.measureText(face.text) +
            (if (count == null) 0f else GAP_DP * dp + badgeSize(count))
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) =
        setMeasuredDimension((PillLayout.TIMER_W_DP * dp).toInt(), (PillLayout.TIMER_H_DP * dp).toInt())

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> tap.down(event.rawX, event.rawY, event.eventTime)
            MotionEvent.ACTION_MOVE -> tap.move(event.rawX, event.rawY)
            MotionEvent.ACTION_UP -> if (tap.up(event.eventTime)) performClick()
            MotionEvent.ACTION_CANCEL -> tap.cancel()
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
        val inset = PillLayout.TIMER_STUB_W_DP * dp / 2f + PillLayout.TIMER_STUB_GAP_DP * dp
        val cx = if (onRight) width - inset else inset
        val cy = height / 2f
        val w = PillLayout.TIMER_STUB_W_DP * dp
        // Always the full tab; a long countdown with its badge makes it a little taller, up to a cap.
        val h = PillLayout.timerTabHeightDp(contentLength() / dp) * dp
        rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)

        // A finished timer blinks for a while, like the ring, then stays solid gold.
        val alpha = if (done) TimerText.blink(SystemClock.uptimeMillis() - doneAt) else null
        val blinking = alpha != null
        if (alpha != null) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (alpha * 255).toInt())

        skin.drawFill(canvas, rect, if (done) PillSkin.COLOR_GOLD else PillSkin.COLOR_FILL)
        // The drain line runs up the side away from the button.
        if (!done) skin.drawDrain(canvas, rect, face.drain, upright = true, onRightSide = !onRight)
        skin.drawEdge(
            canvas, rect,
            when {
                done -> PillSkin.COLOR_GOLD
                active || face.state == TimerText.State.RUNNING -> PillSkin.COLOR_TIMER
                else -> PillSkin.COLOR_EDGE
            },
        )

        canvas.save()
        canvas.clipRect(rect)
        drawContent(canvas, cx, cy, done)
        canvas.restore()

        if (blinking) {
            canvas.restore()
            postInvalidateOnAnimation()
        }
    }

    /**
     * From the bottom up: the gold ring dot while idle, the text ("Timer" or the countdown)
     * turned to read from the bottom up, and above it the count of running timers, upright in
     * a small gold badge.
     */
    private fun drawContent(canvas: Canvas, cx: Float, cy: Float, done: Boolean) {
        val count = badgeLabel()
        // Along the tab, from the bottom: the content starts at total / 2 below the middle.
        var at = cy + contentLength() / 2f
        if (idle()) {
            canvas.drawCircle(cx, at - DOT_DP * dp / 2f, DOT_R_DP * dp, dot)
            at -= (DOT_DP + GAP_DP) * dp
        }
        canvas.save()
        canvas.rotate(-90f, cx, at)
        text.color = if (done) PillSkin.COLOR_ON_GOLD else PillSkin.COLOR_TEXT
        canvas.drawText(face.text, cx, skin.baseline(at), text)
        canvas.restore()
        at -= text.measureText(face.text)
        if (count != null) {
            val size = badgeSize(count)
            val bcy = at - GAP_DP * dp - size / 2f
            val r = BADGE_DP * dp / 2f
            rect.set(cx - r, bcy - size / 2f, cx + r, bcy + size / 2f)
            canvas.drawRoundRect(rect, r, r, badge)
            canvas.drawText(count, cx, bcy - (badgeText.ascent() + badgeText.descent()) / 2f, badgeText)
        }
    }

    private companion object {
        /** The idle dot: an 8 dp gold ring. */
        const val DOT_DP = 8f
        const val DOT_R_DP = 3.25f
        const val DOT_STROKE_DP = 1.5f
        const val BADGE_DP = 11f
        /** Space between the dot, the text and the badge. */
        const val GAP_DP = 4f
    }
}
