package com.lperezmo.voicerelay

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * The touch on the Glyph pill below the dictate button: a pull down of [pull]
 * (more down than sideways) fires the moment it gets there, so the canvas is up
 * while the finger is still moving, and a tap fires on release. Sideways and
 * upward drags, and a pull that already fired, do nothing more. Kept free of
 * Android so it can be unit tested.
 */
class PullDown(private val pull: Float, private val slop: Float) {
    private var active = false
    private var fired = false
    private var startX = 0f
    private var startY = 0f
    private var moved = 0f

    fun down(x: Float, y: Float) {
        active = true
        fired = false
        startX = x
        startY = y
        moved = 0f
    }

    /** True exactly once per touch, when the finger has come [pull] down. */
    fun move(x: Float, y: Float): Boolean {
        if (!active || fired) return false
        val dx = x - startX
        val dy = y - startY
        moved = max(moved, hypot(dx, dy))
        if (dy > pull && dy > abs(dx)) {
            fired = true
            return true
        }
        return false
    }

    /** True for a tap: lifted without having fired and without moving past [slop]. */
    fun up(): Boolean {
        val tap = active && !fired && moved < slop
        active = false
        return tap
    }

    /** The touch was taken away (ACTION_CANCEL): nothing fires. */
    fun cancel() {
        active = false
    }
}
