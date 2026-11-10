package com.lperezmo.voicerelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeVoicePressTest {

    private var now = 0L
    /** Posted runnables with the time they are due. */
    private val posted = mutableListOf<Pair<Long, Runnable>>()
    private var hidden = false
    private val hideCalls = mutableListOf<Boolean>()
    private var button: Boolean? = null
    private var presses = 0

    private val press = ClaudeVoicePress(
        pressVoiceButton = { presses++; button },
        postDelayed = { r, ms -> posted += (now + ms) to r },
        removeCallbacks = { r -> posted.removeAll { it.second === r } },
        now = { now },
        buttonsHidden = { hidden },
        setButtonsHidden = { hidden = it; hideCalls += it },
        logInfo = {},
        logWarn = {},
    )

    /** Moves the clock forward by [ms], running whatever comes due. */
    private fun advance(ms: Long) {
        val until = now + ms
        while (true) {
            val next = posted.filter { it.first <= until }.minByOrNull { it.first } ?: break
            posted.remove(next)
            now = next.first
            next.second.run()
        }
        now = until
    }

    @Test fun `presses the button once it shows, then brings the buttons back`() {
        press.start()
        assertTrue(hidden)
        advance(500)
        button = true
        advance(150)
        assertEquals(listOf(true), hideCalls)
        advance(800)
        assertFalse(hidden)
        assertTrue(posted.isEmpty())
    }

    @Test fun `gives up after the wait and brings the buttons back`() {
        press.start()
        advance(WakeWord.VOICE_MODE_WAIT_MS + 1000)
        assertFalse(hidden)
        assertTrue(posted.isEmpty())
    }

    @Test fun `buttons that were already hidden stay hidden`() {
        hidden = true
        button = true
        press.start()
        advance(2000)
        assertTrue(hidden)
        assertTrue(hideCalls.isEmpty())
    }

    @Test fun `cancel stops the polling`() {
        press.start()
        advance(500)
        val before = presses
        press.cancel()
        assertTrue(posted.isEmpty())
        advance(WakeWord.VOICE_MODE_WAIT_MS)
        assertEquals(before, presses)
        assertTrue(hideCalls == listOf(true))
    }

    @Test fun `cancel drops the pending restore of the buttons`() {
        button = true
        press.start()
        advance(200)
        assertEquals(1, presses)
        press.cancel()
        advance(2000)
        assertEquals(listOf(true), hideCalls)
    }
}
