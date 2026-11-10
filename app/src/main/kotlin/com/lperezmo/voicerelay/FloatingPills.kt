package com.lperezmo.voicerelay

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Handler
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.content.ContextCompat

/**
 * The two optional pills that ride with the dictate button: the volume pill
 * ([VolumePillView]) above it, which opens the preset track ([VolumeTrackView]),
 * and the Glyph pill ([GlyphPillView]) below it. Where they sit is [PillLayout].
 *
 * The button stays the service's: [bubble] reads its window as it is now (null
 * while it is not on screen), and [onPillAdded] lets the service settle the
 * button with the new pill's room and raise it back over the pill. The service
 * calls [follow] whenever the button moves. Everything runs on the main thread.
 */
class FloatingPills(
    private val service: AccessibilityService,
    private val settings: AppSettings,
    private val volume: VolumeController,
    private val handler: Handler,
    /** The ring window, the button inside it and the screen margin, in dp: the button's geometry. */
    private val ringDp: Int,
    private val buttonDp: Int,
    private val marginDp: Int,
    private val bubble: () -> WindowManager.LayoutParams?,
    private val buttonsHidden: () -> Boolean,
    private val onPillAdded: () -> Unit,
    private val showFeedback: (text: String, durationMs: Long) -> Unit,
) {
    private var pillView: VolumePillView? = null
    private var pillParams: WindowManager.LayoutParams? = null
    private var trackView: VolumeTrackView? = null
    private var trackParams: WindowManager.LayoutParams? = null
    private val closeTrack = Runnable { hideTrack() }
    private var glyphView: GlyphPillView? = null
    private var glyphParams: WindowManager.LayoutParams? = null
    /** Glyph installed, updated or removed: show or hide its pill to match. */
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.data?.schemeSpecificPart == GlyphPillView.GLYPH_PACKAGE) refreshGlyph()
        }
    }
    /** A tap on the pill closes an open track through its outside touch first; this keeps it from reopening. */
    private var trackClosedOutsideAt = 0L
    private val refreshPill = Runnable { pillView?.update(volume.current()) }
    /** Any volume, ringer or DND change, from the presets or not, redraws the pill. */
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handler.removeCallbacks(refreshPill)
            handler.postDelayed(refreshPill, PILL_REFRESH_MS)
        }
    }

    private val dp get() = service.resources.displayMetrics.density
    private val screenW get() = service.resources.displayMetrics.widthPixels
    private val screenH get() = service.resources.displayMetrics.heightPixels
    private val wm get() = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun layout() = PillLayout(dp, ringDp, buttonDp, marginDp)

    /** Starts watching for Glyph being installed or removed. Call once, when the service connects. */
    fun start() {
        ContextCompat.registerReceiver(
            service, packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** The service is going away: stop watching Glyph. Call [hide] too, to take the pills down. */
    fun stop() {
        try { service.unregisterReceiver(packageReceiver) } catch (_: IllegalArgumentException) {}
    }

    /** Takes down the track and both pills. */
    fun hide() {
        hideVolumePill()
        hideGlyphPill()
    }

    /** How far the shown pills reach over and under the button's ring window, for [BubbleBounds]. */
    fun above(): Int = layout().above(pillView != null)
    fun below(): Int = layout().below(glyphView != null)

    /** Keeps the volume pill and track, and the Glyph pill, next to the button as it moves. */
    fun follow(wm: WindowManager, bubble: WindowManager.LayoutParams) {
        pillParams?.let {
            positionPill(it, bubble)
            wm.updateViewLayout(pillView, it)
        }
        trackParams?.let {
            positionTrack(it, bubble)
            wm.updateViewLayout(trackView, it)
        }
        glyphParams?.let {
            positionGlyphPill(it, bubble)
            wm.updateViewLayout(glyphView, it)
        }
    }

    // --- Volume presets ---

    /** Shows or hides the volume pill to match the setting. Safe to call at any time. */
    fun refreshVolume() {
        if (settings.volumePillEnabled && !buttonsHidden()) showVolumePill() else hideVolumePill()
    }

    private fun showVolumePill() {
        if (pillView != null) return
        val bubble = bubble() ?: return
        val params = WindowManager.LayoutParams(
            (VolumePillView.W_DP * dp).toInt(), (VolumePillView.H_DP * dp).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        positionPill(params, bubble)
        val view = VolumePillView(service).apply {
            update(volume.current())
            setOnClickListener {
                when {
                    trackView != null -> hideTrack()
                    android.os.SystemClock.uptimeMillis() - trackClosedOutsideAt > REOPEN_GUARD_MS -> showTrack()
                }
            }
        }
        wm.addView(view, params)
        pillView = view
        pillParams = params
        onPillAdded()
        ContextCompat.registerReceiver(
            service, volumeReceiver,
            IntentFilter().apply {
                addAction(ACTION_VOLUME_CHANGED)
                addAction(android.media.AudioManager.RINGER_MODE_CHANGED_ACTION)
                addAction(android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun hideVolumePill() {
        hideTrack()
        val view = pillView ?: return
        try { service.unregisterReceiver(volumeReceiver) } catch (_: IllegalArgumentException) {}
        handler.removeCallbacks(refreshPill)
        wm.removeView(view)
        pillView = null
        pillParams = null
    }

    private fun showTrack() {
        if (trackView != null) return
        if (!volume.hasDndAccess) {
            showFeedback("Grant Do Not Disturb access in Voice Relay to use volume presets", 3000)
            return
        }
        val bubble = bubble() ?: return
        val params = WindowManager.LayoutParams(
            (VolumeTrackView.W_DP * dp).toInt(), (VolumeTrackView.H_DP * dp).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Outside touches are reported (to close the track) and still reach the app.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        positionTrack(params, bubble)
        val view = VolumeTrackView(service).apply {
            current = volume.current().preset?.ordinal ?: -1
            onTouch = { handler.removeCallbacks(closeTrack) }
            onCommit = { preset -> applyPreset(preset) }
            onTap = {
                showFeedback("Taps do nothing. Draw a V: dip, slide, rise.", FEEDBACK_MS)
                scheduleTrackClose(TRACK_IDLE_MS)
            }
            onCancel = {
                showFeedback("Cancelled, nothing changed", FEEDBACK_MS)
                scheduleTrackClose(TRACK_IDLE_MS)
            }
            onOutside = {
                trackClosedOutsideAt = android.os.SystemClock.uptimeMillis()
                hideTrack()
            }
        }
        wm.addView(view, params)
        trackView = view
        trackParams = params
        scheduleTrackClose(TRACK_IDLE_MS)
    }

    /** Closes the preset track if it is open. Safe to call at any time. */
    fun hideTrack() {
        handler.removeCallbacks(closeTrack)
        val view = trackView ?: return
        wm.removeView(view)
        trackView = null
        trackParams = null
    }

    private fun scheduleTrackClose(delayMs: Long) {
        handler.removeCallbacks(closeTrack)
        handler.postDelayed(closeTrack, delayMs)
    }

    private fun applyPreset(preset: VolumePreset) {
        val error = volume.apply(preset)
        pillView?.update(volume.current())
        if (error != null) {
            showFeedback(error, 3000)
        } else {
            val note = if (preset.dnd && !volume.exactTimer) " (ends within 10 min of the hour)" else ""
            showFeedback("${preset.label} applied$note", FEEDBACK_MS)
        }
        scheduleTrackClose(TRACK_CLOSE_AFTER_APPLY_MS)
    }

    private fun positionPill(params: WindowManager.LayoutParams, bubble: WindowManager.LayoutParams) {
        val spot = layout().volumePill(bubble.x, bubble.y, params.width, params.height)
        params.x = spot.x
        params.y = spot.y
    }

    private fun positionTrack(params: WindowManager.LayoutParams, bubble: WindowManager.LayoutParams) {
        val spot = layout().track(bubble.x, bubble.y, params.width, params.height, screenW)
        params.x = spot.x
        params.y = spot.y
    }

    // --- Glyph pill ---

    /** Shows the Glyph pill when the setting is on and Glyph is installed, else hides it. Safe to call at any time. */
    fun refreshGlyph() {
        if (settings.glyphPillEnabled && !buttonsHidden() && GlyphPillView.glyphInstalled(service)) showGlyphPill() else hideGlyphPill()
    }

    private fun showGlyphPill() {
        if (glyphView != null) return
        val bubble = bubble() ?: return
        val params = WindowManager.LayoutParams(
            (GlyphPillView.W_DP * dp).toInt(), (GlyphPillView.H_DP * dp).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        positionGlyphPill(params, bubble)
        val view = GlyphPillView(service).apply { onFire = { openGlyph() } }
        wm.addView(view, params)
        glyphView = view
        glyphParams = params
        onPillAdded()
    }

    private fun hideGlyphPill() {
        val view = glyphView ?: return
        wm.removeView(view)
        glyphView = null
        glyphParams = null
    }

    /** Opens Glyph's canvas in its own task, over whatever is on screen. */
    private fun openGlyph() {
        hideTrack()
        try {
            service.startActivity(GlyphPillView.openCanvasIntent())
            glyphView?.sparkle()
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "Glyph canvas not found", e)
            showFeedback("Glyph is not installed", FEEDBACK_MS)
            refreshGlyph()
        }
    }

    private fun positionGlyphPill(params: WindowManager.LayoutParams, bubble: WindowManager.LayoutParams) {
        val spot = layout().glyphPill(bubble.x, bubble.y, params.width, params.height, screenH)
        params.x = spot.x
        params.y = spot.y
    }

    private companion object {
        const val TAG = "VoiceRelay"
        /** The service's default feedback duration, for the messages that used it. */
        const val FEEDBACK_MS = 2000L
        /** An open track closes itself after this long untouched, so it cannot linger under a pocket. */
        const val TRACK_IDLE_MS = 6000L
        const val TRACK_CLOSE_AFTER_APPLY_MS = 700L
        const val PILL_REFRESH_MS = 150L
        const val REOPEN_GUARD_MS = 800L
        const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
    }
}
