package com.lperezmo.voicerelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class MicGuardTest {

    @Test
    fun `a free microphone goes to the first one who asks`() {
        val lock = MicLock()
        assertNull(lock.holder())
        assertTrue(lock.tryAcquire("hey timer"))
        assertEquals("hey timer", lock.holder())
    }

    @Test
    fun `a held microphone is refused to someone else`() {
        val lock = MicLock()
        lock.tryAcquire("hey timer")
        assertFalse(lock.tryAcquire("timer rename"))
        assertEquals("hey timer", lock.holder())
    }

    @Test
    fun `a held microphone is refused to its own holder too`() {
        val lock = MicLock()
        lock.tryAcquire("timer rename")
        assertFalse(lock.tryAcquire("timer rename"))
    }

    @Test
    fun `only the holder can let go`() {
        val lock = MicLock()
        lock.tryAcquire("hey timer")
        lock.release("timer rename")
        assertEquals("hey timer", lock.holder())
        lock.release("hey timer")
        assertNull(lock.holder())
    }

    @Test
    fun `letting go of a free microphone does nothing`() {
        val lock = MicLock()
        lock.release("hey timer")
        assertNull(lock.holder())
        assertTrue(lock.tryAcquire("hey timer"))
    }

    @Test
    fun `once let go someone else can take it`() {
        val lock = MicLock()
        lock.tryAcquire("hey timer")
        lock.release("hey timer")
        assertTrue(lock.tryAcquire("timer rename"))
        assertEquals("timer rename", lock.holder())
    }

    @Test
    fun `only one of many threads asking at once gets it`() {
        val lock = MicLock()
        val go = CountDownLatch(1)
        val winners = AtomicInteger()
        val threads = (1..16).map { i ->
            Thread {
                go.await()
                if (lock.tryAcquire("owner $i")) winners.incrementAndGet()
            }.also { it.start() }
        }
        go.countDown()
        threads.forEach { it.join() }
        assertEquals(1, winners.get())
    }

    @Test
    fun `the app guard uses different names for hey timer and rename`() {
        assertFalse(MicGuard.HEY_TIMER == MicGuard.TIMER_RENAME)
        val before = MicGuard.holder()
        if (before == null) {
            assertTrue(MicGuard.tryAcquire(MicGuard.TIMER_RENAME))
            assertFalse(MicGuard.tryAcquire(MicGuard.HEY_TIMER))
            MicGuard.release(MicGuard.TIMER_RENAME)
            assertNull(MicGuard.holder())
        }
    }
}
