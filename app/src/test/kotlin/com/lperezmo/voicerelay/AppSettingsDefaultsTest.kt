package com.lperezmo.voicerelay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsDefaultsTest {

    @Test fun `the built-in canvas is on by default`() {
        // the Glyph app is being retired: a fresh install gets Voice Relay's own canvas
        assertTrue(AppSettings.DEFAULT_GLYPH_BUILT_IN)
    }

    @Test fun `ask the page stays off by default`() {
        // it sends the circled screen to Anthropic, so it is only ever turned on by hand
        assertFalse(AppSettings.DEFAULT_ASK_PAGE)
    }

    @Test fun `dim when idle stays off by default`() {
        // the look is always on; only the fading is a choice
        assertFalse(AppSettings.DEFAULT_DIM_WHEN_IDLE)
    }

    @Test fun `rename timers stays off by default`() {
        // like the other extras, the pencil and microphone are only shown once turned on
        assertFalse(AppSettings.DEFAULT_TIMER_RENAME)
    }
}
