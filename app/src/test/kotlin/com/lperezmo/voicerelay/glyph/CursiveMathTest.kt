package com.lperezmo.voicerelay.glyph

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CursiveMathTest {
    // every character is 10 px wide
    private val mono: (String) -> Float = { it.length * 10f }

    @Test
    fun wrapsWholeWordsToTheWidth() {
        val lines = CursiveMath.wrap("the quick brown fox jumps", 100f, mono)
        assertEquals(listOf("the quick", "brown fox", "jumps"), lines)
        for (l in lines) assertTrue(mono(l) <= 100f)
    }

    @Test
    fun keepsLineBreaksAndCollapsesSpaces() {
        val lines = CursiveMath.wrap("\n\none   two\n\nthree\n\n", 100f, mono)
        assertEquals(listOf("one two", "", "three"), lines)
    }

    @Test
    fun breaksAWordLongerThanALine() {
        val lines = CursiveMath.wrap("a abcdefghijklmnop b", 50f, mono)
        assertEquals(listOf("a", "abcde", "fghij", "klmno", "p b"), lines)
    }

    @Test
    fun emptyTextHasNoLines() {
        assertEquals(emptyList<String>(), CursiveMath.wrap("  \n ", 100f, mono))
    }

    @Test
    fun fitShrinksUntilTheTextIsShortEnough() {
        val text = "one two three four five six seven eight nine ten"
        val measure = { s: String, size: Float -> s.length * size / 2 }
        val lineHeight = { size: Float -> size * 1.2f }
        val roomy = CursiveMath.fit(text, 200f, 10_000f, 30f, 20f, 1f, measure, lineHeight)
        assertEquals(30f, roomy.size, 0f)
        val tight = CursiveMath.fit(text, 200f, 100f, 30f, 20f, 1f, measure, lineHeight)
        assertTrue(tight.size < 30f)
        assertTrue(tight.lines.size * lineHeight(tight.size) <= 100f)
        val impossible = CursiveMath.fit(text, 200f, 1f, 30f, 20f, 1f, measure, lineHeight)
        assertEquals(20f, impossible.size, 0f)
    }

    @Test
    fun durationIsAboutSevenSecondsForFortyWords() {
        val forty = (1..40).joinToString(" ") { "word$it" }
        assertEquals(40, CursiveMath.words(forty))
        val d = CursiveMath.duration(40)
        assertTrue(d in 6f..8f)
        assertEquals(CursiveMath.MIN_SECONDS, CursiveMath.duration(1), 0f)
        assertEquals(CursiveMath.MAX_SECONDS, CursiveMath.duration(10_000), 0f)
        assertEquals(0, CursiveMath.words("  "))
    }

    @Test
    fun charAtFindsTheCharacterUnderAPoint() {
        val edges = floatArrayOf(10f, 20f, 20f, 35f)
        assertEquals(0, CursiveMath.charAt(edges, -5f))
        assertEquals(0, CursiveMath.charAt(edges, 9.9f))
        assertEquals(1, CursiveMath.charAt(edges, 10f))
        assertEquals(3, CursiveMath.charAt(edges, 20f))
        assertEquals(3, CursiveMath.charAt(edges, 99f))
        assertEquals(0, CursiveMath.charAt(FloatArray(0), 5f))
    }

    @Test
    fun orderIsStableByKey() {
        assertArrayEquals(intArrayOf(1, 3, 0, 4, 2), CursiveMath.order(intArrayOf(2, 1, 5, 1, 2)))
    }

    @Test
    fun timelineMovesAtOneSpeedAndLiftsBetweenOutlines() {
        // lengths 100 and 300, 4.1 s with a 0.1 s lift: 100 px/s
        val tl = PenTimeline(floatArrayOf(100f, 300f), 4.1f, 0.1f)
        assertEquals(4.1f, tl.total, 1e-4f)
        assertEquals(1f, tl.endOf(0), 1e-4f)
        assertEquals(4.1f, tl.endOf(1), 1e-4f)
        val c = PenCursor()

        tl.locate(-1f, c)
        assertEquals(0, c.index); assertEquals(0f, c.distance, 0f)
        tl.locate(0.5f, c)
        assertEquals(0, c.index); assertEquals(50f, c.distance, 1e-3f)
        tl.locate(1.05f, c) // pen lifted
        assertEquals(1, c.index); assertEquals(0f, c.distance, 0f)
        tl.locate(2.1f, c)
        assertEquals(1, c.index); assertEquals(100f, c.distance, 1e-3f)
        tl.locate(5f, c)
        assertEquals(2, c.index)
    }

    @Test
    fun manyOutlinesShrinkTheLifts() {
        val tl = PenTimeline(FloatArray(1000) { 10f }, 6f, 0.1f)
        assertEquals(6f, tl.total, 1e-2f)
        // lifts are capped at a third of the time, so drawing still takes two thirds
        assertTrue(tl.endOf(0) > 0f)
    }

    @Test
    fun emptyTimelineIsDoneAtOnce() {
        val tl = PenTimeline(FloatArray(0), 7f, 0.03f)
        assertEquals(0f, tl.total, 0f)
        val c = PenCursor()
        tl.locate(0f, c)
        assertEquals(0, c.index)
    }
}
