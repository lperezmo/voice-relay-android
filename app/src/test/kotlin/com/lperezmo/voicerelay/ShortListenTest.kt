package com.lperezmo.voicerelay

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortListenTest {

    private fun heard(text: String?, error: String?, code: Int = 0) =
        ShortListen.heardFrom(OpenAiClient.Result(text, error, code))

    @Test
    fun `a transcript comes back trimmed`() {
        assertEquals(ShortListen.Heard.Text("Pasta"), heard("  Pasta \n", null, 200))
    }

    @Test
    fun `a blank transcript with no error means nothing was heard`() {
        assertEquals(ShortListen.Heard.Nothing, heard("   ", null, 200))
        assertEquals(ShortListen.Heard.Nothing, heard(null, null, 200))
    }

    @Test
    fun `no answer at all is no network`() {
        assertEquals(ShortListen.Heard.NoNetwork, heard(null, "timeout", 0))
    }

    @Test
    fun `a 401 is a rejected key`() {
        assertEquals(ShortListen.Heard.KeyRejected, heard(null, "bad key", 401))
    }

    @Test
    fun `any other error keeps its code`() {
        assertEquals(ShortListen.Heard.Failed(500), heard(null, "server", 500))
        assertEquals(ShortListen.Heard.Failed(429), heard(null, "slow down", 429))
    }

    // --- what hey timer says for each ---

    @Test
    fun `hey timer keeps its old lines for each failure`() {
        assertEquals(TimerAssistant.SAY_NO_TIME, TimerVoiceFlow.lineFor(ShortListen.Heard.Nothing))
        assertEquals("The microphone is busy, so I couldn't listen.", TimerVoiceFlow.lineFor(ShortListen.Heard.MicBusy))
        assertEquals(TimerAssistant.SAY_NO_NETWORK, TimerVoiceFlow.lineFor(ShortListen.Heard.NoNetwork))
        assertEquals(
            "The OpenAI key was not accepted. Check it in Voice Relay.",
            TimerVoiceFlow.lineFor(ShortListen.Heard.KeyRejected),
        )
        assertEquals(
            "I couldn't turn that into text, so nothing was changed.",
            TimerVoiceFlow.lineFor(ShortListen.Heard.Failed(500)),
        )
    }
}
