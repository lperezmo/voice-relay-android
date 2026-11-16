package com.lperezmo.voicerelay.glyph.recognizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplesTest {
    /** A one-point drawing tagged with [n], so tests can tell samples apart. */
    private fun s(n: Int) = listOf(Pt(n.toFloat(), 0f, 0))
    private fun ids(x: Samples) = x.all.map { it.first().x.toInt() }

    @Test
    fun learnedSamplesAppendUntilTheCap() {
        var x = Samples.of(listOf(s(1), s(2), s(3)))
        for (n in 4..10) x = x.addLearned(s(n))!!
        assertEquals(Samples.MAX, x.all.size)
        assertEquals(3, x.originals)
        assertEquals((1..10).toList(), ids(x))
    }

    @Test
    fun theCapDropsTheOldestLearnedSample() {
        var x = Samples.of(listOf(s(1), s(2), s(3)))
        for (n in 4..10) x = x.addLearned(s(n))!!
        x = x.addLearned(s(11))!!
        assertEquals(Samples.MAX, x.all.size)
        assertEquals(listOf(1, 2, 3, 5, 6, 7, 8, 9, 10, 11), ids(x))
        x = x.addLearned(s(12))!!
        assertEquals(listOf(1, 2, 3, 6, 7, 8, 9, 10, 11, 12), ids(x))
    }

    @Test
    fun originalsAreNeverDropped() {
        var x = Samples.of(listOf(s(1), s(2), s(3)))
        for (n in 100..140) x = x.addLearned(s(n))!!
        assertEquals(Samples.MAX, x.all.size)
        assertEquals(listOf(1, 2, 3), ids(x).take(3))
        assertEquals(3, x.originals)

        // all ten originals: learning has nowhere to go and is refused
        val full = Samples.of((1..10).map { s(it) })
        assertNull(full.addLearned(s(99)))
        assertEquals((1..10).toList(), ids(full))
        assertNull(full.addOriginal(s(99)))
    }

    @Test
    fun aNewOriginalPushesOutTheOldestLearnedOne() {
        var x = Samples.of(listOf(s(1), s(2)))
        for (n in 3..10) x = x.addLearned(s(n))!!
        x = x.addOriginal(s(50))!!
        assertEquals(listOf(1, 2, 50, 4, 5, 6, 7, 8, 9, 10), ids(x))
        assertEquals(3, x.originals)
        assertTrue(x.isLearned(3))
    }

    @Test
    fun removingKeepsTheOriginalsCountRight() {
        var x = Samples.of(listOf(s(1), s(2), s(3)))
        x = x.addLearned(s(4))!!.addLearned(s(5))!!
        x = x.remove(1)
        assertEquals(listOf(1, 3, 4, 5), ids(x))
        assertEquals(2, x.originals)
        x = x.remove(3)
        assertEquals(listOf(1, 3, 4), ids(x))
        assertEquals(2, x.originals)
    }

    @Test
    fun aRedrawnLearnedSampleBecomesAnOriginal() {
        var x = Samples.of(listOf(s(1)))
        x = x.addLearned(s(2))!!.addLearned(s(3))!!
        x = x.replace(1, s(20))
        assertEquals(listOf(1, 20, 3), ids(x))
        assertEquals(2, x.originals)
        x = x.replace(0, s(10))
        assertEquals(listOf(10, 20, 3), ids(x))
        assertEquals(2, x.originals)
    }

    @Test
    fun olderFilesCountEverythingAsOriginal() {
        val x = Samples.of(listOf(s(1), s(2)))
        assertEquals(2, x.originals)
        assertEquals(1, Samples.of(listOf(s(1)), originals = 5).originals)
    }
}
