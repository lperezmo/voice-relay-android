package com.lperezmo.voicerelay.glyph

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.lperezmo.voicerelay.glyph.recognizer.Pt
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * The page Ask the Page writes on, under the canvas's [DrawView] (which passes touches
 * through once the page is open). It draws, back to front: the dim layer with every lit
 * lens cut out of it, the gold lens outlines, the ink, the pause ring, the answer in
 * cursive and the glitter. With [PageState.wholeScreen] on, no lens is cut out (the whole
 * screen is lit, under a lighter dim the controller sets), lit loops are drawn as plain ink
 * and a thin gold border runs round the screen's edge.
 *
 * It only reports touches ([onDown], [onWriting], [onStroke], [onTap], [onIdle]); [PageController]
 * decides what they mean and files strokes into [state]. Coordinates are view-local.
 */
@SuppressLint("ViewConstructor")
class PageView(context: Context, private val glitterOn: Boolean, private val motion: Boolean) : View(context) {
    companion object {
        const val SOAK_S = 1.1f
        private const val ANSWER_FADE_S = 0.3f
        private const val RING_DP = 11f
    }

    enum class Mode { WRITING, SENDING, ANSWER }

    /** A finger came down (resets the pause). */
    var onDown: (() -> Unit)? = null
    /** The stroke under the finger has grown past a tap: the user is writing. Once per stroke. */
    var onWriting: (() -> Unit)? = null
    /** A finished stroke that was not a tap. */
    var onStroke: ((List<Pt>) -> Unit)? = null
    var onTap: ((Float, Float) -> Unit)? = null
    /** A touch ended (or was cancelled) as neither a tap nor a stroke, such as a long rest in place. */
    var onIdle: (() -> Unit)? = null
    /** The cursive text has been fully written (or skipped to the end). Once per [showText]. */
    var onWritten: (() -> Unit)? = null

    var state = PageState()
    var mode = Mode.WRITING
        set(v) {
            field = v
            invalidate()
        }

