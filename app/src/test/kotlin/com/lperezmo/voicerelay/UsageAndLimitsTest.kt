package com.lperezmo.voicerelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageAndLimitsTest {

    @Test
    fun `locked recordings are capped and counted until unlock`() {
        val limits = LockScreenLimits(maxSeconds = 120, maxRecordings = 5)
        repeat(5) { i ->
            val d = limits.onStart(locked = true, normalMaxSeconds = 600)
            assertEquals(LockScreenLimits.Decision.Allow(120, 4 - i), d)
        }
        assertEquals(LockScreenLimits.Decision.UnlockRequired, limits.onStart(locked = true, normalMaxSeconds = 600))
        limits.onUnlock()
        assertEquals(LockScreenLimits.Decision.Allow(120, 4), limits.onStart(locked = true, normalMaxSeconds = 600))
    }

    @Test
    fun `unlocked recordings keep the normal cap and do not count`() {
        val limits = LockScreenLimits()
        repeat(10) { assertEquals(LockScreenLimits.Decision.Allow(600, null), limits.onStart(false, 600)) }
        assertEquals(0, limits.used)
    }

    @Test
    fun `locking mid-recording allows the lock-screen cap from that point`() {
        assertEquals(130 + 120, LockScreenLimits().capWhenLocked(130.7))
    }

    @Test
    fun `transcription priced per minute including dated snapshots`() {
        assertEquals(0.0045, Pricing.transcription("gpt-transcribe", 60.0)!!, 1e-9)
        assertEquals(0.003, Pricing.transcription("gpt-4o-transcribe-2025-03-20", 30.0)!!, 1e-9)
        assertNull(Pricing.transcription("some-new-transcriber", 60.0))
    }

    @Test
    fun `text priced per million tokens`() {
        // 1000 in at $0.20/M + 500 out at $1.20/M
        assertEquals(0.0008, Pricing.text("gpt-5.6-luna", 1000, 500)!!, 1e-12)
        assertNull(Pricing.text("gpt-9", 1000, 500))
    }

    @Test
    fun `totals add up and flag unpriced calls`() {
        val t = UsageTotals()
            .plusTranscription(90.0, Pricing.transcription("gpt-transcribe", 90.0))
            .plusText(200, 100, Pricing.text("gpt-5.6-luna", 200, 100))
            .plusText(200, 100, null)
        assertEquals(1, t.dictations)
        assertEquals(2, t.textCalls)
        assertEquals(1, t.unpricedCalls)
        assertEquals(6750 + 160, t.costMicros)
        assertTrue(t.summary().startsWith("1 dictation, 1.5 min of audio, 2 cleanups and fixes, about < \$0.01"))
        assertEquals("Nothing yet", UsageTotals().summary())
    }

    @Test
    fun `responses usage is parsed alongside the text`() {
        val json = """{"output_text":"Hi.","usage":{"input_tokens":120,"output_tokens":7}}"""
        val r = OpenAiClient.parseResponsesText(json)
        assertEquals("Hi.", r.text)
        assertEquals(120, r.inputTokens)
        assertEquals(7, r.outputTokens)
    }
}
