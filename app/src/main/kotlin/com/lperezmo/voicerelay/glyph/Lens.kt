package com.lperezmo.voicerelay.glyph

import com.lperezmo.voicerelay.glyph.recognizer.Pt
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The geometry of "Ask the Page" lenses. A lens is a closed loop the user draws around part
 * of the screen: a big one is lit (that part of the screenshot is un-dimmed and sent along
 * with the question), a small one is just ink, like the O of a word. A tap inside a loop
 * flips it between the two.
 *
 * Pure Kotlin so it can be unit tested on the JVM. Coordinates are whatever the caller
 * uses (view pixels on the canvas); only [loopOf] needs the screen density.
 */
object Lens {
    /** Smallest side of a loop's bounding box, in dp, for it to start out lit. */
    const val MIN_DP = 72f

    /** A stroke is closed when the gap between its two ends is at most this share of its path length. */
    const val CLOSE_RATIO = 0.18f

    /** At most this many loops are lit at once, so a question sends at most this many lens images. */
    const val MAX_LIT = 4

    /** A stroke with fewer points than this is a tap or a flick, never a loop. */
    const val MIN_POINTS = 8

    /** A stroke whose path is shorter than this many pixels is never a loop, whatever its shape. */
    const val MIN_PATH_PX = 40f

    /**
     * The enclosed area must be at least this share of the squared path length. A circle is
     * about 0.08 and a 10:1 ellipse about 0.02; a line drawn out and straight back is near 0,
     * and that should not count as a loop even though its ends meet.
     */
    const val MIN_AREA_RATIO = 0.01f

    /**
     * True when [stroke] comes back to where it started: enough points, a long enough path,
     * ends no further apart than [CLOSE_RATIO] of the path length, and some area inside.
     */
    fun isClosed(stroke: List<Pt>): Boolean {
        if (stroke.size < MIN_POINTS) return false
        val length = pathLength(stroke)
        if (length < MIN_PATH_PX) return false
        val first = stroke.first()
        val last = stroke.last()
        val gap = hypot(last.x - first.x, last.y - first.y)
        if (gap / length > CLOSE_RATIO) return false
        return abs(area(stroke)) >= MIN_AREA_RATIO * length * length
    }

    /**
     * The loop [stroke] makes, or null when it is not closed. It starts lit when the smaller
     * side of its bounding box is at least [MIN_DP] dp (with [density] pixels per dp) and
     * fewer than [MAX_LIT] loops are already lit ([litCount]); otherwise it is ink.
     */
    fun loopOf(stroke: List<Pt>, density: Float, litCount: Int): Loop? {
        if (!isClosed(stroke)) return null
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in stroke) {
            minX = min(minX, p.x); maxX = max(maxX, p.x)
            minY = min(minY, p.y); maxY = max(maxY, p.y)
        }
        val big = min(maxX - minX, maxY - minY) >= MIN_DP * density
        return Loop(stroke, lit = big && litCount < MAX_LIT)
    }

    /**
     * True when ([x], [y]) is inside the polygon the stroke's points make, closing the last
     * point back to the first. Even-odd rule, so the inside of a figure eight's two lobes counts.
     */
    fun contains(stroke: List<Pt>, x: Float, y: Float): Boolean {
        if (stroke.size < 3) return false
        var inside = false
        var j = stroke.size - 1
        for (i in stroke.indices) {
            val a = stroke[i]
            val b = stroke[j]
            if ((a.y > y) != (b.y > y)) {
                val crossX = a.x + (y - a.y) * (b.x - a.x) / (b.y - a.y)
                if (x < crossX) inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * Flips the topmost (last drawn) loop that contains ([x], [y]) between lit and ink and
     * returns it. Lighting it is refused when [MAX_LIT] loops are already lit: the loop is
     * then returned unchanged (still not lit), so the caller knows the tap hit a loop. Null
     * when no loop contains the point.
     */
    fun toggle(loops: List<Loop>, x: Float, y: Float): Loop? {
        val hit = loops.lastOrNull { contains(it.stroke, x, y) } ?: return null
        if (hit.lit) {
            hit.lit = false
        } else if (loops.count { it.lit } < MAX_LIT) {
            hit.lit = true
        }
        return hit
    }

    private fun pathLength(stroke: List<Pt>): Float {
        var total = 0f
        for (i in 1 until stroke.size) {
            total += hypot(stroke[i].x - stroke[i - 1].x, stroke[i].y - stroke[i - 1].y)
        }
        return total
    }

    /** Signed shoelace area of the polygon, closed back to the first point. */
    private fun area(stroke: List<Pt>): Float {
        var sum = 0f
        var j = stroke.size - 1
        for (i in stroke.indices) {
            sum += (stroke[j].x * stroke[i].y) - (stroke[i].x * stroke[j].y)
            j = i
        }
        return sum / 2f
    }
}
