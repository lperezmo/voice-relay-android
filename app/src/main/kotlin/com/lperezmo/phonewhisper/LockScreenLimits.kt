package com.lperezmo.phonewhisper

/**
 * Guards against pocket and bedside taps while the phone is locked: each
 * locked recording stops after [maxSeconds], and after [maxRecordings] of
 * them the button refuses to record until the phone is unlocked.
 */
class LockScreenLimits(val maxSeconds: Int = MAX_SECONDS, val maxRecordings: Int = MAX_RECORDINGS) {

    sealed class Decision {
        /** Record, stopping after [maxSeconds]. [remaining] locked recordings are left after this one. */
        data class Allow(val maxSeconds: Int, val remaining: Int?) : Decision()
        object UnlockRequired : Decision()
    }

    /** Recordings started since the phone was last unlocked. */
    var used = 0
        private set

    /** Call when a recording is about to start. Counts it when the phone is locked. */
    fun onStart(locked: Boolean, normalMaxSeconds: Int): Decision {
        if (!locked) return Decision.Allow(normalMaxSeconds, null)
        if (used >= maxRecordings) return Decision.UnlockRequired
        used++
        return Decision.Allow(minOf(maxSeconds, normalMaxSeconds), maxRecordings - used)
    }

    /** The phone was unlocked: the locked-recording count starts over. */
    fun onUnlock() {
        used = 0
    }

    /** New cap for a recording that is running when the screen locks. */
    fun capWhenLocked(elapsedSeconds: Double): Int = elapsedSeconds.toInt() + maxSeconds

    companion object {
        const val MAX_SECONDS = 120
        const val MAX_RECORDINGS = 5
    }
}
