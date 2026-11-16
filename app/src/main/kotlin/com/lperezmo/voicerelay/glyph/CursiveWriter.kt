package com.lperezmo.voicerelay.glyph

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import com.lperezmo.voicerelay.R
import kotlin.math.max
import kotlin.math.min

/**
 * Writes Ask the Page's answer in gold cursive (Dancing Script). A pen traces the
 * outline of each letter in turn at a steady speed, with glitter falling from the
 * pen point, and once the pen has passed a letter its outline fills with gold.
 *
 * Call [layout] with the text and where it goes, then [start], then [draw] every
 * frame until it returns false. The writer only emits flecks into [glitter]: the
 * view that owns the glitter draws it (with the same clock passed to [draw]) and
 * keeps animating while it is alive. [finishNow] skips straight to the written
 * text, for reduced motion or a tap.
 *
 * Everything heavy (the outline of every letter, its length and its fill) is built
 * in [layout]; a frame only measures the part of the current outline written so far.
 */
class CursiveWriter(context: Context, private val glitter: Glitter) {
    private companion object {
        const val LARGEST_SP = 30f
        const val SMALLEST_SP = 20f
        const val STEP_SP = 1f
        const val MAX_HEIGHT_FRACTION = 0.6f
        const val BOTTOM_ROOM_DP = 96f
        const val WEIGHT = 500
        const val PEN_LIFT_S = 0.03f
        const val FILL_S = 0.25f
        const val FLECK_GAP_DP = 12f
        const val MAX_FLECKS_PER_FRAME = 10
        const val MIN_CONTOUR_PX = 0.5f
    }

    /** One closed outline of a glyph, in pen order. */
    private class Contour(val path: Path, val length: Float)

    /** One character's outlines: contours [first]..[last], and their union to fill. */
    private class Letter(val first: Int, val last: Int, val fill: Path)

