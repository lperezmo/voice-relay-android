package com.lperezmo.voicerelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerRenameTest {

    // --- Icons ---

    @Test
    fun `with the setting off no icon shows, key or not`() {
        assertEquals(TimerRename.Icons(pencil = false, mic = false), TimerRename.icons(settingOn = false, openAiKeySet = true))
        assertEquals(TimerRename.Icons(pencil = false, mic = false), TimerRename.icons(settingOn = false, openAiKeySet = false))
    }

    @Test
    fun `with the setting on the pencil shows, and the microphone only with an OpenAI key`() {
        assertEquals(TimerRename.Icons(pencil = true, mic = true), TimerRename.icons(settingOn = true, openAiKeySet = true))
        assertEquals(TimerRename.Icons(pencil = true, mic = false), TimerRename.icons(settingOn = true, openAiKeySet = false))
    }

    // --- Refusals ---

    @Test
    fun `the microphone listens when everything is in place`() {
        assertNull(TimerRename.refusal(settingOn = true, micAllowed = true, openAiKeySet = true, dictating = false, micHeld = false))
    }

    @Test
    fun `dictating or another short recording makes the microphone busy`() {
        assertEquals(MicGuard.SAY_BUSY, TimerRename.refusal(true, true, true, dictating = true, micHeld = false))
        assertEquals(MicGuard.SAY_BUSY, TimerRename.refusal(true, true, true, dictating = false, micHeld = true))
        assertEquals("The microphone is busy.", MicGuard.SAY_BUSY)
    }

    @Test
    fun `a missing key, permission or setting is said before busy`() {
        assertEquals(TimerRename.SAY_NO_KEY, TimerRename.refusal(true, true, openAiKeySet = false, dictating = true, micHeld = true))
        assertEquals(TimerRename.SAY_NO_PERMISSION, TimerRename.refusal(true, micAllowed = false, openAiKeySet = false, dictating = false, micHeld = false))
        assertEquals(TimerRename.SAY_OFF, TimerRename.refusal(settingOn = false, micAllowed = false, openAiKeySet = false, dictating = false, micHeld = false))
    }

    @Test
    fun `the missing key line matches the dictate button's`() {
        assertEquals("Set your OpenAI API key in Voice Relay", TimerRename.SAY_NO_KEY)
    }

    // --- What was heard ---

    @Test
    fun `a spoken name is tidied`() {
        assertEquals("Pasta", TimerRename.nameFrom(ShortListen.Heard.Text("pasta.")))
        assertEquals("Eggs for Ana", TimerRename.nameFrom(ShortListen.Heard.Text("  \"eggs for Ana!\" ")))
    }

    @Test
    fun `only punctuation is no name`() {
        assertNull(TimerRename.nameFrom(ShortListen.Heard.Text("...")))
        assertEquals(TimerRename.SAY_NO_NAME, TimerRename.lineFor(ShortListen.Heard.Text("...")))
    }

    @Test
    fun `a long spoken name is cut to the label limit`() {
        val name = TimerRename.nameFrom(ShortListen.Heard.Text("a".repeat(60)))!!
        assertEquals(Timers.MAX_LABEL, name.length)
    }

    @Test
    fun `nothing heard gives no name and says so`() {
        assertNull(TimerRename.nameFrom(ShortListen.Heard.Nothing))
        assertEquals("I didn't catch a name.", TimerRename.lineFor(ShortListen.Heard.Nothing))
    }

    @Test
    fun `each failure has its own short line`() {
        assertEquals(MicGuard.SAY_BUSY, TimerRename.lineFor(ShortListen.Heard.MicBusy))
        assertEquals(TimerRename.SAY_NO_NETWORK, TimerRename.lineFor(ShortListen.Heard.NoNetwork))
        assertEquals(TimerRename.SAY_KEY_REJECTED, TimerRename.lineFor(ShortListen.Heard.KeyRejected))
        assertEquals(TimerRename.SAY_FAILED, TimerRename.lineFor(ShortListen.Heard.Failed(500)))
        for (heard in listOf(ShortListen.Heard.MicBusy, ShortListen.Heard.NoNetwork, ShortListen.Heard.KeyRejected, ShortListen.Heard.Failed(500))) {
            assertNull(TimerRename.nameFrom(heard))
        }
    }

    @Test
    fun `failure lines never mention Claude or the timer phrase`() {
        val lines = listOf(
            TimerRename.SAY_NO_NETWORK, TimerRename.SAY_KEY_REJECTED, TimerRename.SAY_FAILED,
            TimerRename.SAY_NO_NAME, TimerRename.SAY_CANCELLED, TimerRename.SAY_GONE,
        )
        for (line in lines) {
            assertFalse(line, line.contains("Claude") || line.contains("hey timer") || line.contains("Anthropic"))
            assertTrue(line, line.length <= 70)
        }
    }

    @Test
    fun `a rename reads back the new name`() {
        assertEquals("Renamed to Pasta", TimerRename.renamed("Pasta"))
    }
}
