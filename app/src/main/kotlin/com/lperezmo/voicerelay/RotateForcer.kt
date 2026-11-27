package com.lperezmo.voicerelay

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.lperezmo.voicerelay.glyph.Hold
import com.lperezmo.voicerelay.glyph.Rotate

/**
 * Holds the screen upright or sideways for Glyph's rotation actions with an invisible 1 px
 * accessibility overlay that asks for that orientation. The hold lives in memory only: it
 * lasts until let go or until the service stops ([release] in onDestroy).
 *
 * Glyph runs the action while its see-through canvas is in front; turning the screen then
 * would rebuild the canvas and keep it open, so the window goes on after the canvas closes.
 */
class RotateForcer(private val context: Context, private val handler: Handler) {
    companion object {
        private const val TAG = "VoiceRelay"
        private const val APPLY_DELAY_MS = 700L
    }

    var held: Hold? = null
        private set
    private var view: View? = null
    private val apply = Runnable { applyNow() }

    /** "Flip the screen". What is showing is read now, before anything changes. */
    fun flip(): String = run(Rotate.flip(held, showingSideways()))

    /** "Rotation back to auto". */
    fun auto(): String = run(Rotate.auto(held))

    fun release() {
        handler.removeCallbacks(apply)
        held = null
        removeView()
    }

    private fun run(step: Rotate.Step): String {
        held = step.hold
        handler.removeCallbacks(apply)
        handler.postDelayed(apply, APPLY_DELAY_MS)
        return step.say
    }

    private fun showingSideways() =
        context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun applyNow() {
        val hold = held ?: return removeView()
        val wm = context.getSystemService(WindowManager::class.java)
        val orientation = when (hold) {
            Hold.UPRIGHT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            Hold.SIDEWAYS -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        try {
            val v = view
            if (v != null) {
                val lp = v.layoutParams as WindowManager.LayoutParams
                lp.screenOrientation = orientation
                wm.updateViewLayout(v, lp)
                return
            }
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply { screenOrientation = orientation }
            val nv = View(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            wm.addView(nv, lp)
            view = nv
        } catch (e: Exception) {
            // no window went on, so nothing is held: the next flip must start from the screen again
            Log.w(TAG, "rotation hold failed: ${e.message}")
            held = null
            removeView()
            Toast.makeText(context.applicationContext, Rotate.FAILED, Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeView() {
        val v = view ?: return
        view = null
        try {
            context.getSystemService(WindowManager::class.java).removeView(v)
        } catch (_: Exception) {}
    }
}
