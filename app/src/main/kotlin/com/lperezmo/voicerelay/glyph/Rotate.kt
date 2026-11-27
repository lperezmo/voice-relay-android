package com.lperezmo.voicerelay.glyph

/** Which way the screen is held by "Flip the screen". */
enum class Hold { UPRIGHT, SIDEWAYS }

/**
 * What the two rotation actions do: the hold to put on (null lets go) and what to say.
 * Pure Kotlin; the window that holds the screen is [com.lperezmo.voicerelay.RotateForcer].
 */
object Rotate {
    data class Step(val hold: Hold?, val say: String)

    /** The opposite of what is held, or of what is showing when nothing is held. */
    fun flip(held: Hold?, showingSideways: Boolean): Step {
        val sideways = if (held != null) held == Hold.UPRIGHT else !showingSideways
        return if (sideways) Step(Hold.SIDEWAYS, "Screen held sideways.") else Step(Hold.UPRIGHT, "Screen held upright.")
    }

    fun auto(held: Hold?): Step =
        Step(null, if (held == null) "The screen isn't held." else "Rotation is back to auto.")

    const val NO_SERVICE = "Turn on the floating button first."
    const val FAILED = "Couldn't hold the screen."
}
