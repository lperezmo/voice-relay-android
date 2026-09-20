package com.lperezmo.phonewhisper

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * A floating arrow pad: a ring you drag to move the whole thing, and a knob in
 * the middle you push in a direction to send arrow keys. It is drawn as two
 * hairline circles and nothing else, so it sits over a remote desktop session
 * without covering it.
 *
 * One push is one key: pushing the knob past [STEP_DP] sends a single arrow,
 * and the knob has to come back toward the middle before it sends another, so
 * four steps down is four deliberate pushes. A long-press on the ring switches
 * between the arrows keyboard and the one you were using.
 */
class JoystickView(context: Context) : View(context) {

    /** One arrow key, already resolved to a KEYCODE_DPAD_* value. */
    var onStep: ((Int) -> Unit)? = null

    /** Dragging the ring: the distance moved since the last call, in pixels. */
    var onDrag: ((Int, Int) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    /** Long-press on the ring. */
    var onPicker: (() -> Unit)? = null

    /** The knob was touched: a chance to get the arrows keyboard ready. */
    var onReady: (() -> Unit)? = null

    private val dp = context.resources.displayMetrics.density
    private val size = (SIZE_DP * dp).toInt()
    private val knobRadius = KNOB_DP * dp
    private val travel = TRAVEL_DP * dp
    private val step = STEP_DP * dp

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_EDGE; style = Paint.Style.STROKE; strokeWidth = 1.25f * dp
    }
    private val knobEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_KNOB_EDGE; style = Paint.Style.STROKE; strokeWidth = 1.25f * dp
    }

    private enum class Mode { NONE, DRAG, STICK }

    private var mode = Mode.NONE
    private var knobX = 0f
    private var knobY = 0f
    private var originX = 0f
    private var originY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    /** True once this push has sent its key; cleared when the knob comes back. */
    private var fired = false

    private val handler = Handler(Looper.getMainLooper())
    private val longPress = Runnable {
        mode = Mode.NONE
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onPicker?.invoke()
    }
    init {
        contentDescription = "Arrow joystick"
        isHapticFeedbackEnabled = true
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) = setMeasuredDimension(size, size)

    /**
     * Nothing solid: two hairlines, so the pad can be found without covering
     * what is under it. No arrow glyphs; the four directions are obvious once
     * you know it is there.
     */
    override fun onDraw(canvas: Canvas) {
        val c = size / 2f
        canvas.drawCircle(c, c, size / 2f - 1.5f * dp, edgePaint)
        canvas.drawCircle(c + knobX, c + knobY, knobRadius, knobEdgePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val c = size / 2f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                originX = event.x
                originY = event.y
                lastRawX = event.rawX
                lastRawY = event.rawY
                fired = false
                mode = if (hypot(event.x - c, event.y - c) <= knobRadius * 1.3f) Mode.STICK else Mode.DRAG
                if (mode == Mode.STICK) onReady?.invoke()
                if (mode == Mode.DRAG) {
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.DRAG -> {
                    val dx = event.rawX - lastRawX
                    val dy = event.rawY - lastRawY
                    if (abs(dx) + abs(dy) >= TAP_SLOP_DP * dp) handler.removeCallbacks(longPress)
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    onDrag?.invoke(dx.roundToInt(), dy.roundToInt())
                }

                Mode.STICK -> {
                    val dx = event.x - originX
                    val dy = event.y - originY
                    val distance = hypot(dx, dy)
                    // One key per push: pushing out sends once, and the knob has to
                    // come back to the middle before it can send again.
                    if (!fired && distance >= step) {
                        fire(
                            if (abs(dx) >= abs(dy)) {
                                if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                            } else {
                                if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                            }
                        )
                        fired = true
                    } else if (fired && distance <= step * RESET_FRACTION) {
                        fired = false
                    }
                    knobX = (event.x - c).coerceIn(-travel, travel)
                    knobY = (event.y - c).coerceIn(-travel, travel)
                    invalidate()
                }

                Mode.NONE -> {}
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                fired = false
                if (mode == Mode.DRAG) onDragEnd?.invoke()
                mode = Mode.NONE
                knobX = 0f
                knobY = 0f
                invalidate()
            }
        }
        return true
    }

    private fun fire(key: Int) {
        performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
        )
        onStep?.invoke(key)
    }

    companion object {
        const val SIZE_DP = 92
        private const val KNOB_DP = 21f
        private const val TRAVEL_DP = 22f
        /** Push needed for one key. Short enough for a nudge, long enough not to fire by accident. */
        private const val STEP_DP = 14f
        /** How far back toward the middle the knob must come before it fires again. */
        private const val RESET_FRACTION = 0.45f
        private const val TAP_SLOP_DP = 6f

        /** Hairlines only: enough to find the pad, not enough to hide anything. */
        private const val COLOR_EDGE = 0x26FFFFFF
        private const val COLOR_KNOB_EDGE = 0x40FFFFFF
    }
}