    /** How dark the dim layer is, 0..1. */
    var dim = 92 / 255f
        set(v) {
            field = v
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val glitter = Glitter(density)
    private val t0 = SystemClock.uptimeMillis()
    val now get() = (SystemClock.uptimeMillis() - t0) / 1000f

    private val writer = CursiveWriter(context, glitter)
    private var textShown = false
    private var writtenSent = false
    private var answerFadeFrom = -1f

    // the stroke under the finger
    private var live: ArrayList<Pt>? = null
    private val livePath = Path()
    private var liveStart = 0L
    private var liveWriting = false

    private val paths = IdentityHashMap<List<Pt>, Path>()

    // the pause ring: progress from ringFrom over ringSeconds, or off when ringFrom < 0
    private var ringFrom = -1f
    private var ringSeconds = 2.5f

    // the soak: what was on the page when it was sent, fading and blurring away
    private var soakFrom = -1f
    private var soakInk: List<List<Pt>> = emptyList()
    private var soakLoops: List<Loop> = emptyList()
    private var soakWhole = false
    private var soakPoints: List<Pt> = emptyList()
    private var soakBlur = -1f
    private var blur: BlurMaskFilter? = null
    private val soakBox = RectF()
    private var lastTrickle = 0f

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
    /** A lens outline: a little bolder and deeper gold than ink, so lit and unlit read apart. */
    private val lensLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 4f * density
        color = hsl(44f, 100f, 64f)
    }
    private val dimPaint = Paint().apply { color = Color.BLACK }
    /** The whole-screen border, round the edge of the screen. */
    private val edgeLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = hsl(44f, 100f, 64f)
        alpha = 235
    }
    private val edgeBox = RectF()
    private val ringTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        color = hsl(44f, 100f, 78f)
        alpha = 60
    }
    private val ringFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.4f * density
        color = hsl(44f, 100f, 72f)
    }
    private val ringBox = RectF()

    // ---- what the controller calls ---------------------------------------------------

    /** Start the pause ring; it fills over [seconds]. */
    fun startRing(seconds: Float) {
        ringSeconds = seconds
        ringFrom = now
        invalidate()
    }

    fun stopRing() {
        if (ringFrom < 0f) return
        ringFrom = -1f
        invalidate()
    }

    /** The question is on its way: the ink and lenses fade and blur away, shedding glitter. */
    fun soak() {
        soakWhole = state.wholeScreen
        // with the whole screen on, lit loops were plain ink on the page, so they soak away as ink
        val lit = state.loops.filter { it.lit }
        soakInk = if (soakWhole) state.ink + lit.map { it.stroke } else state.ink.toList()
        soakLoops = if (soakWhole) emptyList() else lit
        soakPoints = (soakInk + soakLoops.map { it.stroke }).flatten()
        soakBox.setEmpty()
        for (p in soakPoints) soakBox.union(p.x, p.y)
        soakFrom = now
        blur = null
        mode = Mode.SENDING
    }

    /** Lay out [text] in cursive at [top] / [left], [width] wide, and write it (or show it at once). */
    fun showText(text: String, width: Int, top: Float, left: Float) {
        soakFrom = -1f
        answerFadeFrom = -1f
        writer.layout(text, width, top, left)
        textShown = true
        writtenSent = false
        if (motion) {
            writer.start(now)
        } else {
            writer.finishNow()
            post { written() }
        }
        mode = Mode.ANSWER
    }

    /** Bottom of the cursive text laid out by [showText]. */
    val textBottom get() = writer.bottomPx

    /** Jump to the end of the writing. */
    fun finishText() {
        writer.finishNow()
        written()
        invalidate()
    }

    val writing get() = textShown && !writtenSent

    /** Fade the old answer away (a follow-up started) and go back to writing. */
    fun clearText() {
        if (textShown) {
            if (motion) answerFadeFrom = now else textShown = false
        }
        soakFrom = -1f
        mode = Mode.WRITING
    }

    /** Forget everything drawn: for closing. */
    fun dispose() {
        ringFrom = -1f
        soakFrom = -1f
        textShown = false
        live = null
        paths.clear()
        glitter.clear()
        onDown = null; onWriting = null; onStroke = null; onTap = null; onIdle = null; onWritten = null
    }

    private fun written() {
        if (writtenSent) return
        writtenSent = true
        onWritten?.invoke()
    }

    // ---- touch -------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (mode == Mode.SENDING) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onDown?.invoke()
                liveStart = e.eventTime
                liveWriting = false
                live = arrayListOf(Pt(e.x, e.y, 0))
                livePath.reset()
                livePath.moveTo(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> {
                val stroke = live ?: return true
                for (h in 0 until e.historySize) add(stroke, e.getHistoricalX(0, h), e.getHistoricalY(0, h))
                add(stroke, e.x, e.y)
                if (!liveWriting && !DrawView.isTap(stroke, 0, density)) {
                    liveWriting = true
                    onWriting?.invoke()
                }
            }
            MotionEvent.ACTION_UP -> {
                val stroke = live ?: return true
                live = null
                if (!liveWriting && DrawView.isTap(stroke, e.eventTime - liveStart, density)) {
                    onTap?.invoke(stroke.first().x, stroke.first().y)
                } else if (stroke.size >= 2) {
                    if (!liveWriting) {
                        liveWriting = true
                        onWriting?.invoke()
                    }
                    onStroke?.invoke(stroke)
                } else {
                    onIdle?.invoke()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (live != null) onIdle?.invoke()
                live = null
            }
        }
        invalidate()
        return true
    }

    private fun add(stroke: ArrayList<Pt>, x: Float, y: Float) {
        val last = stroke.last()
        if (hypot(x - last.x, y - last.y) < 1.5f * density) return
        stroke += Pt(x, y, 0)
        livePath.lineTo(x, y)
        if (glitterOn && motion && glitter.random() < 0.35f) glitter.trail(x, y, now)
    }

    // ---- drawing -----------------------------------------------------------------------

    private fun pathOf(stroke: List<Pt>): Path = paths.getOrPut(stroke) {
        Path().apply { stroke.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
    }

    private fun closedPathOf(loop: Loop): Path = Path(pathOf(loop.stroke)).apply { close() }

    override fun onDraw(canvas: Canvas) {
        val t = now
        var animating = false

        val soakK = if (soakFrom >= 0f) min(1f, (t - soakFrom) / SOAK_S) else -1f
        val whole = mode == Mode.WRITING && state.wholeScreen
        val lenses: List<Loop> = when {
            whole -> emptyList()
            mode == Mode.WRITING -> state.loops.filter { it.lit }
            soakK in 0f..0.999f -> soakLoops
            else -> emptyList()
        }

        // the dim layer, with each lit lens cut out so the app underneath shows at full brightness
        canvas.save()
        for (l in lenses) canvas.clipOutPath(closedPathOf(l))
        dimPaint.alpha = (255 * dim).toInt().coerceIn(0, 255)
        canvas.drawPaint(dimPaint)
        canvas.restore()

        if (state.wholeScreen && mode != Mode.SENDING) drawEdge(canvas)

        if (whole) {
            drawStrokes(canvas, state.ink + state.loops.filter { it.lit }.map { it.stroke }, emptyList(), 1f)
        } else if (mode == Mode.WRITING) {
            drawStrokes(canvas, state.ink, lenses, 1f)
        } else if (soakK in 0f..0.999f) {
            animating = true
            val fade = if (motion) 1f - soakK else 0f
            if (motion) setBlur(soakK * 9f * density)
            drawStrokes(canvas, soakInk, soakLoops, fade)
            setBlur(-1f)
            if (glitterOn && motion) shed(t, 7)
        } else if (mode == Mode.SENDING) {
            // waiting on the answer: a slow trickle where the question was
            animating = true
            if (glitterOn && motion && t - lastTrickle > 0.09f && !soakBox.isEmpty) {
                lastTrickle = t
                val x = soakBox.left + glitter.random() * soakBox.width()
                val y = soakBox.top + glitter.random() * soakBox.height()
                glitter.emit(x, y, (glitter.random() * 2 - 1) * 8 * density, (40 + glitter.random() * 60) * density, t, 1.4f + glitter.random())
            }
        }

        live?.let {
            line.alpha = 235
            glow.alpha = 90
            canvas.drawPath(livePath, glow)
            canvas.drawPath(livePath, line)
        }

        if (ringFrom >= 0f && mode == Mode.WRITING) {
            animating = true
            drawRing(canvas, if (motion) min(1f, (t - ringFrom) / ringSeconds) else 1f)
        }

        if (textShown) {
            var alpha = 1f
            if (answerFadeFrom >= 0f) {
                alpha = 1f - (t - answerFadeFrom) / ANSWER_FADE_S
                if (alpha <= 0f) {
                    textShown = false
                    answerFadeFrom = -1f
                }
                animating = true
            }
            if (textShown) {
                val layer = if (alpha < 1f) canvas.saveLayerAlpha(null, (255 * alpha).toInt()) else -1
                val still = writer.draw(canvas, t)
                if (layer >= 0) canvas.restoreToCount(layer)
                if (still) animating = true else if (answerFadeFrom < 0f) written()
            }
        }

        if (glitterOn) glitter.draw(canvas, t) else glitter.clear()
        if (animating || glitter.alive) postInvalidateOnAnimation()
    }

    private fun drawStrokes(canvas: Canvas, ink: List<List<Pt>>, lenses: List<Loop>, alpha: Float) {
        if (alpha <= 0f) return
        glow.alpha = (90 * alpha).toInt()
        line.alpha = (235 * alpha).toInt()
        lensLine.alpha = (245 * alpha).toInt()
        for (l in lenses) {
            val p = closedPathOf(l)
            canvas.drawPath(p, glow)
            canvas.drawPath(p, lensLine)
        }
        for (s in ink) canvas.drawPath(pathOf(s), glow)
        for (s in ink) {
            if (s.size == 1) canvas.drawPoint(s[0].x, s[0].y, line) else canvas.drawPath(pathOf(s), line)
        }
    }

    /** Soften the ink line by [radius] px (or back to sharp for a negative radius). */
    private fun setBlur(radius: Float) {
        if (radius < 0.5f) {
            line.maskFilter = null
            return
        }
        // a new filter only when the radius has moved a little, not every frame
        if (blur == null || abs(radius - soakBlur) >= 0.75f * density) {
            soakBlur = radius
            blur = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        }
        line.maskFilter = blur
    }

    /** Glitter dropping out of the ink as it soaks in. */
    private fun shed(t: Float, count: Int) {
        if (soakPoints.isEmpty()) return
        repeat(count) {
            val p = soakPoints[(glitter.random() * soakPoints.size).toInt().coerceIn(0, soakPoints.size - 1)]
            glitter.emit(p.x, p.y, (glitter.random() * 2 - 1) * 10 * density, (50 + glitter.random() * 90) * density, t, 1.1f + glitter.random() * 0.9f)
        }
    }

    /** The thin gold border of the whole-screen mode, following the display's rounded corners where Android reports them. */
    private fun drawEdge(canvas: Canvas) {
        val half = edgeLine.strokeWidth / 2f
        val radius = if (android.os.Build.VERSION.SDK_INT >= 31) {
            rootWindowInsets?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: 0f
        } else {
            0f
        }
        edgeBox.set(half, half, width - half, height - half)
        val r = (radius - half).coerceAtLeast(0f)
        canvas.drawRoundRect(edgeBox, r, r, edgeLine)
    }

    private fun drawRing(canvas: Canvas, k: Float) {
        val r = RING_DP * density
        val bottom = rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemBars())?.bottom ?: 0
        val cx = width / 2f
        val cy = height - bottom - 72 * density
        ringBox.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawOval(ringBox, ringTrack)
        canvas.drawArc(ringBox, -90f, 360f * k, false, ringFill)
    }

    override fun onDetachedFromWindow() {
        dispose()
        super.onDetachedFromWindow()
    }
}
