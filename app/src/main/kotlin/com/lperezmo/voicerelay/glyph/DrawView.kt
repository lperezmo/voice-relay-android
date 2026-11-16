package com.lperezmo.voicerelay.glyph

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.lperezmo.voicerelay.glyph.recognizer.Pt
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Where shapes are drawn: a gold line with glitter trailing the finger. Several strokes
 * make one drawing; [timeoutMs] after the last finger lifts the drawing is handed to
 * [onDrawing]. A quick tap before anything is drawn goes to [onTap] instead.
 */
@SuppressLint("ViewConstructor")
class DrawView(context: Context, private val glitterOn: Boolean, var timeoutMs: Long) : View(context) {
    companion object {
        /** A quick touch that barely moved: under 14 dp across and under 400 ms. */
        fun isTap(stroke: List<Pt>, ms: Long, density: Float): Boolean {
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (p in stroke) {
                minX = min(minX, p.x); minY = min(minY, p.y); maxX = max(maxX, p.x); maxY = max(maxY, p.y)
            }
            return max(maxX - minX, maxY - minY) < 14 * density && ms < 400
        }
    }

    var onDrawing: ((List<Pt>) -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var onFirstTouch: (() -> Unit)? = null
    /** A finger came down while a drawing was being handled (a result on screen). */
    var onBusyTouch: (() -> Unit)? = null
    /** A finger came down to start a stroke. */
    var onStrokeStart: (() -> Unit)? = null
    /** Ask the Page has taken over: touches go to the page view underneath instead. */
    var passThrough = false

    private val density = resources.displayMetrics.density
    private val glitter = Glitter(density)
    private val strokes = ArrayList<ArrayList<Pt>>()
    private val paths = ArrayList<Path>()
    private var strokeStart = 0L
    private var lastEmit: Pair<Float, Float>? = null
    private var lineAlpha = 1f
    private var fadeFrom = 0f
    private var fadeMs = 0L
    private var fadeEnd: (() -> Unit)? = null
    private var touchedOnce = false
    private var busy = false
    private val t0 = SystemClock.uptimeMillis()

    private val now get() = (SystemClock.uptimeMillis() - t0) / 1000f

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 3.2f * density
        color = hsl(44f, 100f, 78f)
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 10f * density
        color = hsl(44f, 100f, 62f)
        maskFilter = BlurMaskFilter(6f * density, BlurMaskFilter.Blur.NORMAL)
    }

    private val finish = Runnable {
        val pts = strokes.flatten()
        if (pts.size >= 2) {
            busy = true
            onDrawing?.invoke(pts)
        } else {
            reset()
        }
    }

    /** Drop the drawing (after a test or a pad sample) and take the next one. */
    fun reset() {
        removeCallbacks(finish)
        strokes.clear()
        paths.clear()
        lineAlpha = 1f
        fadeEnd = null
        fadeMs = 0
        busy = false
        invalidate()
    }

    /** A match: the line flashes, glitter bursts out of it, and the line fades. */
    fun celebrate() {
        burst(strokes.flatten())
        fadeLine(700) {}
    }

    /** Glitter bursting out of [pts] (a drawing, even one already cleared from the view). */
    fun burst(pts: List<Pt>) {
        if (!glitterOn || pts.isEmpty()) return
        var cx = 0f
        var cy = 0f
        for (p in pts) {
            cx += p.x; cy += p.y
        }
        glitter.burst(pts.map { it.x to it.y }, cx / pts.size, cy / pts.size, now)
        invalidate()
    }

    /** Fade the line away over [ms], then call [end]. */
    fun fadeLine(ms: Long, end: () -> Unit) {
        fadeFrom = now
        fadeMs = ms
        fadeEnd = end
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (passThrough) return false
        if (busy) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) onBusyTouch?.invoke()
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // inside a scrolling page (the teach pad) the stroke must not scroll it
                parent?.requestDisallowInterceptTouchEvent(true)
                removeCallbacks(finish)
                onStrokeStart?.invoke()
                if (!touchedOnce) {
                    touchedOnce = true
                    onFirstTouch?.invoke()
                }
                strokeStart = e.eventTime
                strokes += arrayListOf(Pt(e.x, e.y, strokes.size))
                paths += Path().apply { moveTo(e.x, e.y) }
                lastEmit = null
                sparkle(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> {
                val stroke = strokes.lastOrNull() ?: return true
                val path = paths.last()
                for (h in 0 until e.historySize) add(stroke, path, e.getHistoricalX(0, h), e.getHistoricalY(0, h))
                add(stroke, path, e.x, e.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val stroke = strokes.lastOrNull() ?: return true
                if (strokes.size == 1 && isTap(stroke, e.eventTime - strokeStart)) {
                    strokes.clear()
                    paths.clear()
                    invalidate()
                    onTap?.invoke()
                    return true
                }
                postDelayed(finish, timeoutMs)
            }
        }
        invalidate()
        return true
    }

    private fun isTap(stroke: List<Pt>, ms: Long): Boolean = isTap(stroke, ms, density)

    private fun add(stroke: ArrayList<Pt>, path: Path, x: Float, y: Float) {
        val last = stroke.last()
        if (hypot(x - last.x, y - last.y) < 1.5f * density) return
        stroke += Pt(x, y, last.stroke)
        path.lineTo(x, y)
        sparkle(x, y)
    }

    private fun sparkle(x: Float, y: Float) {
        if (!glitterOn) return
        val prev = lastEmit
        val gap = 5 * density
        if (prev == null) {
            glitter.trail(x, y, now)
            lastEmit = x to y
            return
        }
        var d = hypot(x - prev.first, y - prev.second)
        var px = prev.first
        var py = prev.second
        while (d >= gap) {
            val k = gap / d
            px += (x - px) * k
            py += (y - py) * k
            glitter.trail(px, py, now)
            if (glitter.random() < 0.5f) glitter.trail(px, py, now)
            d -= gap
            lastEmit = px to py
        }
    }

    override fun onDraw(canvas: Canvas) {
        val t = now
        if (fadeMs > 0) {
            val k = (t - fadeFrom) * 1000 / fadeMs
            lineAlpha = max(0f, 1f - k)
            if (k >= 1f) {
                val end = fadeEnd
                fadeEnd = null
                end?.invoke()
            }
        }
        if (lineAlpha > 0f) {
            glow.alpha = (90 * lineAlpha).toInt()
            line.alpha = (235 * lineAlpha).toInt()
            for (p in paths) canvas.drawPath(p, glow)
            for (p in paths) canvas.drawPath(p, line)
        }
        glitter.draw(canvas, t)
        if (glitter.alive || (fadeMs > 0 && lineAlpha > 0f) || fadeEnd != null) postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(finish)
        super.onDetachedFromWindow()
    }
}
