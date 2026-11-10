package com.lperezmo.voicerelay

/**
 * Whether the wake word's microphone hears anything. Android does not fail a
 * recording when another app or the microphone privacy switch takes the mic
 * away: it hands over silence. Two signs of that, either one enough: Android
 * marks our recording silenced (AudioRecordingConfiguration.isClientSilenced),
 * or every sample has been exactly zero for a few seconds ([ZeroRun]), which a
 * real room never is. Android-free, so it is unit tested; the service feeds it
 * on the main thread and shows [deaf] in the notification.
 */
class MicHearing {
    private var silenced = false
    private var zeros = false

    /** True while the mic cannot hear right now. */
    val deaf get() = silenced || zeros

    /** Android silenced, or unsilenced, our recording. Returns true when [deaf] changed. */
    fun onSilenced(value: Boolean): Boolean = change { silenced = value }

    /** The mic started or stopped delivering only zeros ([ZeroRun]). Returns true when [deaf] changed. */
    fun onZeros(value: Boolean): Boolean = change { zeros = value }

    /** A new recording: nothing is known about it yet. Returns true when [deaf] changed. */
    fun reset(): Boolean = change { silenced = false; zeros = false }

    private inline fun change(block: () -> Unit): Boolean {
        val before = deaf
        block()
        return deaf != before
    }

    companion object {
        /** The notification text while listening for [phrase]. */
        fun listeningText(phrase: String, deaf: Boolean): String =
            if (deaf) "Cannot hear right now: another app is using the microphone, or microphone access is off."
            else "Listening for $phrase. Audio stays on the phone."
    }
}

/**
 * Counts all-zero chunks in a row, on the mic thread. [feed] returns the new
 * state when it changes (true: [chunks] zero chunks in a row; false: sound
 * again) and null otherwise.
 */
class ZeroRun(private val chunks: Int) {
    private var run = 0

    fun feed(peak: Int): Boolean? {
        if (peak != 0) {
            val wasZeros = run >= chunks
            run = 0
            return if (wasZeros) false else null
        }
        if (run < chunks) {
            run++
            if (run == chunks) return true
        }
        return null
    }
}