    private val res = context.resources
    private val density = res.displayMetrics.density
    private val screenHeight = res.displayMetrics.heightPixels

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = try {
            res.getFont(R.font.glyph_dancing_script)
        } catch (_: Exception) {
            Typeface.SERIF
        }
        // the font is variable (wght 400..700); a little heavier than regular reads better in gold
        try {
            setFontVariationSettings("'wght' $WEIGHT")
        } catch (_: Exception) {
        }
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = hsl(44f, 100f, 72f)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.3f * density
        color = hsl(44f, 100f, 78f)
        alpha = 235
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 5f * density
        color = hsl(44f, 100f, 62f)
        alpha = 90
        maskFilter = BlurMaskFilter(4f * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val tip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = hsl(44f, 100f, 88f)
    }
    private val tipGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = hsl(44f, 100f, 65f)
        alpha = 110
        maskFilter = BlurMaskFilter(5f * density, BlurMaskFilter.Blur.NORMAL)
    }

    private var contours: List<Contour> = emptyList()
    private var letters: List<Letter> = emptyList()
    private var letterEnds = FloatArray(0)
    private var timeline = PenTimeline(FloatArray(0), 0f, 0f)

    // the letters whose fill has finished fading in, merged into one path
    private val done = Path()
    private var merged = 0

    // reused every frame
    private val cursor = PenCursor()
    private val partial = Path()
    private val penMeasure = PathMeasure()
    private var penIndex = -1
    private var lastEmit = 0f
    private val pos = FloatArray(2)

    private var startAt = 0f
    private var started = false
    private var finished = false

    /** The bottom of the laid-out text, in the same coordinates as [layout]'s top. */
    var bottomPx = 0f
        private set

    /**
     * Lays [text] out in lines no wider than [widthPx], its first line's top at
     * [topPx] and its left edge at [leftPx]. The text is about 30sp, shrinking toward
     * 20sp when it would take more than about 60 percent of the screen's height (or
     * run into the bottom of the screen). Resets any writing in progress.
     */
    fun layout(text: String, widthPx: Int, topPx: Float, leftPx: Float) {
        started = false
        finished = false
        resetProgress()

        val width = max(1f, widthPx.toFloat())
        val room = min(screenHeight * MAX_HEIGHT_FRACTION, screenHeight - topPx - BOTTOM_ROOM_DP * density)
        val fit = CursiveMath.fit(
            text, width, room, sp(LARGEST_SP), sp(SMALLEST_SP), sp(STEP_SP),
            measure = { s, size -> paint.textSize = size; paint.measureText(s) },
            lineHeight = { size -> paint.textSize = size; paint.fontSpacing },
        )
        paint.textSize = fit.size
        val metrics = paint.fontMetrics
        val spacing = paint.fontSpacing

        val outContours = ArrayList<Contour>()
        val outLetters = ArrayList<Letter>()
        val linePath = Path()
        val measure = PathMeasure()
        val bounds = RectF()
        var baseline = topPx - metrics.ascent
        for (row in fit.lines) {
            if (row.isNotEmpty()) {
                addLine(row, leftPx, baseline, linePath, measure, bounds, outContours, outLetters)
            }
            baseline += spacing
        }
        bottomPx = if (fit.lines.isEmpty()) topPx else baseline - spacing + metrics.descent

        contours = outContours
        letters = outLetters
        val lengths = FloatArray(outContours.size) { outContours[it].length }
        timeline = PenTimeline(lengths, CursiveMath.duration(CursiveMath.words(text)), PEN_LIFT_S)
        letterEnds = FloatArray(outLetters.size) { timeline.endOf(outLetters[it].last) }
    }

    /** Splits one line's text outline into contours, grouped by the character each belongs to. */
    private fun addLine(
        row: String,
        left: Float,
        baseline: Float,
        linePath: Path,
        measure: PathMeasure,
        bounds: RectF,
        outContours: MutableList<Contour>,
        outLetters: MutableList<Letter>,
    ) {
        linePath.reset()
        paint.getTextPath(row, 0, row.length, left, baseline, linePath)

        val widths = FloatArray(row.length)
        paint.getTextWidths(row, widths)
        val edges = FloatArray(row.length)
        var x = left
        for (i in widths.indices) {
            x += widths[i]
            edges[i] = x
        }

        val paths = ArrayList<Path>()
        val lengths = ArrayList<Float>()
        val owners = ArrayList<Int>()
        measure.setPath(linePath, false)
        while (true) {
            val length = measure.length
            if (length >= MIN_CONTOUR_PX) {
                val p = Path()
                measure.getSegment(0f, length, p, true)
                p.computeBounds(bounds, true)
                paths += p
                lengths += length
                owners += CursiveMath.charAt(edges, bounds.centerX())
            }
            if (!measure.nextContour()) break
        }
        if (paths.isEmpty()) return

        val keys = IntArray(owners.size) { owners[it] }
        val order = CursiveMath.order(keys)
        var i = 0
        while (i < order.size) {
            val owner = keys[order[i]]
            val first = outContours.size
            val fill = Path()
            while (i < order.size && keys[order[i]] == owner) {
                val c = order[i]
                outContours += Contour(paths[c], lengths[c])
                fill.addPath(paths[c])
                i++
            }
            outLetters += Letter(first, outContours.size - 1, fill)
        }
    }

    /** Starts the pen at time [nowSeconds] (the same clock as [draw] and the glitter). */
    fun start(nowSeconds: Float) {
        resetProgress()
        startAt = nowSeconds
        started = true
        finished = false
    }

    /** Jumps to the fully written text: no pen, no glitter, every letter filled. */
    fun finishNow() {
        while (merged < letters.size) mergeNext()
        finished = true
    }

    /**
     * Draws everything written by [nowSeconds]: filled letters, outlines being traced,
     * the pen and its glitter (emitted, not drawn). True while the pen is still
     * writing or a letter is still filling, so the caller keeps invalidating.
     */
    fun draw(canvas: Canvas, nowSeconds: Float): Boolean {
        if (finished) {
            drawDone(canvas)
            return false
        }
        if (!started) return false
        val t = nowSeconds - startAt
        while (merged < letters.size && t >= letterEnds[merged] + FILL_S) mergeNext()
        drawDone(canvas)

        timeline.locate(t, cursor)
        val k = cursor.index
        var li = merged
        while (li < letters.size && letters[li].first <= k) {
            val letter = letters[li]
            // outlines of this letter the pen has already finished
            for (c in letter.first..min(letter.last, k - 1)) {
                val p = contours[c].path
                canvas.drawPath(p, glow)
                canvas.drawPath(p, line)
            }
            if (k > letter.last) {
                val a = min(1f, max(0f, (t - letterEnds[li]) / FILL_S))
                fillPaint.alpha = (255 * a).toInt()
                canvas.drawPath(letter.fill, fillPaint)
                fillPaint.alpha = 255
            }
            li++
        }

        if (k < contours.size) drawPen(canvas, k, cursor.distance, nowSeconds)
        return t < timeline.total + FILL_S
    }

    private fun drawPen(canvas: Canvas, k: Int, distance: Float, now: Float) {
        if (k != penIndex) {
            penMeasure.setPath(contours[k].path, false)
            penIndex = k
            lastEmit = 0f
            if (penMeasure.getPosTan(0f, pos, null)) glitter.trail(pos[0], pos[1], now)
        }
        if (distance > 0f) {
            partial.reset()
            penMeasure.getSegment(0f, distance, partial, true)
            canvas.drawPath(partial, glow)
            canvas.drawPath(partial, line)
        }
        // glitter spaced along the stretch the pen covered since the last frame
        val gap = FLECK_GAP_DP * density
        var emitted = 0
        while (distance - lastEmit >= gap && emitted < MAX_FLECKS_PER_FRAME) {
            lastEmit += gap
            if (penMeasure.getPosTan(lastEmit, pos, null)) {
                glitter.trail(pos[0], pos[1], now)
                if (glitter.random() < 0.3f) glitter.trail(pos[0], pos[1], now)
            }
            emitted++
        }
        // a slow frame: skip the flecks it could not place rather than bunching them up
        if (distance - lastEmit >= gap) lastEmit = distance

        if (penMeasure.getPosTan(distance, pos, null)) {
            canvas.drawCircle(pos[0], pos[1], 5f * density, tipGlow)
            canvas.drawCircle(pos[0], pos[1], 1.8f * density, tip)
        }
    }

    private fun drawDone(canvas: Canvas) {
        if (merged == 0) return
        canvas.drawPath(done, fillPaint)
        canvas.drawPath(done, line)
    }

    private fun mergeNext() {
        done.addPath(letters[merged].fill)
        merged++
    }

    private fun resetProgress() {
        done.reset()
        merged = 0
        penIndex = -1
        lastEmit = 0f
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, res.displayMetrics)
}
