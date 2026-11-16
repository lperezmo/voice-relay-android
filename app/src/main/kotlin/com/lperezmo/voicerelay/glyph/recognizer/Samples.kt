package com.lperezmo.voicerelay.glyph.recognizer

/**
 * A shape's samples, kept in two runs: the originals (drawn on the shape's page, or shipped
 * as starters) first, then the ones learned from matches, oldest first. Plain data with no
 * Android in it so the rules below are unit tested.
 *
 * The rules: at most [MAX] samples. A learned sample that does not fit pushes out the oldest
 * learned one; originals are never pushed out. A new original that does not fit also pushes
 * out the oldest learned one, and is refused only when all [MAX] are originals.
 */
data class Samples(val all: List<List<Pt>>, val originals: Int) {
    init {
        require(originals in 0..all.size) { "originals $originals out of 0..${all.size}" }
    }

    val learned: Int get() = all.size - originals
    val full: Boolean get() = all.size >= MAX

    fun isLearned(index: Int) = index >= originals

    /** A drawing learned from a match. Never drops an original; null when all [MAX] are originals. */
    fun addLearned(points: List<Pt>): Samples? = when {
        all.size < MAX -> Samples(all + listOf(points), originals)
        learned > 0 -> Samples(all.filterIndexed { i, _ -> i != originals } + listOf(points), originals)
        else -> null
    }

    /** A drawing made on the shape's page. Null when there is no room (all [MAX] are originals). */
    fun addOriginal(points: List<Pt>): Samples? {
        val room = when {
            all.size < MAX -> all
            learned > 0 -> all.filterIndexed { i, _ -> i != originals }
            else -> return null
        }
        return Samples(room.take(originals) + listOf(points) + room.drop(originals), originals + 1)
    }

    /** Swap the drawing at [index]. A redrawn learned sample becomes an original. */
    fun replace(index: Int, points: List<Pt>): Samples {
        if (index < originals) return Samples(all.mapIndexed { i, p -> if (i == index) points else p }, originals)
        return remove(index).addOriginal(points)!!
    }

    fun remove(index: Int): Samples {
        require(index in all.indices)
        return Samples(all.filterIndexed { i, _ -> i != index }, if (index < originals) originals - 1 else originals)
    }

    companion object {
        const val MAX = 10

        /** Older files have no originals count: everything in them was drawn by hand. */
        fun of(all: List<List<Pt>>, originals: Int = all.size) = Samples(all, originals.coerceIn(0, all.size))
    }
}
