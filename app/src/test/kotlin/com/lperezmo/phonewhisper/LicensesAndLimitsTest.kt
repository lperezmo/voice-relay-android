package com.lperezmo.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LicensesAndLimitsTest {

    @Test fun `license chunks rejoin to the original text`() {
        val text = (1..400).joinToString("\n\n") { "Paragraph $it.\nSecond line of paragraph $it." }
        val chunks = LicensesActivity.chunk(text, target = 300)
        assertTrue(chunks.size > 1)
        assertEquals(text, chunks.joinToString("\n"))
    }

    @Test fun `a paragraph longer than the target stays whole`() {
        val long = "x".repeat(5000)
        val chunks = LicensesActivity.chunk("a\n\n$long\n\nb", target = 100)
        assertTrue(chunks.any { it.trimEnd() == long })
        assertEquals("a\n\n$long\n\nb", chunks.joinToString("\n"))
    }

    @Test fun `the rate limiter allows max calls per window, then frees up`() {
        val limiter = RateLimiter(max = 3, windowMs = 60_000)
        assertTrue(limiter.tryAcquire(0))
        assertTrue(limiter.tryAcquire(1_000))
        assertTrue(limiter.tryAcquire(2_000))
        assertFalse(limiter.tryAcquire(59_999))
        assertTrue(limiter.tryAcquire(60_000))
        assertFalse(limiter.tryAcquire(60_001))
        assertTrue(limiter.tryAcquire(61_000))
    }

    @Test fun `languages default to auto-detect`() {
        assertEquals(emptyList<String>(), AppSettings.splitList(AppSettings.DEFAULT_LANGUAGES))
    }
}
