package com.lperezmo.voicerelay.glyph

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.lperezmo.voicerelay.glyph.recognizer.Pt
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * The images Ask the Page sends ([PagePlan] picks which): first the handwriting as light gold
 * strokes on black, then one picture per lit lens holding only the screenshot inside that
 * loop, black everywhere else, or, when the whole-screen button is on, one picture of the
 * whole screenshot instead of the lenses. Nothing outside a lit loop is ever drawn into an
 * image unless the whole-screen button was tapped for that question.
 */
object PageImages {
    /** Longest edge of the ink image. */
    const val INK_MAX_EDGE = 1568
    /** Longest edge of each lens image. */
    const val LENS_MAX_EDGE = 1024
    /** Longest edge of the whole-screen image. */
    const val SCREEN_MAX_EDGE = 1568
    /**
     * A whole-screen PNG bigger than this many bytes (a screen full of photos) goes as a
     * JPEG at [SCREEN_JPEG_QUALITY] instead, well inside the API's 5 MB per image once
     * base64 adds its third.
     */
    const val SCREEN_PNG_MAX_BYTES = 3_000_000
    const val SCREEN_JPEG_QUALITY = 90
    /** Space around the ink, in dp. */
    private const val INK_PAD_DP = 16f
    /** Width of the ink line in dp, the same as the canvas line. */
    private const val LINE_DP = 3.2f
    /** Thinnest the ink line may get in the image after shrinking, in pixels. */
    private const val MIN_LINE_PX = 3f

    /**
     * The ink [strokes] (view coordinates) drawn as round-capped light gold lines on black,
     * cropped to the ink plus some padding. Null when there is no ink.
     */
    fun ink(strokes: List<List<Pt>>, density: Float): ByteArray? {
        val lineWidth = LINE_DP * density
        val box = PageGeometry.bounds(strokes, INK_PAD_DP * density + lineWidth) ?: return null
        val scale = PageGeometry.scaleToMaxEdge(box.width, box.height, INK_MAX_EDGE)
        val (w, h) = PageGeometry.scaledSize(box.width, box.height, scale, INK_MAX_EDGE)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(out)
            canvas.drawColor(Color.BLACK)
            canvas.scale(scale, scale)
            canvas.translate(-box.left, -box.top)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = max(lineWidth, MIN_LINE_PX / scale)
                color = hsl(44f, 100f, 78f)
            }
            for (s in strokes) {
                if (s.isEmpty()) continue
                if (s.size == 1) {
                    canvas.drawPoint(s[0].x, s[0].y, paint)
                } else {
                    canvas.drawPath(pathOf(s), paint)
                }
            }
            return png(out)
        } finally {
            out.recycle()
        }
    }

    /**
     * The part of [shot] inside [loop]. The loop's points are in view coordinates and the
     * view sits at [originX], [originY] on screen, which is where the screenshot is measured
     * from. Everything outside the loop line is black; the picture is cropped to the loop's
     * bounds cut down to the screenshot.
     */
    fun lens(shot: Bitmap, loop: Loop, originX: Int, originY: Int): ByteArray {
        val local = PageGeometry.bounds(listOf(loop.stroke))
        val crop = local?.let {
            PageGeometry.clampInto(it.offset(originX.toFloat(), originY.toFloat()), shot.width, shot.height)
        }
        if (crop == null) {
            // The loop is off the screenshot: nothing of the screen to show.
            val empty = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            try {
                empty.eraseColor(Color.BLACK)
                return png(empty)
            } finally {
                empty.recycle()
            }
        }
        val cw = crop.width.toFloat()
        val ch = crop.height.toFloat()
        val scale = PageGeometry.scaleToMaxEdge(cw, ch, LENS_MAX_EDGE)
        val (w, h) = PageGeometry.scaledSize(cw, ch, scale, LENS_MAX_EDGE)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(out)
            canvas.drawColor(Color.BLACK)
            canvas.scale(scale, scale)
            canvas.translate(-crop.left.toFloat(), -crop.top.toFloat())
            val path = pathOf(loop.stroke).apply {
                offset(originX.toFloat(), originY.toFloat())
                close()
            }
            canvas.clipPath(path)
            canvas.drawBitmap(shot, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            return png(out)
        } finally {
            out.recycle()
        }
    }

    /**
     * The whole of [shot], shrunk so its longest edge is at most [SCREEN_MAX_EDGE]: a PNG, or a
     * JPEG when the PNG would be over [SCREEN_PNG_MAX_BYTES]. The request names each image's
     * type from its bytes ([com.lperezmo.voicerelay.AnthropicClient.mediaTypeOf]).
     */
    fun screen(shot: Bitmap): ByteArray {
        val sw = shot.width.toFloat()
        val sh = shot.height.toFloat()
        val scale = PageGeometry.scaleToMaxEdge(sw, sh, SCREEN_MAX_EDGE)
        val (w, h) = PageGeometry.scaledSize(sw, sh, scale, SCREEN_MAX_EDGE)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(out)
            canvas.drawColor(Color.BLACK)
            canvas.scale(w / sw, h / sh)
            canvas.drawBitmap(shot, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            val png = png(out)
            if (png.size <= SCREEN_PNG_MAX_BYTES) return png
            val jpeg = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, SCREEN_JPEG_QUALITY, jpeg)
            return jpeg.toByteArray()
        } finally {
            out.recycle()
        }
    }

    /**
     * Every image for one question, in the order they are sent: the ink first (when [plan]
     * has it and there is any), then either the first [PagePlan.lenses] lit loops in
     * [PageState.loops] order or the whole screen. Nothing of the screen when [shot] is null.
     */
    fun build(state: PageState, plan: PagePlan, shot: Bitmap?, density: Float, originX: Int, originY: Int): List<ByteArray> {
        val images = ArrayList<ByteArray>()
        if (plan.ink) ink(state.ink, density)?.let { images.add(it) }
        if (shot != null) {
            if (plan.wholeScreen) {
                images.add(screen(shot))
            } else {
                for (loop in state.loops.filter { it.lit }.take(plan.lenses)) images.add(lens(shot, loop, originX, originY))
            }
        }
        return images
    }

    private fun pathOf(points: List<Pt>): Path {
        val path = Path()
        points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
        return path
    }

    private fun png(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}
