package com.lperezmo.voicerelay.glyph

import com.lperezmo.voicerelay.glyph.recognizer.Pt

/**
 * A closed stroke on the "Ask the Page" canvas. When [lit] it is a lens: the screen inside
 * it is un-dimmed and sent with the question. When not lit it is plain ink.
 */
class Loop(val stroke: List<Pt>, var lit: Boolean)

/** One finished question and its answer, kept so a follow-up stays in the same conversation. */
class Turn(val question: String, val answer: String)

/**
 * Everything drawn on the page for the question being written, plus the finished turns.
 *
 * [ink] holds every stroke that is sent as handwriting, which includes the strokes of
 * loops that are not lit. [loops] holds every closed stroke, lit or not. Use [addStroke]
 * and [toggleAt] to keep the two in step; they can also be edited directly.
 */
class PageState {
    val ink: MutableList<List<Pt>> = mutableListOf()
    val loops: MutableList<Loop> = mutableListOf()
    val turns: MutableList<Turn> = mutableListOf()

    /**
     * The whole-screen button is on for this question: the page sees the whole screenshot
     * instead of the lenses (see [PagePlan]). Off again after every send and in [clearQuestion].
     */
    var wholeScreen = false

    /** The loops that are lenses right now, in the order they were drawn. */
    val litLoops: List<Loop> get() = loops.filter { it.lit }

    /** Empties [ink] and [loops] and turns [wholeScreen] off for the next question; [turns] are kept. */
    fun clearQuestion() {
        ink.clear()
        loops.clear()
        wholeScreen = false
    }

    /**
     * Files a finished stroke: a closed one becomes a [Loop] (see [Lens.loopOf]) and, unless
     * it came out lit, is also ink; anything else is ink. Returns the new loop, or null when
     * the stroke was not closed.
     */
    fun addStroke(stroke: List<Pt>, density: Float): Loop? {
        val loop = Lens.loopOf(stroke, density, loops.count { it.lit })
        if (loop != null) loops.add(loop)
        if (loop == null || !loop.lit) ink.add(stroke)
        return loop
    }

    /**
     * A tap at ([x], [y]): flips the topmost loop under it (see [Lens.toggle]) and moves its
     * stroke into or out of [ink] to match. Returns the loop that was hit, or null.
     */
    fun toggleAt(x: Float, y: Float): Loop? {
        val loop = Lens.toggle(loops, x, y) ?: return null
        val at = ink.indexOfFirst { it === loop.stroke }
        if (loop.lit && at >= 0) ink.removeAt(at)
        if (!loop.lit && at < 0) ink.add(loop.stroke)
        return loop
    }
}
