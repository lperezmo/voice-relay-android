package com.lperezmo.voicerelay

/**
 * Who has the microphone for a short recording right now, so two of them never
 * collide ("hey timer" and the rename microphone). Only the holder can let go,
 * and a held guard is refused to everyone, the holder included: one recording
 * per hold. Thread-safe. Pure Kotlin, so it is unit tested.
 */
open class MicLock {
    private var holder: String? = null

    /** Takes the microphone for [owner]. False when someone (also [owner]) already has it. */
    @Synchronized
    fun tryAcquire(owner: String): Boolean {
        if (holder != null) return false
        holder = owner
        return true
    }

    /** Lets go of the microphone if [owner] has it; does nothing otherwise. */
    @Synchronized
    fun release(owner: String) {
        if (holder == owner) holder = null
    }

    /** Who has the microphone, or null when it is free. */
    @Synchronized
    fun holder(): String? = holder
}

/** The one guard for the whole app. */
object MicGuard : MicLock() {
    /** The owner name "hey timer" holds it under for a whole request. */
    const val HEY_TIMER = "hey timer"
    /** The owner name the rename microphone holds it under while it listens. */
    const val TIMER_RENAME = "timer rename"
    /** What to show or say when the guard is refused. */
    const val SAY_BUSY = "The microphone is busy."
}
