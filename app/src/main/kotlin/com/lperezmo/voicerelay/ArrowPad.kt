package com.lperezmo.voicerelay

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager

/**
 * The floating arrow pad ([JoystickView]) and the keyboard switching behind it:
 * arrow keys go out through the arrows keyboard ([KeysInputMethodService]), which
 * is switched to while the pad is in use and switched back once it is quiet.
 */
class ArrowPad(
    private val service: AccessibilityService,
    private val settings: AppSettings,
    private val injector: TextInjector,
    private val handler: Handler,
    private val showFeedback: (text: String, durationMs: Long) -> Unit,
) {
    private var joystickView: JoystickView? = null
    private var joystickParams: WindowManager.LayoutParams? = null
    /** Set when the arrows keyboard was chosen by a long-press, so it stays until switched back. */
    var pinnedArrows = false
        private set
    /** True while a finger is on the pad, so the keyboard is not swapped mid-push. */
    private var padTouched = false
    private val restoreKeyboard = Runnable {
        if (padTouched) scheduleKeyboardRestore() else restorePreviousKeyboard()
    }

    private val dp get() = service.resources.displayMetrics.density
    private val screenW get() = service.resources.displayMetrics.widthPixels
    private val screenH get() = service.resources.displayMetrics.heightPixels

    /** Shows or hides the arrow pad to match the setting. Safe to call at any time. */
    fun refresh(buttonsHidden: Boolean) {
        if (settings.joystickEnabled && !buttonsHidden) showJoystick() else hide()
    }

    /** The service is going away: back to your own keyboard, and the pad comes down. */
    fun destroy() {
        handler.removeCallbacks(restoreKeyboard)
        restorePreviousKeyboard()
        hide()
    }

    private fun showJoystick() {
        if (joystickView != null) return
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = (JoystickView.SIZE_DP * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = settings.joystickX.takeIf { it >= 0 } ?: margin
            y = settings.joystickY.takeIf { it >= 0 } ?: (screenH * 2 / 3)
        }
        val view = JoystickView(service).apply {
            onStep = { key -> sendArrow(key) }
            onDrag = { dx, dy ->
                params.x = (params.x + dx).coerceIn(0, maxOf(0, screenW - size))
                params.y = (params.y + dy).coerceIn(0, maxOf(0, screenH - size))
                wm.updateViewLayout(this, params)
            }
            onDragEnd = {
                settings.joystickX = params.x
                settings.joystickY = params.y
            }
            onPicker = { toggleArrowsKeyboard() }
            onReady = { onPadReady() }
            onDone = { onPadDone() }
        }
        wm.addView(view, params)
        joystickView = view
        joystickParams = params
    }

    fun hide() {
        handler.removeCallbacks(restoreKeyboard)
        pinnedArrows = false
        restorePreviousKeyboard()
        val view = joystickView ?: return
        (service.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
        joystickView = null
        joystickParams = null
    }

    /**
     * Touching the knob: switch to the arrows keyboard before the first key, so
     * it is bound by the time the push lands. Nothing happens if it is already
     * selected, or if automatic switching is off.
     */
    private fun onPadReady() {
        padTouched = true
        handler.removeCallbacks(restoreKeyboard)
        if (!settings.joystickAutoSwitch || pinnedArrows) return
        if (KeysInputMethodService.instance == null) switchKeyboard(arrowsKeyboardId(), remember = true)
        // Armed here, not after a key lands: in an app that takes no keys, the
        // keys never land, and the keyboard must still come back.
        scheduleKeyboardRestore()
    }

    /** Finger off the pad: start the clock even if nothing was ever sent. */
    private fun onPadDone() {
        padTouched = false
        scheduleKeyboardRestore()
    }

    /**
     * Arrow keys only reach other apps through a keyboard, so they go to
     * [KeysInputMethodService] when it is the selected one. Otherwise the caret
     * is moved through accessibility, which works in ordinary text fields but
     * not in a remote desktop session.
     */
    private fun sendArrow(keyCode: Int) = deliverArrow(keyCode, 0)

    /**
     * The keyboard takes a moment to bind after a switch, so a key that arrives
     * in that window is retried briefly instead of being dropped.
     */
    private fun deliverArrow(keyCode: Int, attempt: Int) {
        scheduleKeyboardRestore()
        if (KeysInputMethodService.send(keyCode)) return
        val switching = settings.joystickAutoSwitch && KeysInputMethodService.instance == null
        if (switching && attempt < KEY_RETRIES) {
            handler.postDelayed({ deliverArrow(keyCode, attempt + 1) }, KEY_RETRY_MS)
            return
        }
        if (injector.moveCaret(keyCode)) return
        showFeedback("No keyboard is carrying the arrows. Long-press the pad to switch.", 2500)
    }

    /**
     * Back to your own keyboard once the pad has been quiet for a moment. Armed
     * from every path that can leave the arrows keyboard selected, so the only
     * way to keep it is to pin it with a long-press.
     */
    private fun scheduleKeyboardRestore() {
        if (pinnedArrows) return
        handler.removeCallbacks(restoreKeyboard)
        handler.postDelayed(restoreKeyboard, AUTO_RETURN_MS)
    }

    fun restorePreviousKeyboard() {
        val previous = settings.previousIme.takeIf { it.isNotBlank() } ?: return
        if (currentKeyboardId() != arrowsKeyboardId()) return
        switchKeyboard(previous, remember = false)
        settings.previousIme = ""
    }

    private fun arrowsKeyboardId() =
        ComponentName(service, KeysInputMethodService::class.java).flattenToShortString()

    private fun currentKeyboardId(): String? =
        Settings.Secure.getString(service.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)

    /** True when the switch was accepted. [remember] keeps the keyboard to come back to. */
    private fun switchKeyboard(target: String, remember: Boolean): Boolean {
        val current = currentKeyboardId()
        if (current == target) return true
        return try {
            val ok = service.softKeyboardController.switchToInputMethod(target)
            if (ok && remember) settings.previousIme = current.orEmpty()
            ok
        } catch (e: Exception) {
            Log.w(TAG, "Keyboard switch refused: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Long-press on the pad: swap between the arrows keyboard and whatever was
     * selected before. An accessibility service may switch input methods
     * directly (API 30+); the system's own picker is blocked for background
     * apps, so it is only the fallback when the switch is refused.
     */
    private fun toggleArrowsKeyboard() {
        val arrows = arrowsKeyboardId()
        val goingToArrows = currentKeyboardId() != arrows
        val target = if (goingToArrows) arrows else settings.previousIme.ifBlank { null }
        if (target == null) {
            showKeyboardPicker()
            return
        }
        handler.removeCallbacks(restoreKeyboard)
        if (!switchKeyboard(target, remember = goingToArrows)) {
            showKeyboardPicker()
            return
        }
        // Pinned: a deliberate switch is not undone by the idle timer.
        pinnedArrows = goingToArrows
        if (goingToArrows) {
            showFeedback("Arrows keyboard on, and staying on. Long-press again to switch back.", 2500)
        } else {
            settings.previousIme = ""
            showFeedback("Keyboard switched back", 2000)
        }
    }

    private fun showKeyboardPicker() {
        val imm = service.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showInputMethodPicker()
        showFeedback("Pick Voice Relay arrows in the keyboard list", 3000)
    }

    private companion object {
        const val TAG = "VoiceRelay"
        const val MARGIN_DP = 8
        /** Quiet time on the pad before your own keyboard comes back. */
        const val AUTO_RETURN_MS = 4000L
        const val KEY_RETRIES = 8
        const val KEY_RETRY_MS = 60L
    }
}
