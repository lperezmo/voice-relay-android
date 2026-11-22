package com.lperezmo.voicerelay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.sin

/**
 * The dictate button: a dark disc with a light-and-dark edge, a ring with twelve
 * ticks and the app's mark in the middle. Everything the ring shows comes from
 * [ring] ([ButtonRing.look]); this only draws it, on [SystemClock.uptimeMillis].
 */
class ButtonRingView(context: Context, private val ring: ButtonRing) : View(context) {

    private val dp = context.resources.displayMetrics.density
    private val mark: Drawable = ContextCompat.getDrawable(context, R.drawable.ic_voice_mark)!!.mutate()
    private var markTint: Int? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ButtonRing.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val oval = RectF()

    /** Redraws now; call after telling [ring] something. */
    fun refresh() = invalidate()

    override fun onDraw(canvas: Canvas) {
        val look = ring.look(SystemClock.uptimeMillis())
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f

        // The disc, with Crisp Capsule's double edge: dark just outside, light just inside.
        canvas.drawCircle(cx, cy, r - 1f * dp, fill)
        stroke(ButtonRing.EDGE_OUT, 1f)
        canvas.drawCircle(cx, cy, r - 0.5f * dp, line)
        stroke(ButtonRing.EDGE_IN, 1f)
        canvas.drawCircle(cx, cy, r - 1.5f * dp, line)

        // The track and its ticks.
        val trackR = ButtonRing.TRACK_R_DP * dp
        stroke(ButtonRing.TRACK, ButtonRing.TRACK_W_DP)
        canvas.drawCircle(cx, cy, trackR, line)
        stroke(ButtonRing.TICKS, 1f)
        for (i in 0 until ButtonRing.TICKS_COUNT) {
            val a = i * 2 * Math.PI / ButtonRing.TICKS_COUNT
            val s = sin(a).toFloat()
            val c = cos(a).toFloat()
            canvas.drawLine(
                cx + ButtonRing.TICK_OUT_DP * dp * s, cy - ButtonRing.TICK_OUT_DP * dp * c,
                cx + ButtonRing.TICK_IN_DP * dp * s, cy - ButtonRing.TICK_IN_DP * dp * c, line,
            )
        }

        look.gold?.takeIf { it > 0f }?.let { left ->
            stroke(ButtonRing.GOLD, ButtonRing.GOLD_W_DP, round = true)
            arc(canvas, cx, cy, ButtonRing.GOLD_R_DP * dp, ButtonRing.TOP, left * 360f)
        }
        look.level?.let {
            stroke(it, ButtonRing.LEVEL_W_DP)
            canvas.drawCircle(cx, cy, look.levelRadiusDp * dp, line)
        }
        look.track?.let {
            stroke(it, ButtonRing.TRACK_W_DP)
            canvas.drawCircle(cx, cy, trackR, line)
        }
        look.arc?.let {
            stroke(it.color, ButtonRing.TRACK_W_DP, round = true)
            arc(canvas, cx, cy, trackR, it.startDeg, it.sweepDeg)
        }
        look.flash?.let {
            stroke(it, ButtonRing.TRACK_W_DP)
            canvas.drawCircle(cx, cy, trackR, line)
        }

        // The mark: its own colours at rest, one colour when a state takes it over.
        val tint = look.mic.takeIf { it != ButtonRing.WHITE }
        if (tint != markTint) {
            if (tint == null) mark.setTintList(null) else mark.setTint(tint)
            markTint = tint
        }
        val half = ButtonRing.MIC_DP * dp / 2f
        mark.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        mark.draw(canvas)

        if (look.animating) postInvalidateOnAnimation()
    }

    private fun stroke(color: Int, widthDp: Float, round: Boolean = false) {
        line.color = color
        line.strokeWidth = widthDp * dp
        line.strokeCap = if (round) Paint.Cap.ROUND else Paint.Cap.BUTT
    }

    private fun arc(canvas: Canvas, cx: Float, cy: Float, r: Float, start: Float, sweep: Float) {
        oval.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(oval, start, sweep, false, line)
    }
}
