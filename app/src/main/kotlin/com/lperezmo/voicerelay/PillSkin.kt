package com.lperezmo.voicerelay

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/**
 * How every mini pill and its popups are drawn, in the "Instrument" look with
 * Crisp Capsule edges: a dark see-through body, a double edge (dark just outside,
 * light just inside, so it holds on white pages and on photos) and a small
 * medium-weight label with even-width digits. The volume, Glyph and timer pills,
 * the timer fan and the volume track all draw through this, so their look
 * changes in one place.
 */
class PillSkin(private val dp: Float) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * dp
    }
    /** The label: white, 10 dp, medium, even-width digits. Set its colour before drawing if a pill needs another. */
    val text = label(10f)
    private val inner = RectF()
    private val clip = Path()

    /** A label paint in the pills' type at [sizeDp]. */
    fun label(sizeDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT
        textSize = sizeDp * dp
        typeface = MEDIUM
        fontFeatureSettings = "tnum"
    }

    /** The filled body, fully rounded, with the dark outer line around it. */
    fun drawFill(canvas: Canvas, rect: RectF, color: Int = COLOR_FILL) =
        drawBody(canvas, rect, minOf(rect.width(), rect.height()) / 2, color)

    /** The 1 dp light edge, just inside [rect]. Draw it after the fill and anything laid over the fill. */
    fun drawEdge(canvas: Canvas, rect: RectF, color: Int = COLOR_EDGE) =
        drawInnerEdge(canvas, rect, minOf(rect.width(), rect.height()) / 2, color)

    /** Any rounded body (a pill, a panel, a bubble): the dark 1 dp line just outside [rect], then the fill. */
    fun drawBody(canvas: Canvas, rect: RectF, radius: Float, color: Int) {
        edge.color = COLOR_OUTER
        inner.set(rect)
        inner.inset(-0.5f * dp, -0.5f * dp)
        canvas.drawRoundRect(inner, radius + 0.5f * dp, radius + 0.5f * dp, edge)
        fill.color = color
        canvas.drawRoundRect(rect, radius, radius, fill)
    }

    /** The 1 dp light line just inside a rounded [rect]. */
    fun drawInnerEdge(canvas: Canvas, rect: RectF, radius: Float, color: Int = COLOR_EDGE) {
        edge.color = color
        inner.set(rect)
        inner.inset(0.5f * dp, 0.5f * dp)
        canvas.drawRoundRect(inner, radius - 0.5f * dp, radius - 0.5f * dp, edge)
    }

    /**
     * The 2 dp line that drains as a timer runs: [fraction] of the pill. Along the bottom from the
     * left, or for an upright pill ([upright]) up the side from the bottom, on the left side
     * unless [onRightSide].
     */
    fun drawDrain(
        canvas: Canvas, rect: RectF, fraction: Float, color: Int = COLOR_GOLD,
        upright: Boolean = false, onRightSide: Boolean = false,
    ) {
        if (fraction <= 0f) return
        val f = fraction.coerceAtMost(1f)
        val radius = minOf(rect.width(), rect.height()) / 2
        canvas.save()
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.clipPath(clip)
        fill.color = color
        val line = DRAIN_DP * dp
        when {
            !upright -> canvas.drawRect(rect.left, rect.bottom - line, rect.left + rect.width() * f, rect.bottom, fill)
            onRightSide -> canvas.drawRect(rect.right - line, rect.bottom - rect.height() * f, rect.right, rect.bottom, fill)
            else -> canvas.drawRect(rect.left, rect.bottom - rect.height() * f, rect.left + line, rect.bottom, fill)
        }
        canvas.restore()
    }

    /** The baseline that centres [paint]'s text (the label by default) on [cy]. */
    fun baseline(cy: Float, paint: Paint = text): Float = cy - (paint.ascent() + paint.descent()) / 2f

    companion object {
        /** Every pill is this tall. */
        const val PILL_H_DP = 18f
        const val DRAIN_DP = 2f
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        /** #1C1C1E at 72%. */
        const val COLOR_FILL = 0xB81C1C1E.toInt()
        /** #1C1C1E at 86%: the volume track. */
        const val COLOR_PANEL = 0xDB1C1C1E.toInt()
        /** #1C1C1E at 94%: the timer fan's bubbles and chips. */
        const val COLOR_BUBBLE = 0xF01C1C1E.toInt()
        /** The light inner edge: white at 20%. */
        const val COLOR_EDGE = 0x33FFFFFF
        /** The dark outer line: black at 30%. */
        const val COLOR_OUTER = 0x4D000000
        const val COLOR_TEXT = 0xFFFFFFFF.toInt()
        /** Labels that are not picked: #E8EAED at 62%. */
        const val COLOR_TEXT_DIM = 0x9EE8EAED.toInt()
        const val COLOR_GOLD = 0xFFF2C46D.toInt()
        /** The timer pill's colour: its icon, its edge while timers run, its drain line, the fan's picked bubble. */
        const val COLOR_TIMER = COLOR_GOLD
        /** Text on gold. */
        const val COLOR_ON_GOLD = 0xFF3A2E14.toInt()
        const val COLOR_ON_TIMER = COLOR_ON_GOLD
    }
}
