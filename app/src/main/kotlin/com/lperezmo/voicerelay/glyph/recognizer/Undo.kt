package com.lperezmo.voicerelay.glyph.recognizer

import kotlin.math.abs

/**
 * The rules behind the undo X that shows after a match: which other shapes "Learn as" offers,
 * and how a choice changes the samples. Plain data with no Android in it so it is unit tested.
 */
object Undo {
    /** A shape must score at least this on the drawing to be offered as "Learn as". */
    const val FLOOR = 0.35f
    const val MAX_CHOICES = 3

    /** Every shape other than [matched] scoring at least [FLOOR], best first, at most [MAX_CHOICES]. */
    fun alternatives(ranked: List<Match>, matched: String): List<Match> =
        ranked.filter { it.key != matched && it.score >= FLOOR }.sortedByDescending { it.score }.take(MAX_CHOICES)

    /**
     * What a choice on the undo sheet does to the samples. First the sample "Learn from matches"
     * added to the wrong shape ([wrongId], [learned]) comes out, if it is still there; then, for
     * "Learn as", [drawing] goes into [learnAs] as a learned sample. Returns only the shapes that
     * changed. "New shape" and "Just undo" pass [learnAs] null.
     */
    fun apply(
        samples: Map<String, Samples>,
        wrongId: String,
        learned: List<Pt>?,
        learnAs: String?,
        drawing: List<Pt>,
    ): Map<String, Samples> {
        val out = LinkedHashMap<String, Samples>()
        if (learned != null) {
            samples[wrongId]?.let { set ->
                val after = removeLearned(set, learned)
                if (after !== set) out[wrongId] = after
            }
        }
        if (learnAs != null && learnAs != wrongId) {
            val target = out[learnAs] ?: samples[learnAs]
            target?.addLearned(drawing)?.let { out[learnAs] = it }
        }
        return out
    }

    /**
     * Drop the newest learned sample that is [points] (within the 0.1 px the shapes file
     * rounds to). Originals are never touched. The same instance back when nothing matched.
     */
    fun removeLearned(set: Samples, points: List<Pt>): Samples {
        for (i in set.all.indices.reversed()) {
            if (!set.isLearned(i)) break
            if (same(set.all[i], points)) return set.remove(i)
        }
        return set
    }

    private fun same(a: List<Pt>, b: List<Pt>): Boolean =
        a.size == b.size && a.indices.all { i -> abs(a[i].x - b[i].x) <= 0.06f && abs(a[i].y - b[i].y) <= 0.06f && a[i].stroke == b[i].stroke }
}
