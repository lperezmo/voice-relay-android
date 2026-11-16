package com.lperezmo.voicerelay.glyph

import com.lperezmo.voicerelay.glyph.recognizer.Pt
import kotlin.math.hypot

/**
 * Whether the page holds enough handwriting to be a question, so a few stray dots, one dash
 * or a small squiggle are not sent to Claude by accident. Only the ink counts ([PageState.ink],
 * which leaves out lit lenses): a lens on its own is not a question.
 *
 * Enough means at least [MIN_TOTAL_DP] dp of ink in all, and either [MIN_STROKES] strokes
 * longer than a tap ([MIN_STROKE_DP] each), or one long stroke of [ONE_STROKE_DP] (a word
 * written in one go of cursive). Dots (the single points of i and ?) add nothing to either.
 *
 * Pure Kotlin so it can be unit tested on the JVM; lengths come in pixels and [density]
 * (pixels per dp) turns them into dp.
 */
object PageGuard {
    /**
     * Least total ink, in dp. "ok" written small with a finger (a k about 40 dp, 6 mm, tall) is
     * about 140 dp; one small loop is under 100, and three dots are 0.
     */
    const val MIN_TOTAL_DP = 120f

    /** A stroke shorter than this, in dp, is a tap or a flick and does not count as writing. */
    const val MIN_STROKE_DP = 12f

    /** Strokes longer than [MIN_STROKE_DP] needed, unless one stroke is [ONE_STROKE_DP] long. */
    const val MIN_STROKES = 2

    /** A single stroke this long, in dp, is a written word by itself, not a squiggle. */
    const val ONE_STROKE_DP = 400f

    /** True when [ink] (view pixels, [density] pixels per dp) is enough to send as a question. */
    fun enough(ink: List<List<Pt>>, density: Float): Boolean {
        val d = if (density > 0f) density else 1f
        var total = 0f
        var strokes = 0
        var longest = 0f
        for (s in ink) {
            val len = length(s) / d
            total += len
            if (len > MIN_STROKE_DP) strokes++
            if (len > longest) longest = len
        }
        if (total < MIN_TOTAL_DP) return false
        return strokes >= MIN_STROKES || longest >= ONE_STROKE_DP
    }

    /** Path length of one stroke in pixels; a dot is 0. */
    fun length(stroke: List<Pt>): Float {
        var total = 0f
        for (i in 1 until stroke.size) total += hypot(stroke[i].x - stroke[i - 1].x, stroke[i].y - stroke[i - 1].y)
        return total
    }
}
