package com.lperezmo.voicerelay.glyph.recognizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartersTest {
    private val threshold = PDollar.DEFAULT_THRESHOLD
    private val library = Starters.library().flatMap { (name, samples) -> samples.map { PDollar.template(name, it) } }

    @Test
    fun theQuestionMarkIsAHookAndADotUnderIt() {
        for (q in Starters.library().getValue(Starters.QUESTION)) {
            val hook = q.filter { it.stroke == 0 }
            val dot = q.filter { it.stroke == 1 }
            assertTrue(hook.size > 10)
            assertTrue(dot.isNotEmpty())
            assertTrue("the dot sits below the hook", dot.minOf { it.y } > hook.maxOf { it.y })
            // the hook ends near the middle, under the arc
            assertEquals(150f, hook.last().x, 10f)
        }
    }

    @Test
    fun aQuestionMarkWithoutItsDotStillReadsAsOne() {
        val hookOnly = Starters.question().filter { it.stroke == 0 }
        val best = PDollar.recognize(hookOnly, library)!!
        assertEquals(Starters.QUESTION, best.key)
        assertTrue("score ${best.score}", best.score >= threshold)
    }

    @Test
    fun aStraightDownLineIsNotAQuestionMark() {
        val line = Starters.polyline(listOf(150f to 20f, 150f to 280f))
        val ranked = PDollar.rank(line, library)
        val q = ranked.first { it.key == Starters.QUESTION }
        assertTrue("line scored ${q.score} as ?", q.score < threshold)
        assertNotEquals(Starters.QUESTION, ranked.first().key)
    }
}
