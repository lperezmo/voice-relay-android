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
    fun snapX(x: Int, margin: Int): Int =
        if (x + ring / 2 > (left + right) / 2) clampX(right - ring - margin) else clampX(left + margin)
}
