package com.lperezmo.voicerelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerVoiceTest {

    // --- EndOfSpeech ---

    /**
     * [ms] of 16 kHz 16-bit little-endian mono sound: a square wave at [amplitude], so its
     * level (RMS) is exactly [amplitude]. 0 is silence.
     */
    private fun chunk(amplitude: Int, ms: Int = 100): ByteArray {
        val samples = 16_000 * ms / 1000
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = if (i % 2 == 0) amplitude else -amplitude
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    private fun EndOfSpeech.feed(pcm: ByteArray) = feed(pcm, pcm.size)

    /** Feeds [count] chunks of 100 ms at [amplitude] and returns what each feed said. */
    private fun EndOfSpeech.feedMany(amplitude: Int, count: Int): List<Boolean> {
        val c = chunk(amplitude)
        return List(count) { feed(c) }
    }

    private val loud = 8_000

    /** A quiet start so the room level is known, then [speechChunks] x 100 ms of speech. */
    private fun EndOfSpeech.quietThenSpeech(speechChunks: Int = 10) {
        assertTrue(feedMany(0, 5).none { it })
        assertTrue(feedMany(loud, speechChunks).none { it })
        assertTrue(heardSpeech)
    }

    @Test fun `the chunk helper makes sound at the asked level`() {
        val c = chunk(1_000, 100)
        assertEquals(3_200, c.size)
        // 1000 = 0x03E8, little-endian; -1000 = 0xFC18.
        assertEquals(0xE8.toByte(), c[0])
        assertEquals(0x03.toByte(), c[1])
        assertEquals(0x18.toByte(), c[2])
        assertEquals(0xFC.toByte(), c[3])
    }

    @Test fun `silence only stops after the wait, with no speech heard`() {
        val e = EndOfSpeech()
        val said = e.feedMany(0, 60)
        assertTrue(said.dropLast(1).none { it })
        assertTrue(said.last())
        assertFalse(e.heardSpeech)
        assertEquals(6_000L, EndOfSpeech.WAIT_MS)
    }

    @Test fun `speech then quiet stops exactly when the quiet reaches the silence time`() {
        val e = EndOfSpeech()
        e.quietThenSpeech()
        val said = e.feedMany(0, 15)
        assertTrue("stopped before 1.5 s of quiet: $said", said.dropLast(1).none { it })
        assertTrue(said.last())
        assertTrue(e.heardSpeech)
        assertEquals(1_500L, EndOfSpeech.SILENCE_MS)
    }

    @Test fun `speech that keeps going never stops`() {
        val e = EndOfSpeech()
        e.feedMany(0, 5)
        // The longest a request is recorded, and well past the no-speech wait.
        val said = e.feedMany(loud, EndOfSpeech.MAX_SECONDS * 10)
        assertTrue(said.none { it })
        assertTrue(e.heardSpeech)
    }

    @Test fun `a pause shorter than the silence time starts the count over`() {
        val e = EndOfSpeech()
        e.quietThenSpeech()
        assertTrue(e.feedMany(0, 14).none { it })
        assertTrue(e.feedMany(loud, 3).none { it })
        val said = e.feedMany(0, 15)
        assertTrue(said.dropLast(1).none { it })
        assertTrue(said.last())
    }

    @Test fun `steady background noise at one level is not speech`() {
        val e = EndOfSpeech()
        val said = e.feedMany(3_000, 60)
        assertTrue(said.dropLast(1).none { it })
        assertTrue(said.last())
        assertFalse(e.heardSpeech)
    }

    @Test fun `speech clearly louder than a noisy room is heard, and the room after it counts as quiet`() {
        val e = EndOfSpeech()
        assertTrue(e.feedMany(1_000, 10).none { it })
        assertFalse(e.heardSpeech)
        assertTrue(e.feedMany(6_000, 10).none { it })
        assertTrue(e.heardSpeech)
        val said = e.feedMany(1_000, 15)
        assertTrue(said.dropLast(1).none { it })
        assertTrue(said.last())
    }

    @Test fun `sound only a little louder than a noisy room is not speech`() {
        val e = EndOfSpeech()
        e.feedMany(2_000, 10)
        // Twice the room, not SPEECH_FACTOR times.
        e.feedMany(4_000, 10)
        assertFalse(e.heardSpeech)
    }

    @Test fun `quiet sound under the minimum speech level is not speech, even in a silent room`() {
        val e = EndOfSpeech()
        e.feedMany(0, 5)
        val said = e.feedMany(EndOfSpeech.MIN_SPEECH_LEVEL.toInt() - 100, 55)
        assertFalse(e.heardSpeech)
        assertTrue(said.dropLast(1).none { it })
        assertTrue(said.last())

        val above = EndOfSpeech()
        above.feedMany(0, 5)
        above.feedMany(EndOfSpeech.MIN_SPEECH_LEVEL.toInt() + 100, 1)
        assertTrue(above.heardSpeech)
    }

    @Test fun `an empty or one-byte chunk is ignored`() {
        val e = EndOfSpeech()
        assertTrue(e.feedMany(0, 59).none { it })
        assertFalse(e.feed(ByteArray(0), 0))
        assertFalse(e.feed(ByteArray(1), 1))
        // A buffer with room in it, but nothing read into it.
        assertFalse(e.feed(ByteArray(3_200), 0))
        assertFalse(e.feed(ByteArray(3_200), 1))
        assertFalse(e.heardSpeech)
        // None of those counted as time: the 60th real chunk is the one that stops.
        assertTrue(e.feed(chunk(0)))
    }

    @Test fun `only the given length of the buffer is read`() {
        val e = EndOfSpeech()
        e.feedMany(0, 5)
        // Loud sound past the length must not count.
        val buffer = chunk(0) + chunk(loud)
        e.feed(buffer, 3_200)
        assertFalse(e.heardSpeech)
    }

    @Test fun `custom silence and wait times are respected`() {
        val silent = EndOfSpeech(silenceMs = 500, waitMs = 1_000)
        val waited = silent.feedMany(0, 10)
        assertTrue(waited.dropLast(1).none { it })
        assertTrue(waited.last())

        val spoken = EndOfSpeech(silenceMs = 500, waitMs = 1_000)
        spoken.feedMany(0, 3)
        // Longer than the wait: speech was heard, so only the silence time matters now.
        assertTrue(spoken.feedMany(loud, 20).none { it })
        val said = spoken.feedMany(0, 5)
        assertTrue(said.dropLast(1).none { it })
        assertTrue(said.last())
    }

    // --- DailyCap ---

    private val today = "2026-11-21"

    @Test fun `the first request of a day is allowed and counts one`() {
        assertEquals(DailyCap.Result(true, today, 1), DailyCap(3).tryAcquire(today, "", 0))
        assertEquals(DailyCap.Result(true, today, 1), DailyCap(3).tryAcquire(today, today, 0))
    }

    @Test fun `requests count up to the max, then are refused with the count unchanged`() {
        val cap = DailyCap(3)
        var day = ""
        var count = 0
        for (n in 1..3) {
            val r = cap.tryAcquire(today, day, count)
            assertEquals(DailyCap.Result(true, today, n), r)
            day = r.day
            count = r.count
        }
        assertEquals(DailyCap.Result(false, today, 3), cap.tryAcquire(today, day, count))
        assertEquals(DailyCap.Result(false, today, 3), cap.tryAcquire(today, today, 3))
    }

    @Test fun `a new day starts from zero`() {
        assertEquals(DailyCap.Result(true, today, 1), DailyCap(3).tryAcquire(today, "2026-11-20", 3))
        assertEquals(DailyCap.Result(true, today, 1), DailyCap(3).tryAcquire(today, "not a day", 99))
    }

    @Test fun `a negative or out-of-range saved count is handled`() {
        assertEquals(DailyCap.Result(true, today, 1), DailyCap(3).tryAcquire(today, today, -5))
        assertEquals(DailyCap.Result(true, today, 1), DailyCap(3).tryAcquire(today, today, Int.MIN_VALUE))
        // More than the max already: refused, and no overflow.
        assertEquals(DailyCap.Result(false, today, 50), DailyCap(3).tryAcquire(today, today, 50))
        assertEquals(DailyCap.Result(false, today, Int.MAX_VALUE), DailyCap(3).tryAcquire(today, today, Int.MAX_VALUE))
    }

    @Test fun `the default max is the timer requests per day`() {
        val cap = DailyCap()
        val max = DailyCap.TIMER_REQUESTS_PER_DAY
        assertEquals(DailyCap.Result(true, today, max), cap.tryAcquire(today, today, max - 1))
        assertEquals(DailyCap.Result(false, today, max), cap.tryAcquire(today, today, max))
    }
}
