package com.lperezmo.voicerelay

import com.lperezmo.voicerelay.ButtonRing.Dictation
import com.lperezmo.voicerelay.ButtonRing.Mode
import com.lperezmo.voicerelay.ButtonRing.Voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonRingTest {

    private fun rgb(color: Int) = color and 0xFFFFFF
    private fun alpha(color: Int) = color ushr 24

    @Test fun `idle shows only the track and does not animate`() {
        val look = ButtonRing().look(10_000)
        assertEquals(Mode.IDLE, look.mode)
        assertNull(look.arc)
        assertNull(look.track)
        assertNull(look.level)
        assertNull(look.flash)
        assertFalse(look.badge)
        assertFalse(look.animating)
        assertEquals(ButtonRing.WHITE, look.mic)
    }

    @Test fun `recording draws a red arc that counts the minute, with a red mic and level ring`() {
        val ring = ButtonRing()
        ring.dictation(Dictation.RECORDING, 0)
        val at15 = ring.look(15_000)
        assertEquals(Mode.RECORDING, at15.mode)
        assertEquals(ButtonRing.RED, at15.arc!!.color)
        assertEquals(-90f, at15.arc!!.startDeg)
        assertEquals(90f, at15.arc!!.sweepDeg, 0.01f)
        assertEquals(ButtonRing.RED, at15.mic)
        assertEquals(rgb(ButtonRing.RED), rgb(at15.level!!))
        assertTrue(at15.animating)
        // A new minute starts the arc again.
        assertEquals(180f, ring.look(90_000).arc!!.sweepDeg, 0.01f)
        // Never an empty arc, even at the very start.
        assertTrue(ring.look(0).arc!!.sweepDeg > 0f)
    }

    @Test fun `transcribing is a short white arc that spins`() {
        val ring = ButtonRing()
        ring.dictation(Dictation.WORKING, 1000)
        val a = ring.look(1000).arc!!
        val b = ring.look(1000 + ButtonRing.TRANSCRIBE_TURN_MS / 4).arc!!
        assertEquals(Mode.TRANSCRIBING, ring.mode(1000))
        assertEquals(ButtonRing.WORKING, a.color)
        assertEquals(ButtonRing.TRANSCRIBE_SWEEP, a.sweepDeg)
        assertEquals(90f, b.startDeg - a.startDeg, 0.5f)
    }

    @Test fun `a failed dictation freezes an amber arc and shows the badge`() {
        val ring = ButtonRing()
        ring.dictation(Dictation.RECORDING, 0)
        ring.dictation(Dictation.FAILED, 5000)
        val look = ring.look(60_000)
        assertEquals(Mode.FAILED, look.mode)
        assertEquals(ButtonRing.AMBER, look.arc!!.color)
        assertEquals(look.arc, ring.look(61_000).arc)
        assertTrue(look.badge)
        assertFalse(look.animating)
    }

    @Test fun `text landing flashes green, then fades away`() {
        val ring = ButtonRing()
        ring.dictation(Dictation.WORKING, 0)
        ring.dictation(Dictation.IDLE, 1000)
        ring.delivered(1000)
        val start = ring.look(1000)
        assertEquals(rgb(ButtonRing.GREEN), rgb(start.flash!!))
        assertEquals(255, alpha(start.flash!!))
        assertTrue(start.animating)
        assertTrue(alpha(ring.look(1350).flash!!) < 255)
        val after = ring.look(1000 + ButtonRing.FLASH_MS)
        assertNull(after.flash)
        assertFalse(after.animating)
    }

    @Test fun `hey claude and hey timer look different`() {
        val claude = ButtonRing().apply { wake(timer = false, now = 0) }.look(100)
        val timer = ButtonRing().apply { wake(timer = true, now = 0) }.look(100)
        assertEquals(Mode.CLAUDE, claude.mode)
        assertEquals(Mode.LISTENING, timer.mode)
        assertEquals(ButtonRing.CLAY, claude.mic)
        assertEquals(ButtonRing.BLUE, timer.mic)
        assertEquals(rgb(ButtonRing.CLAY), rgb(claude.flash!!))
        assertEquals(rgb(ButtonRing.BLUE), rgb(timer.flash!!))
        // Claude's whole ring glows; the timer phrase listens with a level ring instead.
        assertNotNull(claude.track)
        assertNull(claude.level)
        assertNull(timer.track)
        assertEquals(rgb(ButtonRing.BLUE), rgb(timer.level!!))
        assertNotEquals(claude, timer)
    }

    @Test fun `hey claude goes back to idle on its own`() {
        val ring = ButtonRing()
        ring.wake(timer = false, now = 0)
        assertEquals(Mode.CLAUDE, ring.mode(ButtonRing.CLAUDE_MS - 1))
        assertEquals(Mode.IDLE, ring.mode(ButtonRing.CLAUDE_MS))
    }

    @Test fun `the hey timer steps each have their look`() {
        val ring = ButtonRing()
        ring.wake(timer = true, now = 0)
        ring.voice(Voice.LISTENING, 50)
        assertEquals(Mode.LISTENING, ring.mode(1000))

        ring.voice(Voice.TRANSCRIBING, 5000)
        assertEquals(Mode.TRANSCRIBING, ring.mode(5100))
        assertEquals(ButtonRing.WORKING, ring.look(5100).arc!!.color)

        ring.voice(Voice.THINKING, 7000)
        val thinking = ring.look(7100)
        assertEquals(Mode.THINKING, thinking.mode)
        assertEquals(ButtonRing.CLAY, thinking.arc!!.color)
        assertEquals(ButtonRing.THINK_SWEEP, thinking.arc!!.sweepDeg)

        ring.voice(Voice.SPEAKING, 9000)
        val speaking = ring.look(9000)
        assertEquals(Mode.SPEAKING, speaking.mode)
        assertEquals(rgb(ButtonRing.CLAY), rgb(speaking.track!!))
        // The answer starts with a gold flash: the timer is set.
        assertEquals(rgb(ButtonRing.GOLD), rgb(speaking.flash!!))

        ring.voiceDone(12_000)
        assertEquals(Mode.IDLE, ring.mode(12_000))
    }

    @Test fun `listening again for an answer does not restart its timeout`() {
        val ring = ButtonRing()
        ring.voice(Voice.LISTENING, 0)
        ring.voice(Voice.LISTENING, 10_000)
        assertEquals(Mode.IDLE, ring.mode(ButtonRing.limitMs(Voice.LISTENING)))
    }

    @Test fun `a hey timer step that never ends goes back to idle`() {
        for (step in Voice.entries) {
            val ring = ButtonRing()
            ring.voice(step, 0)
            assertNotEquals(step.name, Mode.IDLE, ring.mode(ButtonRing.limitMs(step) - 1))
            assertEquals(step.name, Mode.IDLE, ring.mode(ButtonRing.limitMs(step)))
            assertFalse(ring.look(ButtonRing.limitMs(step) + ButtonRing.FLASH_MS).animating)
        }
    }

    @Test fun `each call to Claude starts the thinking limit again`() {
        val ring = ButtonRing()
        val limit = ButtonRing.limitMs(Voice.THINKING)
        ring.voice(Voice.THINKING, 0)
        ring.voice(Voice.THINKING, limit - 1000)
        ring.voice(Voice.THINKING, 2 * limit - 2000)
        assertEquals(Mode.THINKING, ring.mode(3 * limit - 3001))
        assertEquals(Mode.IDLE, ring.mode(3 * limit - 2000))
    }

    @Test fun `after an answer to a question the ring thinks again`() {
        val ring = ButtonRing()
        ring.wake(timer = true, now = 0)
        ring.voice(Voice.THINKING, 1000)
        ring.voice(Voice.SPEAKING, 2000)
        ring.voice(Voice.LISTENING, 3000)
        ring.voice(Voice.TRANSCRIBING, 4000)
        ring.voice(Voice.THINKING, 5000)
        assertEquals(Mode.THINKING, ring.mode(6000))
    }

    @Test fun `dictation in progress wins over the wake word, which wins over a failed one`() {
        val ring = ButtonRing()
        ring.dictation(Dictation.FAILED, 0)
        ring.wake(timer = true, now = 10)
        assertEquals(Mode.LISTENING, ring.mode(20))
        assertTrue(ring.look(20).badge)
        ring.dictation(Dictation.RECORDING, 30)
        assertEquals(Mode.RECORDING, ring.mode(40))
        ring.dictation(Dictation.FAILED, 50)
        assertEquals(Mode.LISTENING, ring.mode(60))
        ring.voiceDone(70)
        assertEquals(Mode.FAILED, ring.mode(80))
    }

    @Test fun `the next timer is a gold arc of what is left`() {
        val ring = ButtonRing()
        ring.timers(0.25f, done = false, now = 0)
        assertEquals(0.25f, ring.look(0).gold!!, 0f)
        assertFalse(ring.look(0).animating)
        ring.timers(null, done = false, now = 1)
        assertNull(ring.look(1).gold)
    }

    @Test fun `a finished timer flashes the whole ring gold, then stays gold`() {
        val ring = ButtonRing()
        ring.timers(0.5f, done = true, now = 0)
        val look = ring.look(100)
        assertEquals(Mode.TIMER_DONE, look.mode)
        assertEquals(rgb(ButtonRing.GOLD), rgb(look.track!!))
        assertNull(look.gold)
        assertTrue(look.animating)
        // At the half-beat it is faint.
        assertTrue(alpha(ring.look(ButtonRing.DONE_PULSE_MS / 2).track!!) < 80)
        val later = ring.look(ButtonRing.DONE_FLASHING_MS)
        assertEquals(ButtonRing.GOLD, later.track)
        assertFalse(later.animating)
        ring.timers(null, done = false, now = ButtonRing.DONE_FLASHING_MS + 1)
        assertEquals(Mode.IDLE, ring.mode(ButtonRing.DONE_FLASHING_MS + 1))
    }

    @Test fun `dims only when on, idle, untouched for a while and nothing is open`() {
        val ring = ButtonRing()
        ring.touched(1000)
        val due = 1000 + ButtonRing.DIM_AFTER_MS
        assertFalse(ring.dimmed(due - 1, enabled = true, popupsOpen = false))
        assertEquals(1L, ring.dimDueIn(due - 1))
        assertTrue(ring.dimmed(due, enabled = true, popupsOpen = false))
        assertFalse(ring.dimmed(due, enabled = false, popupsOpen = false))
        assertFalse(ring.dimmed(due, enabled = true, popupsOpen = true))
    }

    @Test fun `anything happening wakes the cluster`() {
        val ring = ButtonRing()
        val later = ButtonRing.DIM_AFTER_MS * 10
        assertTrue(ring.dimmed(later, enabled = true, popupsOpen = false))

        ring.dictation(Dictation.RECORDING, later)
        assertFalse(ring.dimmed(later + ButtonRing.DIM_AFTER_MS * 2, enabled = true, popupsOpen = false))
        ring.dictation(Dictation.FAILED, later + 1)
        // A failed dictation stays awake until it is retried or dismissed.
        assertFalse(ring.dimmed(later + ButtonRing.DIM_AFTER_MS * 2, enabled = true, popupsOpen = false))

        val other = ButtonRing()
        other.timers(null, done = true, now = 0)
        assertFalse(other.dimmed(ButtonRing.DIM_AFTER_MS * 5, enabled = true, popupsOpen = false))

        val voice = ButtonRing()
        voice.wake(timer = true, now = 0)
        assertFalse(voice.dimmed(ButtonRing.DIM_AFTER_MS, enabled = true, popupsOpen = false))
    }

    @Test fun `a finger on the button keeps it bright, held or dragged`() {
        val ring = ButtonRing()
        ring.pressed(true, 0)
        val long = ButtonRing.DIM_AFTER_MS * 10
        assertFalse(ring.dimmed(long, enabled = true, popupsOpen = false))
        assertNull(ring.dimDueIn(long))
        ring.pressed(false, long)
        assertEquals(ButtonRing.DIM_AFTER_MS, ring.dimDueIn(long))
        assertTrue(ring.dimmed(long + ButtonRing.DIM_AFTER_MS, enabled = true, popupsOpen = false))
    }

    @Test fun `nothing needs a later look while only an event can change it`() {
        val done = ButtonRing()
        done.timers(null, done = true, now = 0)
        assertNull(done.dimDueIn(ButtonRing.DIM_AFTER_MS * 5))

        val failed = ButtonRing()
        failed.dictation(Dictation.FAILED, 0)
        assertNull(failed.dimDueIn(ButtonRing.DIM_AFTER_MS * 5))

        val recording = ButtonRing()
        recording.dictation(Dictation.RECORDING, 0)
        assertNull(recording.dimDueIn(ButtonRing.DIM_AFTER_MS * 5))

        // A step that ends on its own is looked at again when it ends.
        val voice = ButtonRing()
        voice.voice(Voice.LISTENING, 0)
        assertEquals(ButtonRing.limitMs(Voice.LISTENING) - 1000, voice.dimDueIn(1000))
        // Then the flash and the last change count down to the dim.
        val flash = ButtonRing()
        flash.delivered(0)
        assertEquals(ButtonRing.DIM_AFTER_MS - 100, flash.dimDueIn(100))
    }

    @Test fun `with alpha scales only the alpha`() {
        assertEquals(0x80EF4444.toInt(), ButtonRing.withAlpha(0xFFEF4444.toInt(), 128 / 255f))
        assertEquals(0x00EF4444, ButtonRing.withAlpha(0xFFEF4444.toInt(), 0f))
        assertEquals(0xFFEF4444.toInt(), ButtonRing.withAlpha(0xFFEF4444.toInt(), 2f))
    }

    @Test fun `the pulse runs from full down to its low point and back`() {
        assertEquals(1f, ButtonRing.pulse(0, 1000, 0.35f), 1e-4f)
        assertEquals(0.35f, ButtonRing.pulse(500, 1000, 0.35f), 1e-4f)
        assertEquals(1f, ButtonRing.pulse(1000, 1000, 0.35f), 1e-4f)
    }

    @Test fun `the voice level stays between half and full`() {
        for (t in 0L..5000L step 37) {
            val v = ButtonRing.voiceLevel(t)
            assertTrue(v in 0.5f..1f)
        }
    }
}
