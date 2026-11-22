package com.lperezmo.voicerelay

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * How every mini pill is drawn: the dark see-through body, the thin edge and
 * the small bold label. The volume, Glyph and timer pills all draw through
 * this, so their look changes in one place.
 */
class PillSkin(private val dp: Float) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * dp
    }
    /** The label: white, 9 dp, bold mono. Set its colour before drawing if a pill needs another. */
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT
        textSize = 9f * dp
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.04f
    }
    private val inner = RectF()

    /** The filled body, fully rounded. */
    fun drawFill(canvas: Canvas, rect: RectF, color: Int = COLOR_FILL) {
        fill.color = color
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, fill)
    }

    /** The 1 dp edge, just inside [rect]. Draw it after the fill and anything laid over the fill. */
    fun drawEdge(canvas: Canvas, rect: RectF, color: Int = COLOR_EDGE) {
        edge.color = color
        inner.set(rect)
        inner.inset(0.5f * dp, 0.5f * dp)
        canvas.drawRoundRect(inner, rect.height() / 2, rect.height() / 2, edge)
    }

    /** The baseline that centres the label on [cy]. */
    fun baseline(cy: Float): Float = cy - (text.ascent() + text.descent()) / 2f

    companion object {
        /** Every pill is this tall. */
        const val PILL_H_DP = 18f
        /** #1C1C1E at 72%. */
        const val COLOR_FILL = 0xB81C1C1E.toInt()
        /** White at 16%. */
        const val COLOR_EDGE = 0x29FFFFFF
        const val COLOR_TEXT = 0xFFFFFFFF.toInt()
        const val COLOR_GOLD = 0xFFF2C46D.toInt()
        /** The timer pill's colour: its icon, its edge while timers run, the fan's picked bubble. */
        const val COLOR_TIMER = 0xFF8AB4F8.toInt()
        /** Text on gold or on the timer colour. */
        const val COLOR_ON_GOLD = 0xFF1C1C1E.toInt()
        const val COLOR_ON_TIMER = 0xFF0B2A5C.toInt()
    }
}
