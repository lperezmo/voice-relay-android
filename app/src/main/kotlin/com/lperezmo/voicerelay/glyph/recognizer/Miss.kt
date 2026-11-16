package com.lperezmo.voicerelay.glyph.recognizer

/**
 * What the canvas offers when a drawing is not recognised: one "Learn as <shape>" choice for
 * every shape that scored at least [NEAR] (still under the threshold), best first, at most
 * [MAX_CHOICES]. "New shape" and "Dismiss" are always offered and need no rule here.
 */
object Miss {
    const val NEAR = 0.45f
    const val MAX_CHOICES = 3

    /** The shapes worth offering "Learn as", best first. Empty on a match or when nothing came close. */
    fun learnChoices(ranked: List<Match>, threshold: Float): List<Match> {
        if (ranked.any { it.score >= threshold }) return emptyList()
        return ranked.filter { it.score >= NEAR }.sortedByDescending { it.score }.take(MAX_CHOICES)
    }
}
