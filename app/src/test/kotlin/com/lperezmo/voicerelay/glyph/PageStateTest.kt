package com.lperezmo.voicerelay.glyph

import com.lperezmo.voicerelay.glyph.recognizer.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class PageStateTest {
    private fun circle(cx: Float, cy: Float, r: Float, n: Int = 40): List<Pt> =
        (0..n).map { i ->
            val a = 2 * PI * i / n
            Pt(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), 0)
        }

    private val line = (0..20).map { Pt(it * 10f, 0f, 0) }

    private fun containsSame(list: List<List<Pt>>, stroke: List<Pt>) = list.any { it === stroke }

    @Test
    fun aNewPageIsEmpty() {
        val page = PageState()
        assertTrue(page.ink.isEmpty())
        assertTrue(page.loops.isEmpty())
        assertTrue(page.turns.isEmpty())
        assertTrue(page.litLoops.isEmpty())
    }

    @Test
    fun anOpenStrokeIsInkOnly() {
        val page = PageState()
        assertNull(page.addStroke(line, 1f))
        assertEquals(1, page.ink.size)
        assertTrue(page.loops.isEmpty())
    }

    @Test
    fun aBigLoopIsALensAndNotInk() {
        val page = PageState()
        val stroke = circle(300f, 300f, 150f)
        val loop = page.addStroke(stroke, 1f)
        assertNotNull(loop)
        assertTrue(loop!!.lit)
        assertSame(loop, page.loops.single())
        assertTrue(page.ink.isEmpty())
        assertEquals(listOf(loop), page.litLoops)
    }

    @Test
    fun aSmallLoopIsBothALoopAndInk() {
        val page = PageState()
        val stroke = circle(100f, 100f, 20f) // 40 px across, under 72 dp at density 1
        val loop = page.addStroke(stroke, 1f)
        assertNotNull(loop)
        assertFalse(loop!!.lit)
        assertEquals(1, page.loops.size)
        assertTrue(containsSame(page.ink, stroke))
        assertTrue(page.litLoops.isEmpty())
    }

    @Test
    fun aBigLoopAfterMaxLitComesOutAsInk() {
        val page = PageState()
        repeat(Lens.MAX_LIT) { page.addStroke(circle(200f + it * 400f, 200f, 150f), 1f) }
        assertEquals(Lens.MAX_LIT, page.litLoops.size)
        val extra = circle(200f, 700f, 150f)
        val loop = page.addStroke(extra, 1f)
        assertFalse(loop!!.lit)
        assertTrue(containsSame(page.ink, extra))
        assertEquals(Lens.MAX_LIT, page.litLoops.size)
        assertEquals(Lens.MAX_LIT + 1, page.loops.size)
    }

    @Test
    fun toggleAtKeepsInkInStep() {
        val page = PageState()
        val stroke = circle(300f, 300f, 150f)
        val loop = page.addStroke(stroke, 1f)!!
        // lit -> ink: the stroke joins the ink
        assertSame(loop, page.toggleAt(300f, 300f))
        assertFalse(loop.lit)
        assertTrue(containsSame(page.ink, stroke))
        assertEquals(1, page.ink.size)
        // ink -> lit: it leaves again
        assertSame(loop, page.toggleAt(300f, 300f))
        assertTrue(loop.lit)
        assertTrue(page.ink.isEmpty())
    }

    @Test
    fun toggleAtOutsideEveryLoopChangesNothing() {
        val page = PageState()
        page.addStroke(line, 1f)
        page.addStroke(circle(300f, 300f, 150f), 1f)
        assertNull(page.toggleAt(900f, 900f))
        assertEquals(1, page.ink.size)
        assertEquals(1, page.litLoops.size)
    }

    @Test
    fun aRefusedToggleLeavesTheStrokeInInkOnce() {
        val page = PageState()
        repeat(Lens.MAX_LIT) { page.addStroke(circle(200f + it * 400f, 200f, 150f), 1f) }
        val extra = circle(200f, 700f, 150f)
        page.addStroke(extra, 1f)
        val hit = page.toggleAt(200f, 700f)
        assertNotNull(hit)
        assertFalse(hit!!.lit)
        assertEquals(1, page.ink.count { it === extra })
    }

    @Test
    fun aSmallLoopCanBeTappedIntoALens() {
        val page = PageState()
        val stroke = circle(100f, 100f, 20f)
        page.addStroke(stroke, 1f)
        val loop = page.toggleAt(100f, 100f)!!
        assertTrue(loop.lit)
        assertFalse(containsSame(page.ink, stroke))
    }

    @Test
    fun clearQuestionKeepsTheTurns() {
        val page = PageState()
        page.addStroke(line, 1f)
        page.addStroke(circle(300f, 300f, 150f), 1f)
        page.addStroke(circle(800f, 800f, 20f), 1f)
        page.turns.add(Turn("what is this", "a cat"))
        page.clearQuestion()
        assertTrue(page.ink.isEmpty())
        assertTrue(page.loops.isEmpty())
        assertEquals(1, page.turns.size)
        assertEquals("a cat", page.turns[0].answer)
    }

    @Test
    fun clearQuestionTurnsTheWholeScreenOff() {
        val page = PageState()
        assertFalse(page.wholeScreen)
        page.addStroke(circle(300f, 300f, 150f), 1f)
        page.wholeScreen = true
        page.clearQuestion()
        assertFalse(page.wholeScreen)
    }
}
