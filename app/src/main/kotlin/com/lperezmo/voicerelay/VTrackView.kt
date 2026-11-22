package com.lperezmo.voicerelay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * A track of stops picked with a V drawn on it (see [VGesture]): the volume track
 * ([VolumeTrackView]) and the timer track ([TimerTrackView]). Taps do nothing. The stroke
 * is drawn as you go, the stop under the V grows, the edge lights up in the target's
 * colour once the dip unlocks it, and a tick marks each new target. Subclasses give the
 * stops' places ([stops]), names and colours, and what a completed V does ([picked]).
 */
abstract class VTrackView(context: Context, private val stops: TrackStops) : View(context) {

    /** The stop that is on now (filled in its colour), or -1 for none. */
    var current = -1
        set(v) { field = v; invalidate() }

    var onTap: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    /** A finger landed on the track. */
    var onTouch: (() -> Unit)? = null
    /** A touch anywhere outside the track's window. */
    var onOutside: (() -> Unit)? = null

    /** Stop [i]'s short name, under it. */
    protected abstract fun label(i: Int): String

    /** Stop [i]'s colour: its fill when on, and the edge while the V aims at it. */
    protected abstract fun color(i: Int): Int

    /** A V was completed on stop [i]. */
    protected abstract fun picked(i: Int)

    protected val dp = context.resources.displayMetrics.density
    private val gesture = VGesture(DIP_DP * dp, RISE_DP * dp, TAP_SLOP_DP * dp) { x -> stops.at(x / dp) }
    private var applied = -1

    private val skin = PillSkin(dp)
    private val rail = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_RAIL; strokeWidth = 1f * dp }
    private val nodeFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nodeEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * dp }
    private val labelPaint = skin.label(10f).apply { textAlign = Paint.Align.CENTER }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TRAIL; style = Paint.Style.STROKE; strokeWidth = 3f * dp
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val trail = Path()
    private val rect = RectF()

    init {
        isHapticFeedbackEnabled = true
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) =
        setMeasuredDimension((W_DP * dp).toInt(), (H_DP * dp).toInt())

    private fun stopX(i: Int) = stops.xDp(i) * dp

    override fun onDraw(canvas: Canvas) {
        val unlocked = gesture.phase == VGesture.Phase.UNLOCKED
        val r = RADIUS_DP * dp
        // Inset by the outer line's width, so the dark line around the panel stays inside the window.
        rect.set(1f * dp, 1f * dp, width - 1f * dp, height - 1f * dp)
        skin.drawBody(canvas, rect, r, PillSkin.COLOR_PANEL)
        skin.drawInnerEdge(canvas, rect, r, if (unlocked) color(gesture.target) else PillSkin.COLOR_EDGE)

        // The rail is a row of tick marks, like a gauge's scale.
        val cy = NODE_Y_DP * dp
        var tick = stopX(0)
        while (tick <= stopX(stops.count - 1) + 0.5f) {
            canvas.drawLine(tick, cy - RAIL_TICK_DP * dp / 2f, tick, cy + RAIL_TICK_DP * dp / 2f, rail)
            tick += stops.railStepDp * dp
        }
        for (i in 0 until stops.count) {
            val on = i == (if (applied >= 0) applied else current)
            val targeted = (unlocked && i == gesture.target) || i == applied
            val radius = (if (targeted) NODE_HOT_R_DP else NODE_R_DP) * dp
            nodeFill.color = if (on) color(i) else COLOR_NODE
            nodeEdge.color = when {
                targeted -> 0xFFFFFFFF.toInt()
                on -> color(i)
                else -> COLOR_NODE_EDGE
            }
            canvas.drawCircle(stopX(i), cy, radius, nodeFill)
            canvas.drawCircle(stopX(i), cy, radius - 1f * dp, nodeEdge)
            labelPaint.color = if (on || targeted) PillSkin.COLOR_TEXT else PillSkin.COLOR_TEXT_DIM
            canvas.drawText(label(i), stopX(i), LABEL_Y_DP * dp, labelPaint)
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

    /** Feeds one point to the gesture. True when it picked a stop. */
    private fun step(x: Float, y: Float): Boolean {
        if (gesture.phase == VGesture.Phase.DONE) return true
        trail.lineTo(x, y)
        val hit = gesture.move(x, y) ?: return false
        applied = hit
        performHapticFeedback(HapticFeedbackConstants.CONFIRM, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
        picked(hit)
        return true
    }

    companion object {
        const val W_DP = 262
        const val H_DP = 76
        private const val RADIUS_DP = 22f
        private const val NODE_Y_DP = 24f
        private const val NODE_R_DP = 13f
        /** The stop the V is on grows a little. */
        private const val NODE_HOT_R_DP = 15f
        private const val RAIL_TICK_DP = 6f
        private const val LABEL_Y_DP = 58f
        /** How far down the finger must go before sideways moves pick a stop. */
        private const val DIP_DP = 20f
        /** How far back up from the lowest point applies the stop. */
        private const val RISE_DP = 16f
        private const val TAP_SLOP_DP = 8f

        /** The rail's ticks: white at 35%. */
        private const val COLOR_RAIL = 0x59FFFFFF
        private const val COLOR_NODE = 0xFF1C1C1E.toInt()
        private const val COLOR_NODE_EDGE = 0x59FFFFFF
        private const val COLOR_TRAIL = 0xBFFFFFFF.toInt()
    }
}
