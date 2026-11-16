package com.lperezmo.voicerelay.glyph.recognizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MissTest {
    private val threshold = 0.75f
    private fun keys(ranked: List<Match>) = Miss.learnChoices(ranked, threshold).map { it.key }

    @Test
    fun missOffersShapesOnlyWhenScoreIsAtLeastNear() {
        assertEquals(listOf("heart"), keys(listOf(Match("heart", 0.71f), Match("z", 0.30f))))
        // exactly at the floor counts
        assertEquals(listOf("v"), keys(listOf(Match("v", Miss.NEAR))))
        // nothing came close: only "New shape" and "Dismiss"
        assertTrue(keys(listOf(Match("heart", 0.44f), Match("v", 0.20f))).isEmpty())
        assertTrue(keys(emptyList()).isEmpty())
        // a match is not a miss
        assertTrue(keys(listOf(Match("heart", 0.80f), Match("circle", 0.60f))).isEmpty())
    }

    @Test
    fun twoShapesAboveNearYieldTwoChoicesInScoreOrder() {
        val ranked = listOf(Match("heart", 0.69f), Match("z", 0.30f), Match("circle", 0.72f))
        assertEquals(listOf("circle", "heart"), keys(ranked))
    }

    @Test
    fun atMostThreeChoices() {
        val ranked = listOf(Match("a", 0.5f), Match("b", 0.6f), Match("c", 0.7f), Match("d", 0.55f))
        assertEquals(listOf("c", "b", "d"), keys(ranked))
    }
}
