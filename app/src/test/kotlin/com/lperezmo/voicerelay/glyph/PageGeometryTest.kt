package com.lperezmo.voicerelay.glyph

import com.lperezmo.voicerelay.glyph.PageGeometry.Box
import com.lperezmo.voicerelay.glyph.PageGeometry.PixelRect
import com.lperezmo.voicerelay.glyph.recognizer.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageGeometryTest {
    private val eps = 1e-4f

    @Test
    fun boundsCoverEveryStrokeAndThePadding() {
        val strokes = listOf(
            listOf(Pt(10f, 20f, 0), Pt(30f, 25f, 0)),
            listOf(Pt(5f, 40f, 1), Pt(12f, 60f, 1)),
        )
        assertEquals(Box(5f, 20f, 30f, 60f), PageGeometry.bounds(strokes))
        assertEquals(Box(-3f, 12f, 38f, 68f), PageGeometry.bounds(strokes, pad = 8f))
    }

    @Test
    fun noPointsMeansNoBounds() {
        assertNull(PageGeometry.bounds(emptyList()))
        assertNull(PageGeometry.bounds(listOf(emptyList(), emptyList())))
    }

    @Test
    fun aSinglePointHasAPaddedBox() {
        val b = PageGeometry.bounds(listOf(listOf(Pt(50f, 50f, 0))), pad = 10f)!!
        assertEquals(20f, b.width, eps)
        assertEquals(20f, b.height, eps)
    }

    @Test
    fun scaleShrinksTheLongestEdgeOnly() {
        assertEquals(1f, PageGeometry.scaleToMaxEdge(800f, 600f, 1568), eps)
        assertEquals(1f, PageGeometry.scaleToMaxEdge(1568f, 10f, 1568), eps)
        assertEquals(0.5f, PageGeometry.scaleToMaxEdge(3136f, 1000f, 1568), eps)
        assertEquals(0.5f, PageGeometry.scaleToMaxEdge(1000f, 2048f, 1024), eps)
        assertEquals(1f, PageGeometry.scaleToMaxEdge(0f, 0f, 1024), eps)
    }

    @Test
    fun scaledSizeStaysWithinTheMaxEdge() {
        val s = PageGeometry.scaleToMaxEdge(3001f, 1500f, 1024)
        val (w, h) = PageGeometry.scaledSize(3001f, 1500f, s, 1024)
        assertEquals(1024, w)
        assertEquals(512, h)
        assertEquals(1 to 1, PageGeometry.scaledSize(0.2f, 0f, 1f, 1024))
    }

    @Test
    fun clampRoundsOutwardsAndCutsToTheBitmap() {
        assertEquals(PixelRect(10, 20, 31, 41), PageGeometry.clampInto(Box(10.4f, 20.9f, 30.2f, 40.1f), 100, 100))
        assertEquals(PixelRect(0, 0, 50, 100), PageGeometry.clampInto(Box(-20f, -5f, 50f, 300f), 100, 100))
    }

    @Test
    fun aBoxOutsideTheBitmapClampsToNothing() {
        assertNull(PageGeometry.clampInto(Box(120f, 10f, 200f, 50f), 100, 100))
        assertNull(PageGeometry.clampInto(Box(10f, -50f, 40f, -1f), 100, 100))
    }

    @Test
    fun aPhoneScreenshotFitsTheWholeScreenEdge() {
        val edge = PageImages.SCREEN_MAX_EDGE
        assertEquals(1568, edge)
        // 1080 x 2400, the usual tall phone
        val s = PageGeometry.scaleToMaxEdge(1080f, 2400f, edge)
        assertEquals(706 to 1568, PageGeometry.scaledSize(1080f, 2400f, s, edge))
        // 1440 x 3120: the long edge lands on the limit, never one past it
        val q = PageGeometry.scaleToMaxEdge(1440f, 3120f, edge)
        val (w, h) = PageGeometry.scaledSize(1440f, 3120f, q, edge)
        assertEquals(1568, h)
        assertEquals(724, w)
        // landscape, and a small screen that is not blown up
        val l = PageGeometry.scaleToMaxEdge(2400f, 1080f, edge)
        assertEquals(1568 to 706, PageGeometry.scaledSize(2400f, 1080f, l, edge))
        assertEquals(720 to 1280, PageGeometry.scaledSize(720f, 1280f, PageGeometry.scaleToMaxEdge(720f, 1280f, edge), edge))
    }

    @Test
    fun offsetMovesTheBox() {
        assertEquals(Box(15f, 120f, 25f, 130f), Box(5f, 20f, 15f, 30f).offset(10f, 100f))
    }
}
