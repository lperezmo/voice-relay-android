package com.lperezmo.voicerelay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View

/**
 * What shows while the timer pill is held: five round bubbles on an arc (5, 10,
 * 15, 30 and "···" for your own time), the one under the finger bigger and in
 * the timer colour, and above them the running timers as small chips. It only
 * draws; touches stay with [TimerPillView]. Where the bubbles go is
 * [PillLayout.timerFan].
 */
class TimerFanView(context: Context) : View(context) {

    /** A running timer's chip: its label and its countdown. */
    class Chip(val label: String, val left: String, val done: Boolean)

    private val dp = context.resources.displayMetrics.density
    private var centres: List<PillLayout.Spot> = emptyList()
    private var chipsFromRight = false
    private var chips: List<Chip> = emptyList()
    private val labels = TimerText.FAN_MINUTES.map { if (it == 0) TimerText.OWN_LABEL else it.toString() }

    /** The bubble under the finger, or -1. It grows over [GROW_MS]. */
    var hot = -1
        set(v) {
            if (field == v) return
            field = v
            lastFrame = SystemClock.uptimeMillis()
            invalidate()
        }
    /** How far each bubble has grown, 0 (resting) to 1 (under the finger). */
    private val grown = FloatArray(labels.size)
    private var lastFrame = 0L

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * dp
    }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * dp
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val chipLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_DIM
        textSize = 12f * dp
    }
    private val chipTime = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * dp
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val rect = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Where bubble [index]'s middle is on the screen, once the view is laid out; null before that. */
    fun centresOnScreen(): List<PillLayout.Spot>? {
        if (!isLaidOut) return null
        val at = IntArray(2).also { getLocationOnScreen(it) }
        return centres.map { PillLayout.Spot(it.x + at[0], it.y + at[1]) }
    }

    /** Places the bubbles for [fan], whose window this view fills. */
    fun place(fan: PillLayout.Fan) {
        centres = fan.centres.map { PillLayout.Spot(it.x - fan.window.x, it.y - fan.window.y) }
        chipsFromRight = !fan.onRight
        invalidate()
    }

    fun showChips(new: List<Chip>) {
        chips = new
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        drawChips(canvas)

        val now = SystemClock.uptimeMillis()
        val step = ((now - lastFrame).coerceAtLeast(0) / GROW_MS.toFloat())
        lastFrame = now
        var moving = false
        for (i in centres.indices) {
            val target = if (i == hot) 1f else 0f
            grown[i] = if (grown[i] < target) minOf(target, grown[i] + step) else maxOf(target, grown[i] - step)
            if (grown[i] != target) moving = true

            val c = centres[i]
            val isHot = i == hot
            val r = (PillLayout.TIMER_FAN_BUBBLE_DP +
                (PillLayout.TIMER_FAN_HOT_DP - PillLayout.TIMER_FAN_BUBBLE_DP) * grown[i]) * dp / 2f
            fill.color = if (isHot) PillSkin.COLOR_TIMER else COLOR_BUBBLE
            canvas.drawCircle(c.x.toFloat(), c.y.toFloat(), r, fill)
            if (!isHot) {
                ring.color = COLOR_RING
                canvas.drawCircle(c.x.toFloat(), c.y.toFloat(), r - 0.5f * dp, ring)
            }
            bubbleText.color = when {
                isHot -> PillSkin.COLOR_ON_TIMER
                TimerText.FAN_MINUTES[i] == 0 -> PillSkin.COLOR_TIMER
                else -> COLOR_BUBBLE_TEXT
            }
            canvas.drawText(labels[i], c.x.toFloat(), c.y - (bubbleText.ascent() + bubbleText.descent()) / 2f, bubbleText)
        }
        if (moving) postInvalidateOnAnimation()
    }

    /** The running timers, wrapped into rows from the side away from the button. */
    private fun drawChips(canvas: Canvas) {
        if (chips.isEmpty()) return
        val h = CHIP_H_DP * dp
        val pad = 10f * dp
        val gap = 6f * dp
        val maxW = minOf(width.toFloat(), CHIPS_W_DP * dp)
        var x = 0f
        var y = 0f
        for (chip in chips) {
            val labelW = chipLabel.measureText(chip.label)
            val w = pad + labelW + gap + chipTime.measureText(chip.left) + pad
            if (x > 0f && x + w > maxW) {
                x = 0f
                y += h + gap
                // Two rows is the room there is above the bubbles; the pill's list shows the rest.
                if (y + h > CHIP_ROWS * (h + gap)) return
            }
            val left = if (chipsFromRight) width - x - w else x
            rect.set(left, y, left + w, y + h)
            fill.color = COLOR_BUBBLE
            canvas.drawRoundRect(rect, h / 2f, h / 2f, fill)
            ring.color = PillSkin.COLOR_EDGE
            rect.inset(0.5f * dp, 0.5f * dp)
            canvas.drawRoundRect(rect, h / 2f, h / 2f, ring)
            val cy = y + h / 2f
            canvas.drawText(chip.label, left + pad, cy - (chipLabel.ascent() + chipLabel.descent()) / 2f, chipLabel)
            chipTime.color = if (chip.done) PillSkin.COLOR_GOLD else COLOR_LIGHT
            canvas.drawText(chip.left, left + pad + labelW + gap, cy - (chipTime.ascent() + chipTime.descent()) / 2f, chipTime)
            x += w + gap
        }
    }

    private companion object {
        const val GROW_MS = 120L
        const val CHIP_H_DP = 26f
        const val CHIPS_W_DP = 250f
        const val CHIP_ROWS = 2
        /** #1C1C1E at 92%. */
        const val COLOR_BUBBLE = 0xEB1C1C1E.toInt()
        /** White at 28%. */
        const val COLOR_RING = 0x47FFFFFF
        const val COLOR_BUBBLE_TEXT = 0xFFE8EAED.toInt()
        const val COLOR_DIM = 0xFFA9ACB5.toInt()
        const val COLOR_LIGHT = 0xFFE6E6EA.toInt()
    }
}
