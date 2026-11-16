package com.lperezmo.voicerelay.glyph

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Phone Relay's golden glitter (GlitterView there, tuned in the Glitter lab design), cut
 * into single flecks so they can trail a finger and burst from a finished shape. The look
 * is the design's: hue 44 +/- 14, 24% four-point stars, two thirds of the dots diamonds,
 * a soft glow, a twinkle, and the "settle" fall that is quick at first and winds out.
 * Every fleck's looks come from one mulberry32 stream seeded 11, as in the design.
 */
class Fleck(
    val x0: Float,
    val y0: Float,
    val dx: Float,
    val dy: Float,
    val born: Float,
    val life: Float,
    val isStar: Boolean,
    val isDiamond: Boolean,
    val color: Int,
    val glow: Int,
    val swayPeriod: Float,
    val swayPhase: Float,
    val twinkle: Float,
    val twinklePhase: Float,
)

class Glitter(private val density: Float) {
    private companion object {
        const val SIZE_DP = 2f
        const val STAR_PCT = 24f
        const val STAR_DP = 8f
        const val HUE = 44f
        const val HUE_SPREAD = 14f
        const val LIGHTNESS = 62f
        const val LIGHT_SPREAD = 14f
        const val GLOW_DP = 2.5f
        const val GLOW_ALPHA = 0.3f
        const val FADE_START = 0.65f
        const val SWAY_DP = 4f
        const val SWAY_S = 0.9f
        const val TWINKLE_S = 0.62f
        const val SEED = 11
        const val MAX_FLECKS = 900
    }

    private val rnd = Mulberry32(SEED)
    private val flecks = ArrayList<Fleck>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val star = Path()

    val alive get() = flecks.isNotEmpty()

    fun random() = rnd.next()

    /**
     * One fleck at (x, y) that drifts by (dx, dy) along the settle curve over [life]
     * seconds, starting at time [now].
     */
    fun emit(x: Float, y: Float, dx: Float, dy: Float, now: Float, life: Float) {
        if (flecks.size >= MAX_FLECKS) flecks.removeAt(0)
        val isStar = rnd.next() * 100 < STAR_PCT
        val hue = HUE + (rnd.next() * 2 - 1) * HUE_SPREAD
        val light = min(97f, max(10f, LIGHTNESS + (rnd.next() * 2 - 1) * LIGHT_SPREAD))
        val isDiamond = rnd.next() < 0.66f
        val swayPeriod = SWAY_S * (0.8f + rnd.next() * 0.5f)
        val twinkle = TWINKLE_S * (0.7f + rnd.next() * 0.6f)
        flecks += Fleck(
            x0 = x, y0 = y, dx = dx, dy = dy, born = now, life = life,
            isStar = isStar, isDiamond = isDiamond,
            color = if (isStar) hsl(hue, 100f, 88f) else hsl(hue, 100f, light),
            glow = hsl(hue, 100f, 65f),
            swayPeriod = swayPeriod, swayPhase = rnd.next() * swayPeriod,
            twinkle = twinkle, twinklePhase = rnd.next() * twinkle,
        )
    }

    /** A trail fleck under a moving finger: a little scatter, then a short settling fall. */
    fun trail(x: Float, y: Float, now: Float) {
        val jitter = 7 * density
        val fall = (26 + rnd.next() * 46) * density
        emit(
            x + (rnd.next() * 2 - 1) * jitter, y + (rnd.next() * 2 - 1) * jitter,
            (rnd.next() * 2 - 1) * 6 * density, fall, now, 1.1f + rnd.next() * 0.8f,
        )
    }

    /** The match celebration: flecks thrown out from the drawing's points and settling down. */
    fun burst(points: List<Pair<Float, Float>>, cx: Float, cy: Float, now: Float, count: Int = 220) {
        if (points.isEmpty()) return
        repeat(count) { i ->
            val (px, py) = points[(i * 7919) % points.size]
            val angle = rnd.next() * 2 * PI.toFloat()
            val push = (30 + rnd.next() * 90) * density
            val ox = px - cx
            val oy = py - cy
            val len = max(1f, kotlin.math.sqrt(ox * ox + oy * oy))
            val dx = ox / len * push * 0.8f + cos(angle) * push * 0.5f
            val dy = oy / len * push * 0.8f + sin(angle) * push * 0.5f + (40 + rnd.next() * 80) * density
            emit(px, py, dx, dy, now + rnd.next() * 0.12f, 0.9f + rnd.next() * 0.7f)
        }
    }

    fun clear() = flecks.clear()

    fun draw(canvas: Canvas, now: Float, fade: Float = 1f) {
        val it = flecks.iterator()
        val sway = SWAY_DP * density
        while (it.hasNext()) {
            val f = it.next()
            val local = (now - f.born) / f.life
            if (local >= 1f) {
                it.remove()
                continue
            }
            if (local <= 0f) continue
            val appear = min(1f, local / 0.05f)
            val out = if (local <= FADE_START) 1f else 1f - (local - FADE_START) / (1 - FADE_START)
            val alpha = appear * out * fade
            if (alpha <= 0.01f) continue
            val k = SETTLE.at(local)
            val swing = cos(PI * ((now + f.swayPhase) / f.swayPeriod)).toFloat()
            val x = f.x0 + f.dx * k + sway * swing * local
            val y = f.y0 + f.dy * k
            val pulse = triangle((now + f.twinklePhase) / f.twinkle)
            if (f.isStar) drawStar(canvas, f, x, y, alpha, pulse) else drawDot(canvas, f, x, y, alpha, pulse)
        }
    }

