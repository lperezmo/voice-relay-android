package com.lperezmo.voicerelay

import kotlin.math.sqrt

/**
 * Decides when someone has finished speaking, from the recording as it comes
 * in. It learns how loud the room is, calls anything clearly louder speech, and
 * says stop after [silenceMs] of quiet that follows speech, or when nothing at
 * all was said for [waitMs]. Fed from the recorder's thread; kept free of
 * Android so it can be unit tested with made-up sound.
 */
class EndOfSpeech(
    private val silenceMs: Long = SILENCE_MS,
    private val waitMs: Long = WAIT_MS,
    private val sampleRate: Int = 16_000,
) {
    /** Speech was heard at some point. */
    var heardSpeech = false
        private set
    private var floor = Double.NaN
    private var quietMs = 0L
    private var totalMs = 0L

    /**
     * Takes the next [length] bytes of 16-bit little-endian mono sound. True when the
     * recording should stop: the speaker has gone quiet, or never said anything.
     */
    fun feed(pcm: ByteArray, length: Int): Boolean {
        val samples = length / 2
        if (samples == 0) return false
        var sum = 0.0
        for (i in 0 until samples) {
            val v = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toDouble()
            sum += v * v
        }
        val level = sqrt(sum / samples)
        val ms = samples * 1000L / sampleRate
        totalMs += ms

        // The quietest the room has been. It creeps up slowly, so a room that gets noisier is
        // learned, but far too slowly for one long sentence to become the new "quiet".
        floor = if (floor.isNaN()) level else minOf(level, maxOf(floor, 1.0) * (1.0 + FLOOR_RISE_PER_MS * ms))
        val speech = level > maxOf(MIN_SPEECH_LEVEL, floor * SPEECH_FACTOR)
        if (speech) {
            heardSpeech = true
            quietMs = 0
        } else {
            quietMs += ms
        }
        return if (heardSpeech) quietMs >= silenceMs else totalMs >= waitMs
    }

    companion object {
        /** Quiet after speech that ends the recording. */
        const val SILENCE_MS = 1_500L
        /** With no speech at all, give up after this long. */
        const val WAIT_MS = 6_000L
        /** The longest a request is recorded. */
        const val MAX_SECONDS = 15
        /** Sound has to be this loud (RMS of 16-bit samples) and [SPEECH_FACTOR] times the room to count as speech. */
        const val MIN_SPEECH_LEVEL = 500.0
        const val SPEECH_FACTOR = 3.0
        /** The room's level may double in about 7 seconds: under a tripling within the 15 s a request can last. */
        private const val FLOOR_RISE_PER_MS = 0.0001
    }
}

/**
 * At most [max] requests a day, so a wake phrase that misfires all day cannot run up a
 * bill. The day and its count are kept by the caller ([AppSettings.timerCapDay]); a new
 * day starts from zero.
 */
class DailyCap(private val max: Int = TIMER_REQUESTS_PER_DAY) {
    /** Whether one more request is [allowed] today, and the [day] and [count] to save either way. */
    data class Result(val allowed: Boolean, val day: String, val count: Int)

    fun tryAcquire(today: String, savedDay: String, savedCount: Int): Result {
        val used = if (savedDay == today) savedCount.coerceAtLeast(0) else 0
        return if (used < max) Result(true, today, used + 1) else Result(false, today, used)
    }

    companion object {
        /** Far more timers than a day of cooking needs; each is one transcription and a few Claude requests. */
        const val TIMER_REQUESTS_PER_DAY = 100
        const val SAY_LIMIT = "That's the limit of timer requests for today."
    }
}
