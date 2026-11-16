package com.lperezmo.voicerelay.glyph

import com.lperezmo.voicerelay.glyph.recognizer.Pt
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The plain arithmetic behind the images Ask the Page sends: how big the ink is, how much
 * to shrink a picture so its longest edge fits, and which part of the screenshot a loop
 * covers. No Android here so it can be tested on the JVM.
 */
object PageGeometry {
    /** A rectangle in floating point pixels. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top

        /** The same box moved by [dx], [dy]. */
        fun offset(dx: Float, dy: Float) = Box(left + dx, top + dy, right + dx, bottom + dy)
    }

    /** A rectangle in whole pixels: [left], [top] inclusive, [right], [bottom] exclusive. */
    data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /** The box around every point of [strokes], grown by [pad] on each side; null when there are no points. */
    fun bounds(strokes: List<List<Pt>>, pad: Float = 0f): Box? {
        var l = Float.POSITIVE_INFINITY
        var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY
        var b = Float.NEGATIVE_INFINITY
        var any = false
        for (s in strokes) for (p in s) {
            any = true
            l = min(l, p.x); t = min(t, p.y)
            r = max(r, p.x); b = max(b, p.y)
        }
        if (!any) return null
        return Box(l - pad, t - pad, r + pad, b + pad)
    }

    /**
     * The factor that shrinks a [width] x [height] picture so its longest edge is at most
     * [maxEdge]. Never above 1: small pictures are not blown up.
     */
    fun scaleToMaxEdge(width: Float, height: Float, maxEdge: Int): Float {
        val longest = max(width, height)
        if (longest <= 0f || longest <= maxEdge) return 1f
        return maxEdge / longest
    }

    /** The whole-pixel size of a [width] x [height] picture after [scale], at least 1 x 1 and never past [maxEdge]. */
    fun scaledSize(width: Float, height: Float, scale: Float, maxEdge: Int): Pair<Int, Int> {
        val w = ceil(width * scale).toInt().coerceIn(1, max(1, maxEdge))
        val h = ceil(height * scale).toInt().coerceIn(1, max(1, maxEdge))
        return w to h
    }

    /**
     * [box] rounded outwards to whole pixels and cut down to a [width] x [height] bitmap.
     * Null when nothing of it is inside the bitmap.
     */
    fun clampInto(box: Box, width: Int, height: Int): PixelRect? {
        val l = floor(box.left).toInt().coerceIn(0, width)
        val t = floor(box.top).toInt().coerceIn(0, height)
        val r = ceil(box.right).toInt().coerceIn(0, width)
        val b = ceil(box.bottom).toInt().coerceIn(0, height)
        if (r <= l || b <= t) return null
        return PixelRect(l, t, r, b)
    }
}
