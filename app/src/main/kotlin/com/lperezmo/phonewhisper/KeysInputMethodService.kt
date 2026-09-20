package com.lperezmo.phonewhisper

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View

/**
 * A keyboard that draws nothing and types nothing. It exists so the arrow
 * joystick can send real key events: an accessibility overlay cannot, because
 * injecting keys is system-only, while a keyboard may send them through its
 * [android.view.inputmethod.InputConnection].
 *
 * Apps that take keys that way, such as remote desktop clients and terminals,
 * see these as ordinary arrows. Select this keyboard while you need the
 * joystick, and switch back to your own afterwards; the joystick offers the
 * keyboard picker on a long-press.
 */
class KeysInputMethodService : InputMethodService() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** No input view: the joystick is the UI, and it floats above every app. */
    override fun onCreateInputView(): View? = null

    override fun onEvaluateFullscreenMode() = false

    /** Keeps the screen layout as it is; there is nothing to make room for. */
    override fun onEvaluateInputViewShown() = false

    /** Sends [keyCode] as a down then an up. False when no app is taking input. */
    fun sendKey(keyCode: Int): Boolean {
        val connection = currentInputConnection ?: return false
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
        return connection.sendKeyEvent(down) && connection.sendKeyEvent(up)
    }

    companion object {
        /** Set while this keyboard is the selected one; null otherwise. */
        @Volatile
        var instance: KeysInputMethodService? = null

        fun send(keyCode: Int): Boolean = instance?.sendKey(keyCode) ?: false
    }
}
