package com.lperezmo.voicerelay.glyph

/**
 * Which images one Ask the Page question sends, in order: the [ink] image (when there is
 * qualifying ink), then either [lenses] lens crops or, with the whole-screen button on, one
 * picture of the [wholeScreen]. Never both: while the whole screen goes, the lenses add
 * nothing it does not already hold. Without a screenshot there is neither.
 *
 * Android-free, so the choice is tested on the JVM; [PageImages.build] draws what it says.
 */
data class PagePlan(val ink: Boolean, val lenses: Int, val wholeScreen: Boolean) {
    /** How many images go. */
    val count get() = (if (ink) 1 else 0) + lenses + (if (wholeScreen) 1 else 0)

    companion object {
        /**
         * The plan for a question with qualifying ink ([hasInk]), [litLoops] lit lenses, the
         * whole-screen button on or off ([wholeScreen]) and a screenshot or not ([hasShot]).
         */
        fun of(hasInk: Boolean, litLoops: Int, wholeScreen: Boolean, hasShot: Boolean): PagePlan = when {
            !hasShot -> PagePlan(hasInk, 0, false)
            wholeScreen -> PagePlan(hasInk, 0, true)
            else -> PagePlan(hasInk, litLoops.coerceIn(0, Lens.MAX_LIT), false)
        }
    }
}
