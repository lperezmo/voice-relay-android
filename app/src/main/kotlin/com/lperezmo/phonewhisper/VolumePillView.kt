package com.lperezmo.phonewhisper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * The small oval that rides above the dictate button and shows which volume
 * preset is on. Tapping it opens [VolumeTrackView]. The drawn pill is
 * [PILL_W_DP] x [PILL_H_DP]; the view is taller so the touch target reaches
 * 44 dp, growing upward only so it never covers the button below.
 */
class VolumePillView(context: Context) : View(context) {

    private val dp = context.resources.displayMetrics.density
    private var state = PillState.CUSTOM

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_EDGE; style = Paint.Style.STROKE; strokeWidth = 1f * dp
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 9f * dp
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.04f
    }
    private val rect = RectF()

    init {
        isHapticFeedbackEnabled = true
        update(state)
    }

    fun update(new: PillState) {
        state = new
        contentDescription = "Volume preset: ${new.label}. Tap to change."
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) =
        setMeasuredDimension((W_DP * dp).toInt(), (H_DP * dp).toInt())

    override fun onDraw(canvas: Canvas) {
        val w = PILL_W_DP * dp
        val h = PILL_H_DP * dp
        val left = (width - w) / 2f
        val top = height - BOTTOM_PAD_DP * dp - h
        rect.set(left, top, left + w, top + h)
        canvas.drawRoundRect(rect, h / 2, h / 2, fill)
        rect.inset(0.5f * dp, 0.5f * dp)
        canvas.drawRoundRect(rect, h / 2, h / 2, edge)

        val dotR = 3f * dp
        val gap = 4f * dp
        val labelW = text.measureText(state.short)
        val startX = left + (w - (dotR * 2 + gap + labelW)) / 2f
        val cy = top + h / 2f
        dot.color = state.color
        canvas.drawCircle(startX + dotR, cy, dotR, dot)
        val baseline = cy - (text.ascent() + text.descent()) / 2f
        canvas.drawText(state.short, startX + dotR * 2 + gap, baseline, text)
    }

    companion object {
        const val W_DP = 50
        const val H_DP = 44
        const val PILL_W_DP = 46f
        const val PILL_H_DP = 18f
        /** Space under the drawn pill, inside the view. */
        const val BOTTOM_PAD_DP = 4f

        private const val COLOR_FILL = 0xB81C1C1E.toInt()
        private const val COLOR_EDGE = 0x29FFFFFF
    }
}
