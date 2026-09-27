package com.lperezmo.voicerelay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * The four-stop volume track, opened from [VolumePillView]. Taps do nothing:
 * a preset is picked with a V drawn on the track (see [VGesture]). The
 * stroke is drawn as you go, the edge lights up in the target's color once
 * the dip unlocks it, and a tick marks each new target.
 */
class VolumeTrackView(context: Context) : View(context) {

    /** Index into [VolumePreset.entries] of the preset that is on, or -1 for none. */
    var current = -1
        set(v) { field = v; invalidate() }

    var onCommit: ((VolumePreset) -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    /** A finger landed on the track: hold off closing it. */
    var onTouch: (() -> Unit)? = null
    /** A touch anywhere outside the track. */
    var onOutside: (() -> Unit)? = null

    private val dp = context.resources.displayMetrics.density
    private val presets = VolumePreset.entries
    private val gesture = VGesture(DIP_DP * dp, RISE_DP * dp, TAP_SLOP_DP * dp) { x -> stopAt(x) }
    private var applied = -1

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_BG }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * dp }
    private val rail = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_RAIL; strokeWidth = 2f * dp }
    private val nodeFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nodeEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * dp }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * dp
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.06f
        textAlign = Paint.Align.CENTER
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TRAIL; style = Paint.Style.STROKE; strokeWidth = 3f * dp
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val trail = Path()
    private val rect = RectF()

    init {
        contentDescription = "Volume presets. Hold, dip down, slide to a preset, then rise to apply."
        isHapticFeedbackEnabled = true
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) =
        setMeasuredDimension((W_DP * dp).toInt(), (H_DP * dp).toInt())

    private fun stopX(i: Int) = (STOP0_DP + STEP_DP * i) * dp
    private fun stopAt(x: Float) = ((x / dp - STOP0_DP) / STEP_DP).roundToInt().coerceIn(0, presets.size - 1)

    override fun onDraw(canvas: Canvas) {
        val unlocked = gesture.phase == VGesture.Phase.UNLOCKED
        val r = RADIUS_DP * dp
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, bg)
        edge.color = if (unlocked) presets[gesture.target].color else COLOR_EDGE
        rect.inset(0.5f * dp, 0.5f * dp)
        canvas.drawRoundRect(rect, r, r, edge)

        val cy = NODE_Y_DP * dp
        canvas.drawLine(stopX(0), cy, stopX(presets.size - 1), cy, rail)
        for ((i, p) in presets.withIndex()) {
            val on = i == (if (applied >= 0) applied else current)
            val targeted = (unlocked && i == gesture.target) || i == applied
            val radius = NODE_R_DP * dp * (if (targeted) 1.18f else 1f)
            nodeFill.color = if (on) p.color else COLOR_NODE
            nodeEdge.color = when {
                targeted -> 0xFFFFFFFF.toInt()
                on -> p.color
                else -> COLOR_NODE_EDGE
            }
            canvas.drawCircle(stopX(i), cy, radius, nodeFill)
            canvas.drawCircle(stopX(i), cy, radius - 1f * dp, nodeEdge)
            label.color = if (on || targeted) 0xFFFFFFFF.toInt() else COLOR_LABEL
            canvas.drawText(p.short, stopX(i), LABEL_Y_DP * dp, label)
        }
        if (!trail.isEmpty) canvas.drawPath(trail, stroke)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> onOutside?.invoke()

            MotionEvent.ACTION_DOWN -> {
                onTouch?.invoke()
                applied = -1
                gesture.down(event.x, event.y, current.coerceAtLeast(0))
                trail.reset()
                trail.moveTo(event.x, event.y)
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                val phase = gesture.phase
                val target = gesture.target
                for (h in 0 until event.historySize) {
                    if (step(event.getHistoricalX(h), event.getHistoricalY(h))) break
                }
                if (gesture.phase != VGesture.Phase.DONE) step(event.x, event.y)
                if (gesture.phase == VGesture.Phase.UNLOCKED && (phase != VGesture.Phase.UNLOCKED || target != gesture.target)) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                }
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val end = gesture.up()
                if (end != VGesture.End.COMMITTED) trail.reset()
                invalidate()
                if (event.actionMasked == MotionEvent.ACTION_UP) when (end) {
                    VGesture.End.TAP -> onTap?.invoke()
                    VGesture.End.CANCELLED -> onCancel?.invoke()
                    VGesture.End.COMMITTED -> {}
                }
            }
        }
        return true
    }

    /** Feeds one point to the gesture. True when it applied a preset. */
    private fun step(x: Float, y: Float): Boolean {
        if (gesture.phase == VGesture.Phase.DONE) return true
        trail.lineTo(x, y)
        val hit = gesture.move(x, y) ?: return false
        applied = hit
        performHapticFeedback(HapticFeedbackConstants.CONFIRM, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
        onCommit?.invoke(presets[hit])
        return true
    }

    companion object {
        const val W_DP = 262
        const val H_DP = 76
        private const val RADIUS_DP = 22f
        private const val STOP0_DP = 41f
        private const val STEP_DP = 60f
        private const val NODE_Y_DP = 24f
        private const val NODE_R_DP = 13f
        private const val LABEL_Y_DP = 58f
        /** How far down the finger must go before sideways moves pick a stop. */
        private const val DIP_DP = 20f
        /** How far back up from the lowest point applies the stop. */
        private const val RISE_DP = 16f
        private const val TAP_SLOP_DP = 8f

        private const val COLOR_BG = 0xDB1C1C1E.toInt()
        private const val COLOR_EDGE = 0x1FFFFFFF
        private const val COLOR_RAIL = 0x2EFFFFFF
        private const val COLOR_NODE = 0xFF1C1C1E.toInt()
        private const val COLOR_NODE_EDGE = 0x59FFFFFF
        private const val COLOR_LABEL = 0x9EE8EAED.toInt()
        private const val COLOR_TRAIL = 0xBFFFFFFF.toInt()
    }
}
