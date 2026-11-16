package com.lperezmo.voicerelay.glyph

import android.os.SystemClock
import com.lperezmo.voicerelay.glyph.recognizer.Match
import com.lperezmo.voicerelay.glyph.recognizer.Pt

/**
 * The last match, kept in memory only for the undo X and its sheet: the drawing, every
 * shape's score on it, which shape fired, and the sample "Learn from matches" added (if any).
 * Gone when the process goes, and dropped when the X times out unused or a choice is made.
 */
object LastMatch {
    /** Long enough for the 10 s notification; a sheet opened later finds nothing. */
    private const val FRESH_MS = 15_000L

    class Entry(
        val drawing: List<Pt>,
        val ranked: List<Match>,
        val shapeId: String,
        val shapeName: String,
        val actionText: String,
        val learned: List<Pt>?,
        val at: Long = SystemClock.elapsedRealtime(),
    ) {
        val id: Long get() = at
    }

    @Volatile private var entry: Entry? = null

    fun put(e: Entry) {
        entry = e
    }

    fun get(): Entry? = entry?.takeIf { SystemClock.elapsedRealtime() - it.at < FRESH_MS }

    /** Forget [e] unless a newer match has replaced it. */
    fun clear(e: Entry? = null) {
        if (e == null || entry === e) entry = null
    }
}
