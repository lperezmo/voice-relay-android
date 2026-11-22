package com.lperezmo.voicerelay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * The small oval that rides above the dictate button and shows which volume
 * preset is on, as four level bars and a short name. Tapping it opens [VolumeTrackView]. The drawn pill is
 * [PILL_W_DP] x [PILL_H_DP]; the view is taller so the touch target reaches
 * 44 dp, growing upward only so it never covers the button below.
 */
class VolumePillView(context: Context) : View(context) {

    private val dp = context.resources.displayMetrics.density
    private var state = PillState.CUSTOM

    private val skin = PillSkin(dp)
    private val text = skin.text
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val bars = RectF()

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

    /** The track is open: the edge takes the preset's colour. */
    var open = false
        set(v) { field = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = PILL_W_DP * dp
        val h = PILL_H_DP * dp
        val left = (width - w) / 2f
        val top = height - BOTTOM_PAD_DP * dp - h
        rect.set(left, top, left + w, top + h)
        skin.drawFill(canvas, rect)
        skin.drawEdge(canvas, rect, if (open) state.color else PillSkin.COLOR_EDGE)

        // Four level bars, lit in the preset's colour, then the short name.
        val barW = BAR_W_DP * dp
        val barGap = 1f * dp
        val barH = BAR_H_DP * dp
        val barsW = PillState.BARS * barW + (PillState.BARS - 1) * barGap
        val gap = 4f * dp
        val labelW = text.measureText(state.short)
        var x = left + (w - (barsW + gap + labelW)) / 2f
        val cy = top + h / 2f
        for (i in 0 until PillState.BARS) {
            bar.color = if (i < state.bars) state.color else COLOR_BAR_OFF
            bars.set(x, cy - barH / 2f, x + barW, cy + barH / 2f)
            canvas.drawRoundRect(bars, barW / 2f, barW / 2f, bar)
            x += barW + barGap
        }
        canvas.drawText(state.short, x - barGap + gap, skin.baseline(cy), text)
    }

    companion object {
        const val W_DP = 50
        const val H_DP = 44
        const val PILL_W_DP = 46f
        const val PILL_H_DP = PillSkin.PILL_H_DP
        /** Space under the drawn pill, inside the view. */
        const val BOTTOM_PAD_DP = 4f
        private const val BAR_W_DP = 2f
        private const val BAR_H_DP = 7f
        /** An unlit bar: white at 22%. */
        private const val COLOR_BAR_OFF = 0x38FFFFFF
    }
}
