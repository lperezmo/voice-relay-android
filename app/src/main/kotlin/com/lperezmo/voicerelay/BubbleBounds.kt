package com.lperezmo.voicerelay

/**
 * Where the dictate button may sit so it can always be dragged again. The
 * button is [ring] px square at (x, y); the pills riding with it reach [above]
 * px over its top and [below] px under its bottom. Everything stays inside the
 * safe area ([left], [top], [right], [bottom]: clear of the status bar, nav bar
 * and cutout), so a pill is never pushed against the screen edge and onto the
 * button. When the screen is too short for the whole stack (landscape on a
 * small phone), the button itself still stays inside. Kept free of Android so
 * it can be unit tested.
 */
class BubbleBounds(
    private val left: Int,
    private val top: Int,
    private val right: Int,
    private val bottom: Int,
    private val ring: Int,
    private val above: Int,
    private val below: Int,
) {
    fun clampX(x: Int): Int = x.coerceIn(left, maxOf(left, right - ring))

    fun clampY(y: Int): Int {
        val lo = top + above
        val hi = bottom - ring - below
        return if (lo <= hi) y.coerceIn(lo, hi) else y.coerceIn(top, maxOf(top, bottom - ring))
    }

    /** Against the nearer side, [margin] in from it. */
    fun snapX(x: Int, margin: Int): Int = sideX(isRight(x), margin)

    /** True when a button at [x] sits on the right half. */
    fun isRight(x: Int): Boolean = x + ring / 2 > (left + right) / 2

    /** Against the right or left side, [margin] in from it. */
    fun sideX(onRight: Boolean, margin: Int): Int =
        if (onRight) clampX(right - ring - margin) else clampX(left + margin)

    companion object {
        /** The middle of a [ring] px button at [y], as a fraction of a [height] px screen, kept in 0..1. */
        fun fraction(y: Int, ring: Int, height: Int): Float =
            if (height <= 0) 0.5f else ((y + ring / 2f) / height).coerceIn(0f, 1f)

        /** The y that puts the button's middle at [fraction] of a [height] px screen; clamp it afterwards. */
        fun yAt(fraction: Float, ring: Int, height: Int): Int =
            (fraction.coerceIn(0f, 1f) * height - ring / 2f).toInt()
    }
}