    private fun drawDot(canvas: Canvas, f: Fleck, x: Float, y: Float, alpha: Float, pulse: Float) {
        val shine = if (pulse < 0.6f) lerp(0.25f, 1f, pulse / 0.6f) else lerp(1f, 0.6f, (pulse - 0.6f) / 0.4f)
        val r = SIZE_DP * density / 2
        glowPaint.color = f.glow
        glowPaint.alpha = (255 * GLOW_ALPHA * alpha * shine).toInt()
        canvas.drawCircle(x, y, r + GLOW_DP * density, glowPaint)
        paint.color = brighten(f.color, (shine - 0.25f) * 0.5f)
        paint.alpha = (255 * alpha * shine).toInt()
        if (f.isDiamond) {
            canvas.save()
            canvas.rotate(45f, x, y)
            canvas.drawRect(x - r, y - r, x + r, y + r, paint)
            canvas.restore()
        } else {
            canvas.drawCircle(x, y, r, paint)
        }
    }

    private fun drawStar(canvas: Canvas, f: Fleck, x: Float, y: Float, alpha: Float, pulse: Float) {
        val half = pulse < 0.5f
        val k = if (half) pulse / 0.5f else (pulse - 0.5f) / 0.5f
        val scale = if (half) lerp(0.2f, 1.15f, k) else lerp(1.15f, 0.4f, k)
        val shine = if (half) lerp(0.2f, 1f, k) else lerp(1f, 0.5f, k)
        val r = STAR_DP * density / 2 * scale
        canvas.save()
        canvas.rotate(45f * pulse, x, y)
        glowPaint.color = f.glow
        glowPaint.alpha = (255 * GLOW_ALPHA * alpha * shine).toInt()
        canvas.drawCircle(x, y, r * 0.6f + GLOW_DP * density, glowPaint)
        paint.color = f.color
        paint.alpha = (255 * alpha * shine).toInt()
        val w = r * 0.1f
        star.reset()
        star.moveTo(x, y - r)
        star.quadTo(x + w, y - w, x + r, y)
        star.quadTo(x + w, y + w, x, y + r)
        star.quadTo(x - w, y + w, x - r, y)
        star.quadTo(x - w, y - w, x, y - r)
        star.close()
        canvas.drawPath(star, paint)
        canvas.restore()
    }
}

// CSS's `alternate` direction: 0 -> 1 -> 0 over two periods, eased in and out.
private fun triangle(cycles: Float): Float {
    val p = cycles - floor(cycles / 2) * 2
    val linear = if (p < 1) p else 2 - p
    return (0.5 - 0.5 * cos(PI * linear)).toFloat()
}

private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * min(1f, max(0f, k))

private fun brighten(color: Int, k: Float): Int {
    val f = min(1f, max(0f, k))
    return Color.rgb(
        (Color.red(color) + (255 - Color.red(color)) * f).toInt(),
        (Color.green(color) + (255 - Color.green(color)) * f).toInt(),
        (Color.blue(color) + (255 - Color.blue(color)) * f).toInt(),
    )
}

fun hsl(h: Float, s: Float, l: Float): Int {
    val hue = ((h % 360) + 360) % 360
    val sat = s / 100
    val light = l / 100
    val c = (1 - abs(2 * light - 1)) * sat
    val x = c * (1 - abs((hue / 60) % 2 - 1))
    val m = light - c / 2
    val (r, g, b) = when {
        hue < 60 -> Triple(c, x, 0f)
        hue < 120 -> Triple(x, c, 0f)
        hue < 180 -> Triple(0f, c, x)
        hue < 240 -> Triple(0f, x, c)
        hue < 300 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color.rgb(((r + m) * 255).toInt(), ((g + m) * 255).toInt(), ((b + m) * 255).toInt())
}

/** CSS cubic-bezier timing: the fraction of the way at a fraction of the time. */
private class CubicBezier(private val x1: Float, private val y1: Float, private val x2: Float, private val y2: Float) {
    private fun curve(a1: Float, a2: Float, s: Float): Float {
        val u = 1 - s
        return 3 * u * u * s * a1 + 3 * u * s * s * a2 + s * s * s
    }

    fun at(x: Float): Float {
        var lo = 0f
        var hi = 1f
        repeat(24) {
            val mid = (lo + hi) / 2
            if (curve(x1, x2, mid) < x) lo = mid else hi = mid
        }
        return curve(y1, y2, (lo + hi) / 2)
    }
}

// "settle": quick at first, slowing as it winds out
private val SETTLE = CubicBezier(0.2f, 0.55f, 0.4f, 1f)

/** The design's seeded random (mulberry32). */
private class Mulberry32(seed: Int) {
    private var a = seed

    fun next(): Float {
        a += 0x6D2B79F5
        var t = (a xor (a ushr 15)) * (1 or a)
        t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
        return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toFloat() / 4294967296f
    }
}
