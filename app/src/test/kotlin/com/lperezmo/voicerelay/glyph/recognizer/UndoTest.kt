package com.lperezmo.voicerelay.glyph.recognizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoTest {
    private fun s(n: Int) = listOf(Pt(n.toFloat(), 0f, 0), Pt(n + 0.5f, 1f, 0))
    private fun ids(x: Samples) = x.all.map { it.first().x.toInt() }
    private fun keys(ranked: List<Match>, matched: String) = Undo.alternatives(ranked, matched).map { it.key }

    @Test
    fun alternativesLeaveOutTheMatchedShape() {
        val ranked = listOf(Match("heart", 0.82f), Match("circle", 0.61f), Match("v", 0.40f))
        assertEquals(listOf("circle", "v"), keys(ranked, "heart"))
        assertFalse("heart" in keys(ranked, "heart"))
    }

    @Test
    fun alternativesHonourTheFloorBestFirstAndAtMostThree() {
        // exactly at the floor counts, just under does not
        assertEquals(listOf("v"), keys(listOf(Match("heart", 0.9f), Match("v", Undo.FLOOR), Match("z", 0.349f)), "heart"))
        assertTrue(keys(listOf(Match("heart", 0.9f), Match("z", 0.20f)), "heart").isEmpty())
        val ranked = listOf(Match("heart", 0.9f), Match("a", 0.4f), Match("b", 0.7f), Match("c", 0.5f), Match("d", 0.6f))
        assertEquals(listOf("b", "d", "c"), keys(ranked, "heart"))
    }

    @Test
    fun theSilentlyLearnedSampleIsRemovedBeforeRelearning() {
        val drawing = s(9)
        // "Learn from matches" put the drawing into heart; it was meant to be a circle
        val heart = Samples.of(listOf(s(1), s(2))).addLearned(s(3))!!.addLearned(drawing)!!
        val circle = Samples.of(listOf(s(5)))
        val changed = Undo.apply(mapOf("heart" to heart, "circle" to circle), "heart", drawing, "circle", drawing)
        assertEquals(listOf(1, 2, 3), ids(changed.getValue("heart")))
        assertEquals(2, changed.getValue("heart").originals)
        assertEquals(listOf(5, 9), ids(changed.getValue("circle")))
        assertTrue(changed.getValue("circle").isLearned(1))
    }

    @Test
    fun removalMatchesTheRoundedCopyAndNeverTouchesOriginals() {
        val drawing = listOf(Pt(10.04f, 3.01f, 0), Pt(11.96f, 4f, 0))
        val stored = listOf(Pt(10.0f, 3.0f, 0), Pt(12.0f, 4f, 0)) // as the shapes file rounds it
        val heart = Samples.of(listOf(s(1), stored)).addLearned(stored)!!
        val after = Undo.apply(mapOf("heart" to heart), "heart", drawing, null, drawing).getValue("heart")
        assertEquals(2, after.all.size)
        assertEquals(2, after.originals)
    }

    @Test
    fun justUndoWithNothingLearnedChangesNothing() {
        val heart = Samples.of(listOf(s(1)))
        assertTrue(Undo.apply(mapOf("heart" to heart), "heart", null, null, s(9)).isEmpty())
        // the learned sample is already gone (edited away): still nothing to do
        assertTrue(Undo.apply(mapOf("heart" to heart), "heart", s(9), null, s(9)).isEmpty())
    }
}
