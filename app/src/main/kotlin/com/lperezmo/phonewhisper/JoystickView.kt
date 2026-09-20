package com.lperezmo.phonewhisper

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
 * the middle you push in a direction to send arrow keys.
 *
 * Pushing the knob is a ratchet, not a switch: every [STEP_DP] of travel sends
 * one more key, so a short nudge is one arrow and a long push is several. Hold
 * the knob out and it repeats. A long-press on the ring opens the keyboard
 * picker, since the keys only reach other apps while [KeysInputMethodService]
 * is the selected keyboard.
 */
class JoystickView(context: Context) : View(context) {

    /** One arrow key, already resolved to a KEYCODE_DPAD_* value. */
    var onStep: ((Int) -> Unit)? = null

    /** Dragging the ring: the distance moved since the last call, in pixels. */
    var onDrag: ((Int, Int) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    /** Long-press on the ring. */
    var onPicker: (() -> Unit)? = null

    private val dp = context.resources.displayMetrics.density
    private val size = (SIZE_DP * dp).toInt()
    private val knobRadius = KNOB_DP * dp
    private val travel = TRAVEL_DP * dp
    private val step = STEP_DP * dp

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_RING }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_EDGE; style = Paint.Style.STROKE; strokeWidth = 1.5f * dp
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_ARROW }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_KNOB }
    private val arrow = Path()

    private enum class Mode { NONE, DRAG, STICK }

    private var mode = Mode.NONE
    private var knobX = 0f
    private var knobY = 0f
    private var originX = 0f
    private var originY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var heldKey = 0

    private val handler = Handler(Looper.getMainLooper())
    private val longPress = Runnable {
        mode = Mode.NONE
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onPicker?.invoke()
    }
    private val repeat = object : Runnable {
        override fun run() {
            if (heldKey == 0) return
            fire(heldKey)
            handler.postDelayed(this, REPEAT_MS)
        }
    }

    init {
        contentDescription = "Arrow joystick"
        isHapticFeedbackEnabled = true
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) = setMeasuredDimension(size, size)

    override fun onDraw(canvas: Canvas) {
        val c = size / 2f
        val ring = size / 2f - 1.5f * dp
        canvas.drawCircle(c, c, ring, ringPaint)
        canvas.drawCircle(c, c, ring, edgePaint)

        val tip = ring - 7 * dp
        val half = 6.5f * dp
        val depth = 9 * dp
        drawArrow(canvas, c, c - tip, c, c - tip + depth, half, vertical = true)
        drawArrow(canvas, c, c + tip, c, c + tip - depth, half, vertical = true)
        drawArrow(canvas, c - tip, c, c - tip + depth, c, half, vertical = false)
        drawArrow(canvas, c + tip, c, c + tip - depth, c, half, vertical = false)

        canvas.drawCircle(c + knobX, c + knobY, knobRadius, knobPaint)
        canvas.drawCircle(c + knobX, c + knobY, knobRadius, edgePaint)
    }

    /** One triangle pointing from (baseX, baseY) out to (tipX, tipY). */
    private fun drawArrow(
        canvas: Canvas, tipX: Float, tipY: Float, baseX: Float, baseY: Float,
        half: Float, vertical: Boolean,
    ) {
        arrow.reset()
        arrow.moveTo(tipX, tipY)
        if (vertical) {
            arrow.lineTo(baseX - half, baseY)
            arrow.lineTo(baseX + half, baseY)
        } else {
            arrow.lineTo(baseX, baseY - half)
            arrow.lineTo(baseX, baseY + half)
        }
        arrow.close()
        canvas.drawPath(arrow, arrowPaint)
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
                mode = if (hypot(event.x - c, event.y - c) <= knobRadius * 1.3f) Mode.STICK else Mode.DRAG
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
                    var dx = event.x - originX
                    var dy = event.y - originY
                    // One key per STEP_DP of travel, on whichever axis is winning.
                    while (abs(dx) >= step || abs(dy) >= step) {
                        if (abs(dx) >= abs(dy)) {
                            val key = if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                            fire(key)
                            originX += if (dx > 0) step else -step
                            dx = event.x - originX
                        } else {
                            val key = if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                            fire(key)
                            originY += if (dy > 0) step else -step
                            dy = event.y - originY
                        }
                    }
                    knobX = (event.x - c).coerceIn(-travel, travel)
                    knobY = (event.y - c).coerceIn(-travel, travel)
                    startRepeat(event.x - c, event.y - c)
                    invalidate()
                }

                Mode.NONE -> {}
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                stopRepeat()
                if (mode == Mode.DRAG) onDragEnd?.invoke()
                mode = Mode.NONE
                knobX = 0f
                knobY = 0f
                invalidate()
            }
        }
        return true
    }

    /** Held out past the travel limit: keep sending the same key. */
    private fun startRepeat(dx: Float, dy: Float) {
        val key = when {
            abs(dx) < travel && abs(dy) < travel -> 0
            abs(dx) >= abs(dy) -> if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
            else -> if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
        }
        if (key == heldKey) return
        stopRepeat()
        heldKey = key
        if (key != 0) handler.postDelayed(repeat, REPEAT_DELAY_MS)
    }

    private fun stopRepeat() {
        heldKey = 0
        handler.removeCallbacks(repeat)
    }

    private fun fire(key: Int) {
        performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
        )
        onStep?.invoke(key)
    }

    companion object {
        const val SIZE_DP = 116
        private const val KNOB_DP = 26f
        private const val TRAVEL_DP = 26f
        /** Travel per key. Short enough for one nudge, long enough not to fire by accident. */
        private const val STEP_DP = 16f
        private const val TAP_SLOP_DP = 6f
        private const val REPEAT_DELAY_MS = 380L
        private const val REPEAT_MS = 110L

        private const val COLOR_RING = 0xCC1C1C1E.toInt()
        private const val COLOR_EDGE = 0x33FFFFFF
        private const val COLOR_ARROW = 0xB3FFFFFF.toInt()
        private const val COLOR_KNOB = 0xE62C2C2E.toInt()
    }
}
