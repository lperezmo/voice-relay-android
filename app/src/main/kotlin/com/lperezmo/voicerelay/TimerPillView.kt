package com.lperezmo.voicerelay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * The timer pill, upright beside the button: a small round stub with a clock while
 * nothing runs, growing into a tall tab with the countdown of the timer that ends
 * first (read bottom to top), a count when there are several and a gold line up its
 * outer side that drains, and gold and blinking once one is done. A tap lists the
 * running timers and a hold fans out times to start one; the view only reports the
 * touches ([onPress]) and [FloatingPills] decides, through [TimerPress]. The view is
 * [PillLayout.TIMER_W_DP] x [PillLayout.TIMER_H_DP] for a full touch target; the stub
 * sits at its inner edge, next to the button.
 */
class TimerPillView(context: Context) : View(context) {

    /** The pill was clicked without a touch (a screen reader's double tap): show what is running. */
    var onTap: (() -> Unit)? = null

    /** Every touch on the pill, with where it is on the screen. */
    var onPress: ((action: Int, rawX: Float, rawY: Float) -> Unit)? = null

    /** True when the button is docked right, so the pill is left of it and the stub hugs the view's right edge. */
    var onRight = true
        set(v) { if (field != v) { field = v; invalidate() } }

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
        color = PillSkin.COLOR_TIMER
    }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PillSkin.COLOR_TIMER }
    private val badgeText = skin.label(8f).apply {
        color = PillSkin.COLOR_ON_TIMER
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    /** The stub's drawn height now, in px; it eases toward the height the face needs. */
    private var stubH = PillLayout.TIMER_STUB_H_DP * dp
    private var growTarget = stubH
    private var grow: ValueAnimator? = null

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
        growTo(targetHeight())
        invalidate()
    }

    /** The idle stub is a dot; with a countdown it is tall enough for the text, at least [PillLayout.TIMER_GROWN_H_DP]. */
    private fun targetHeight(): Float {
        if (face.state == TimerText.State.IDLE) return PillLayout.TIMER_STUB_H_DP * dp
        val need = contentLength() + 2 * END_PAD_DP * dp
        return maxOf(PillLayout.TIMER_GROWN_H_DP * dp, need).coerceAtMost(PillLayout.TIMER_H_DP * dp)
    }

    private fun growTo(target: Float) {
        if (target == growTarget) return
        growTarget = target
        grow?.cancel()
        grow = ValueAnimator.ofFloat(stubH, target).apply {
            duration = PillLayout.TIMER_GROW_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                stubH = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun badgeLabel(): String? = if (face.count > 1) face.count.toString() else null

    private fun badgeSize(count: String): Float = maxOf(BADGE_DP * dp, badgeText.measureText(count) + 4f * dp)

    /** How long the countdown and its badge are, along the stub. */
    private fun contentLength(): Float {
        val count = badgeLabel()
        return text.measureText(face.text) + if (count == null) 0f else BADGE_GAP_DP * dp + badgeSize(count)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) =
        setMeasuredDimension((PillLayout.TIMER_W_DP * dp).toInt(), (PillLayout.TIMER_H_DP * dp).toInt())

    override fun onDetachedFromWindow() {
        // Taken down mid-grow: land on the end height, so it is right when shown again.
        grow?.end()
        super.onDetachedFromWindow()
    }

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
        val inset = PillLayout.TIMER_STUB_W_DP * dp / 2f + PillLayout.TIMER_STUB_GAP_DP * dp
        val cx = if (onRight) width - inset else inset
        val cy = height / 2f
        val w = PillLayout.TIMER_STUB_W_DP * dp
        rect.set(cx - w / 2f, cy - stubH / 2f, cx + w / 2f, cy + stubH / 2f)

        // A finished timer blinks: fully there, then down to a third, every 0.6 s.
        val blinking = done
        if (blinking) {
            val t = (SystemClock.uptimeMillis() % BLINK_MS) / BLINK_MS.toFloat()
            val alpha = BLINK_LOW + (1f - BLINK_LOW) * (0.5f + 0.5f * Math.cos(2 * Math.PI * t).toFloat())
            canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (alpha * 255).toInt())
        }

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

        if (face.state == TimerText.State.IDLE) drawClock(canvas, cx, cy)
        else {
            // While it grows the text shows through the stub as it opens.
            canvas.save()
            canvas.clipRect(rect)
            drawCountdown(canvas, cx, cy, done)
            canvas.restore()
        }

        if (blinking) {
            canvas.restore()
            postInvalidateOnAnimation()
        }
    }

    /**
     * The countdown, turned to read from the bottom up, and after it (above it) the count
     * of running timers, upright in a small gold badge.
     */
    private fun drawCountdown(canvas: Canvas, cx: Float, cy: Float, done: Boolean) {
        val count = badgeLabel()
        val labelW = text.measureText(face.text)
        val total = contentLength()
        // Along the stub, from the bottom: the label starts at total / 2 below the middle.
        val start = cy + total / 2f
        canvas.save()
        canvas.rotate(-90f, cx, start)
        text.color = if (done) PillSkin.COLOR_ON_GOLD else PillSkin.COLOR_TEXT
        canvas.drawText(face.text, cx, skin.baseline(start), text)
        canvas.restore()
        if (count != null) {
            val size = badgeSize(count)
            val bcy = start - labelW - BADGE_GAP_DP * dp - size / 2f
            val r = BADGE_DP * dp / 2f
            rect.set(cx - r, bcy - size / 2f, cx + r, bcy + size / 2f)
            canvas.drawRoundRect(rect, r, r, badge)
            canvas.drawText(count, cx, bcy - (badgeText.ascent() + badgeText.descent()) / 2f, badgeText)
        }
    }

    /** The clock: a ring with two hands, [ICON_DP] across, centred on ([cx], [cy]). */
    private fun drawClock(canvas: Canvas, cx: Float, cy: Float) {
        // The design's 8 unit icon.
        val k = ICON_DP * dp / 8f
        val x = cx - 4 * k
        val y = cy - 4 * k
        icon.strokeWidth = 1.3f * k
        canvas.drawCircle(cx, cy, 3.25f * k, icon)
        icon.strokeWidth = 1f * k
        canvas.drawLine(x + 4 * k, y + 2.2f * k, x + 4 * k, y + 4 * k, icon)
        canvas.drawLine(x + 4 * k, y + 4 * k, x + 5.2f * k, y + 4.8f * k, icon)
    }

    private companion object {
        const val ICON_DP = 9f
        const val BADGE_DP = 11f
        const val BADGE_GAP_DP = 4f
        /** Room kept at each end of the grown stub, past its text. */
        const val END_PAD_DP = 7f
        const val BLINK_MS = 600L
        const val BLINK_LOW = 0.35f
    }
}
