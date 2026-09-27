package com.lperezmo.voicerelay

import com.lperezmo.voicerelay.WakeWordController.Action
import com.lperezmo.voicerelay.WakeWordController.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WakeWordTest {

    // --- Keywords file and tuning ---

    /** Gradle runs unit tests from the module directory. */
    private val assets = File("src/main/assets/wake")

    @Test fun `the shipped keywords cover claude and the cloud accent`() {
        val keywords = WakeWord.parseKeywords(File(assets, "keywords.txt").readText())
        val byLabel = keywords.associate { it.label to it.phones.joinToString(" ") }
        assertEquals("HH EY1 K L AO1 D", byLabel["HEY_CLAUDE"])
        assertEquals("HH EY1 K L AW1 D", byLabel["HEY_CLOUD"])
        assertEquals(keywords.size, byLabel.size)
    }

    @Test fun `every keyword phone is a model token`() {
        val tokensFile = File(assets, "model/tokens.txt")
        // The model is fetched, not committed; CI and local builds run scripts/fetch_sherpa.sh first.
        if (!tokensFile.exists()) return
        val tokens = tokensFile.readLines().map { it.substringBefore(' ') }.toSet()
        for (k in WakeWord.parseKeywords(File(assets, "keywords.txt").readText())) {
            for (phone in k.phones) assertTrue("${k.label}: $phone is not a model token", phone in tokens)
        }
    }

    @Test fun `parsing skips comments and drops per-keyword boosts`() {
        val parsed = WakeWord.parseKeywords("# comment\n\nHH EY1 K L AO1 D :2.0 #0.3 @HEY_CLAUDE\n")
        assertEquals(listOf(WakeWord.Keyword(listOf("HH", "EY1", "K", "L", "AO1", "D"), "HEY_CLAUDE")), parsed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a keyword without a label is rejected`() {
        WakeWord.parseKeywords("HH EY1 K L AO1 D\n")
    }

    @Test fun `sensitivity maps to a threshold, loosest at the top`() {
        assertEquals(0.40f, WakeWord.threshold(1), 1e-6f)
        assertEquals(0.20f, WakeWord.threshold(WakeWord.DEFAULT_SENSITIVITY), 1e-6f)
        assertEquals(0.10f, WakeWord.threshold(7), 1e-6f)
        assertEquals(0.10f, WakeWord.threshold(99), 1e-6f)
        assertEquals(0.40f, WakeWord.threshold(-3), 1e-6f)
    }

    @Test fun `labels read as speech`() {
        assertEquals("hey cloud", WakeWord.spoken("HEY_CLOUD"))
    }

    // --- Controller ---

    private val c = WakeWordController(cooldownMs = 4_000, releaseDelayMs = 1_000, assistantTimeoutMs = 10_000, micRetryMs = 5_000)

    private fun List<Action>.withoutNotes() = filterNot { it is Action.Note }

    @Test fun `start listens when the mic is free and waits when it is not`() {
        assertEquals(listOf(Action.StartMic), c.start(0, micBusy = false))
        assertEquals(State.LISTENING, c.state)
        assertTrue(c.start(0, micBusy = false).isEmpty())

        val busy = WakeWordController()
        assertTrue(busy.start(0, micBusy = true).withoutNotes().isEmpty())
        assertEquals(State.BUSY, busy.state)
        busy.update(100, micBusy = false, musicActive = false)
        assertEquals(listOf(Action.StartMic), busy.update(1_100, micBusy = false, musicActive = false))
    }

    @Test fun `a keyword frees the mic and opens the assistant, once`() {
        c.start(0, false)
        assertEquals(listOf(Action.StopMic, Action.LaunchAssistant), c.onKeyword(1_000, false, true))
        assertEquals(State.WAITING, c.state)
        assertTrue(c.onKeyword(1_100, false, true).isEmpty())
        assertTrue(c.needsTicks)
    }

    @Test fun `listening resumes only after the assistant lets go, the delay, and the cooldown`() {
        c.start(0, false)
        c.onKeyword(10_000, musicActive = false, resumeMediaEnabled = true)
        c.update(10_500, micBusy = true, musicActive = false)
        assertEquals(State.BUSY, c.state)
        // Claude's turns: recording stops between them, but the call-style audio mode keeps it busy.
        c.update(20_000, micBusy = true, musicActive = false)
        assertTrue(c.update(30_000, micBusy = false, musicActive = false).isEmpty())
        assertTrue(c.update(30_900, micBusy = false, musicActive = false).isEmpty())
        // Busy again before the delay ran out: the delay starts over.
        c.update(30_950, micBusy = true, musicActive = false)
        c.update(31_000, micBusy = false, musicActive = false)
        assertTrue(c.update(31_900, micBusy = false, musicActive = false).isEmpty())
        assertEquals(listOf(Action.StartMic), c.update(32_000, micBusy = false, musicActive = false))
        assertEquals(State.LISTENING, c.state)
        assertFalse(c.needsTicks)
    }

    @Test fun `the cooldown holds even when the assistant is quick`() {
        c.start(0, false)
        c.onKeyword(10_000, false, true)
        c.update(10_200, micBusy = true, musicActive = false)
        c.update(10_400, micBusy = false, musicActive = false)
        assertTrue(c.update(12_000, micBusy = false, musicActive = false).withoutNotes().isEmpty())
        assertTrue(c.update(13_900, micBusy = false, musicActive = false).withoutNotes().isEmpty())
        assertEquals(listOf(Action.StartMic), c.update(14_000, micBusy = false, musicActive = false))
    }

    @Test fun `if the assistant never takes the mic, listening comes back after the timeout`() {
        c.start(0, false)
        c.onKeyword(10_000, false, true)
        assertTrue(c.update(19_000, micBusy = false, musicActive = false).isEmpty())
        assertEquals(listOf(Action.StartMic), c.update(20_000, micBusy = false, musicActive = false).withoutNotes())
    }

    @Test fun `music playing at the trigger is resumed when the assistant is done`() {
        c.start(0, false)
        c.onKeyword(10_000, musicActive = true, resumeMediaEnabled = true)
        c.update(10_300, micBusy = true, musicActive = false)
        c.update(20_000, micBusy = false, musicActive = false)
        assertEquals(listOf(Action.StartMic, Action.ResumeMedia), c.update(21_000, micBusy = false, musicActive = false))
    }

    @Test fun `music is not pressed again if it is already playing, or if the setting is off`() {
        c.start(0, false)
        c.onKeyword(10_000, musicActive = true, resumeMediaEnabled = true)
        c.update(10_300, micBusy = true, musicActive = false)
        c.update(20_000, micBusy = false, musicActive = true)
        assertEquals(listOf(Action.StartMic), c.update(21_000, micBusy = false, musicActive = true).withoutNotes())

        val off = WakeWordController()
        off.start(0, false)
        off.onKeyword(10_000, musicActive = true, resumeMediaEnabled = false)
        off.update(10_300, micBusy = true, musicActive = false)
        off.update(20_000, micBusy = false, musicActive = false)
        assertEquals(listOf(Action.StartMic), off.update(21_000, micBusy = false, musicActive = false))
    }

    @Test fun `no music, no media key`() {
        c.start(0, false)
        c.onKeyword(10_000, musicActive = false, resumeMediaEnabled = true)
        c.update(10_300, micBusy = true, musicActive = false)
        c.update(20_000, micBusy = false, musicActive = false)
        assertEquals(listOf(Action.StartMic), c.update(21_000, micBusy = false, musicActive = false))
    }

    @Test fun `someone else recording pauses listening without a media key afterwards`() {
        c.start(0, false)
        assertEquals(listOf(Action.StopMic), c.update(5_000, micBusy = true, musicActive = true).withoutNotes())
        assertEquals(State.BUSY, c.state)
        c.update(9_000, micBusy = false, musicActive = false)
        assertEquals(listOf(Action.StartMic), c.update(10_000, micBusy = false, musicActive = false))
    }

    @Test fun `a failed mic start backs off before retrying`() {
        c.start(0, false)
        c.onMicFailed(1_000)
        assertEquals(State.BUSY, c.state)
        c.update(1_100, micBusy = false, musicActive = false)
        assertTrue(c.update(5_900, micBusy = false, musicActive = false).isEmpty())
        assertEquals(listOf(Action.StartMic), c.update(6_000, micBusy = false, musicActive = false))
    }

    @Test fun `stop turns the mic off only if it was on`() {
        c.start(0, false)
        assertEquals(listOf(Action.StopMic), c.stop())
        assertEquals(State.OFF, c.state)
        assertTrue(c.update(1_000, micBusy = false, musicActive = false).isEmpty())
        c.start(2_000, false)
        c.onKeyword(3_000, false, true)
        assertTrue(c.stop().isEmpty())
    }
}
