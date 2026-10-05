package com.lperezmo.voicerelay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The small oval below the dictate button that opens Glyph's shape canvas, the
 * mirror of [VolumePillView] above it. A tap or a pull down (see [PullDown])
 * calls [onFire]; the pull fires while the finger is still moving, so the canvas
 * is up by the time it lifts. [sparkle] throws a little gold glitter off the
 * pill. The drawn pill is [PILL_W_DP] x [PILL_H_DP]; the view is taller so the
 * touch target reaches 44 dp, growing downward only so it never covers the
 * button above.
 */
class GlyphPillView(context: Context) : View(context) {

    var onFire: (() -> Unit)? = null

    private val dp = context.resources.displayMetrics.density
    private val gesture = PullDown(PULL_DP * dp, TAP_SLOP_DP * dp)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_FILL }
    private val flash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_GOLD }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_EDGE; style = Paint.Style.STROKE; strokeWidth = 1f * dp
    }
    private val star = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_GOLD }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 9f * dp
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.04f
    }
    private val glitter = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val starPath = Path()

    private class Speck(val angle: Float, val speed: Float, val size: Float, val delay: Float)
    private var specks = emptyList<Speck>()
    private var sparkleAt = 0L

    init {
        isHapticFeedbackEnabled = true
        contentDescription = "Glyph: tap or pull down to open the shape canvas"
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) =
        setMeasuredDimension((W_DP * dp).toInt(), (H_DP * dp).toInt())

    /** Gold flash on the pill and glitter thrown off it, about half a second. */
    fun sparkle() {
        performHapticFeedback(HapticFeedbackConstants.CONFIRM, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
        val rnd = Random(SystemClock.uptimeMillis())
        specks = List(SPECKS) {
            Speck(
                angle = (rnd.nextFloat() * 2 * Math.PI).toFloat(),
                speed = (14f + rnd.nextFloat() * 18f) * dp,
                size = (1.2f + rnd.nextFloat() * 1.6f) * dp,
                delay = rnd.nextFloat() * 0.25f,
            )
        }
        sparkleAt = SystemClock.uptimeMillis()
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> gesture.down(event.rawX, event.rawY)
            MotionEvent.ACTION_MOVE -> if (gesture.move(event.rawX, event.rawY)) onFire?.invoke()
            MotionEvent.ACTION_UP -> if (gesture.up()) {
                performClick()
                onFire?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> gesture.cancel()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = PILL_W_DP * dp
        val h = PILL_H_DP * dp
        val left = (width - w) / 2f
        val top = TOP_PAD_DP * dp
        rect.set(left, top, left + w, top + h)
        canvas.drawRoundRect(rect, h / 2, h / 2, fill)

        val t = (SystemClock.uptimeMillis() - sparkleAt) / SPARKLE_MS.toFloat()
        val sparkling = specks.isNotEmpty() && t < 1f
        if (sparkling) {
            flash.alpha = (200 * (1f - t)).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(rect, h / 2, h / 2, flash)
        }
        rect.inset(0.5f * dp, 0.5f * dp)
        canvas.drawRoundRect(rect, h / 2, h / 2, edge)

        val starR = 4f * dp
        val gap = 3f * dp
        val labelW = text.measureText(LABEL)
        val startX = left + (w - (starR * 2 + gap + labelW)) / 2f
        val cy = top + h / 2f
        drawStar(canvas, startX + starR, cy, starR, star)
        val baseline = cy - (text.ascent() + text.descent()) / 2f
        canvas.drawText(LABEL, startX + starR * 2 + gap, baseline, text)

        if (sparkling) {
            val cx = width / 2f
            for (s in specks) {
                val k = ((t - s.delay) / (1f - s.delay)).coerceIn(0f, 1f)
                if (k <= 0f) continue
                val d = s.speed * (1f - (1f - k) * (1f - k))
                glitter.color = COLOR_GOLD
                glitter.alpha = (255 * (1f - k)).toInt()
                drawStar(canvas, cx + cos(s.angle) * (w / 2f * 0.6f + d), cy + sin(s.angle) * (h / 2f + d), s.size * (1.6f - k), glitter)
            }
            postInvalidateOnAnimation()
        } else if (specks.isNotEmpty()) {
            specks = emptyList()
        }
    }

    /** A four-point sparkle. */
    private fun drawStar(canvas: Canvas, x: Float, y: Float, r: Float, paint: Paint) {
        val n = r * 0.32f
        starPath.reset()
        starPath.moveTo(x, y - r)
        starPath.lineTo(x + n, y - n)
        starPath.lineTo(x + r, y)
        starPath.lineTo(x + n, y + n)
        starPath.lineTo(x, y + r)
        starPath.lineTo(x - n, y + n)
        starPath.lineTo(x - r, y)
        starPath.lineTo(x - n, y - n)
        starPath.close()
        canvas.drawPath(starPath, paint)
    }

    companion object {
        const val W_DP = 56
        const val H_DP = 44
        const val PILL_W_DP = 50f
        const val PILL_H_DP = 18f
        /** Space above the drawn pill, inside the view. */
        const val TOP_PAD_DP = 4f
        /** How far down a pull goes before the canvas opens. */
        private const val PULL_DP = 12f
        private const val TAP_SLOP_DP = 10f
        private const val SPARKLE_MS = 520L
        private const val SPECKS = 12
        private const val LABEL = "Glyph"

        private const val COLOR_FILL = 0xB81C1C1E.toInt()
        private const val COLOR_EDGE = 0x29FFFFFF
        private const val COLOR_GOLD = 0xFFF2C46D.toInt()

        const val GLYPH_PACKAGE = "com.lperezmo.glyph"
        const val ACTION_OPEN_CANVAS = "com.lperezmo.glyph.OPEN_CANVAS"

        /** Glyph is installed and new enough to answer [ACTION_OPEN_CANVAS]. */
        @Suppress("DEPRECATION")
        fun glyphInstalled(context: Context): Boolean =
            context.packageManager.queryIntentActivities(Intent(ACTION_OPEN_CANVAS).setPackage(GLYPH_PACKAGE), 0).isNotEmpty()

        fun openCanvasIntent(): Intent = Intent(ACTION_OPEN_CANVAS)
            .setPackage(GLYPH_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
