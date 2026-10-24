package com.lperezmo.voicerelay

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Hosts the floating dictation button and runs one dictation at a time:
 *
 *   IDLE --tap--> RECORDING --tap--> WORKING --text--> IDLE
 *                                        \--network error--> RETRY
 *   RETRY --tap--> WORKING (same audio)   RETRY --long-press, then tap "Discard"--> IDLE
 *   IDLE --long-press--> WORKING (grammar fix of the selection or focused field) --> IDLE
 *
 * A recording is saved to disk ([PendingRecording]) before it is uploaded and
 * removed once it is transcribed, so a failed one comes back in RETRY even after
 * the service restarts (an app update, a crash, a reboot).
 *
 * While the phone is locked, recordings are capped by [LockScreenLimits]. After
 * text lands in a field, the feedback bubble offers undo for a few seconds.
 *
 * The overlay is a TYPE_ACCESSIBILITY_OVERLAY window. The service follows which
 * text field is focused, and reads a field's text only to insert, fix or undo
 * (see [TextInjector]).
 */
class WhisperAccessibilityService : AccessibilityService() {

    companion object {
        var instance: WhisperAccessibilityService? = null
        private const val TAG = "VoiceRelay"
        private const val BTN_DP = 44
        private const val PAD_DP = 10
        private const val MARGIN_DP = 8
        private const val TAP_THRESHOLD_DP = 10
        private const val RING_DP = 56
        /** How far above the middle the button starts. Dead centre on the right
         *  edge is where chat apps put their send button. */
        private const val BUTTON_LIFT_DP = 80
        private const val FB_RADIUS_DP = 16f
        private const val FB_CHIP_RADIUS_DP = 18f
        private const val MIN_CLIP_SECONDS = 0.3
        private const val MAX_SECONDS = 600
        private const val UNDO_WINDOW_MS = 5000L
        private const val CONFIRM_WINDOW_MS = 5000L
        private const val BADGE_DP = 20
        private const val DRAFT_INTERVAL_MS = 250L
        /** Quiet time on the pad before your own keyboard comes back. */
        private const val AUTO_RETURN_MS = 4000L
        private const val KEY_RETRIES = 8
        private const val KEY_RETRY_MS = 60L
        /** Space between each pill (volume above, Glyph below) and the dictate button, and between the track and the volume pill. */
        private const val PILL_GAP_DP = 6
        private const val TRACK_GAP_DP = 12
        /** An open track closes itself after this long untouched, so it cannot linger under a pocket. */
        private const val TRACK_IDLE_MS = 6000L
        private const val TRACK_CLOSE_AFTER_APPLY_MS = 700L
        private const val PILL_REFRESH_MS = 150L
        private const val REOPEN_GUARD_MS = 800L
        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"

        private const val COLOR_IDLE = 0xDD1C1C1E.toInt()
        private const val COLOR_RECORDING = 0xDDEF4444.toInt()
        private const val COLOR_BUSY = 0xDD6B6B6B.toInt()
        private const val COLOR_RETRY = 0xDDD97706.toInt()
        private const val COLOR_FEEDBACK_BG = 0xEE1C1C1E.toInt()
        private const val COLOR_CONFIRM_BG = 0xEE991B1B.toInt()
        private const val COLOR_RING = 0xFFE8EAED.toInt()
    }

    private enum class State { IDLE, RECORDING, WORKING, RETRY }

    /**
     * How the feedback bubble is drawn: a notice that can wrap, a tappable undo
     * chip, or a red box that confirms something destructive when tapped.
     */
    private enum class Fb { NOTICE, ACTION, CONFIRM }

    private var state = State.IDLE
    /** When the last tap on the idle button lifted, for pairing two taps into a start. */
    private var lastIdleTapAt = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null
    private var recorder: Recorder? = null
    /** The live transcription stream for the current recording, in live mode. */
    private var live: LiveTranscription? = null
    /** Words typed into the field while talking (live mode), and what has been heard so far. */
    private var draft: TextInjector.LiveDraft? = null
    private var pendingDraft: TextInjector.LiveDraft? = null
    private val partial = StringBuilder()
    /** Field updates are batched: at most one every [DRAFT_INTERVAL_MS]. */
    private val draftTick = Runnable { draft?.update(partial.toString().trim()) }
    /** Audio of the last failed dictation, kept until it is sent or discarded. */
    private var pendingAudio: OpenAiClient.Audio? = null
    /** The on-disk copy of [pendingAudio], and of the recording being uploaded. */
    private lateinit var pending: PendingRecording

    private lateinit var settings: AppSettings
    private lateinit var secrets: SecretStore
    private lateinit var injector: TextInjector
    private lateinit var usage: UsageStore
    private val lockLimits = LockScreenLimits()

    /** Unlock resets the locked-recording count; locking mid-dictation caps the running recording. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> lockLimits.onUnlock()
                Intent.ACTION_SCREEN_OFF -> {
                    recorder?.let { it.capAt(lockLimits.capWhenLocked(it.elapsedSeconds)) }
                    hideTrack()
                }
            }
        }
    }

    private var overlayView: FrameLayout? = null
    private var button: ImageView? = null
    private var spinner: ProgressBar? = null
    private var retryBadge: ImageView? = null
    private var feedbackView: TextView? = null
    private var joystickView: JoystickView? = null
    private var joystickParams: WindowManager.LayoutParams? = null
    /** Set when the arrows keyboard was chosen by a long-press, so it stays until switched back. */
    private var pinnedArrows = false
    /** True while a finger is on the pad, so the keyboard is not swapped mid-push. */
    private var padTouched = false
    private val restoreKeyboard = Runnable {
        if (padTouched) scheduleKeyboardRestore() else restorePreviousKeyboard()
    }
    private lateinit var volume: VolumeController
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
            if (intent.data?.schemeSpecificPart == GlyphPillView.GLYPH_PACKAGE) refreshGlyphPill()
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
    private var undoArrowDrawable: Drawable? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var feedbackLayoutParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideFeedback = Runnable {
        feedbackLayoutParams?.let { setFeedbackTouchable(it, false) }
        feedbackView?.setOnClickListener(null)
        feedbackView?.isClickable = false
        feedbackView?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction {
            feedbackView?.visibility = View.GONE
        }?.start()
    }

    private val dp get() = resources.displayMetrics.density
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    override fun onServiceConnected() {
        instance = this
        settings = AppSettings(this)
        secrets = SecretStore(this)
        injector = TextInjector(this)
        usage = UsageStore(this)
        pending = PendingRecording(java.io.File(filesDir, "pending"))
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        volume = VolumeController(this)
        volume.resumeDndTimer()
        showOverlay()
        restorePendingRecording()
        refreshJoystick()
        refreshVolumePill()
        refreshGlyphPill()
        ContextCompat.registerReceiver(
            this, packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // If a crash or an update left the arrows keyboard selected, undo that now.
        restorePreviousKeyboard()
        // Android rebinds this service after a reboot or an app update, which is the
        // one moment the wake word can come back without you opening the app. If
        // Android refuses the start, a notification offers to turn it back on.
        if (settings.wakeWordEnabled && !WakeWordService.running) {
            WakeWordService.start(this, "accessibility service connected")
        }
    }

    // --- Arrow joystick ---

    /** Shows or hides the arrow pad to match the setting. Safe to call at any time. */
    fun refreshJoystick() {
        if (settings.joystickEnabled) showJoystick() else hideJoystick()
    }

    private fun showJoystick() {
        if (joystickView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
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
        val view = JoystickView(this).apply {
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

    private fun hideJoystick() {
        handler.removeCallbacks(restoreKeyboard)
        pinnedArrows = false
        restorePreviousKeyboard()
        val view = joystickView ?: return
        (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        joystickView = null
        joystickParams = null
    }

    /**
     * Arrow keys only reach other apps through a keyboard, so they go to
     * [KeysInputMethodService] when it is the selected one. Otherwise the caret
     * is moved through accessibility, which works in ordinary text fields but
     * not in a remote desktop session.
     */
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

    private fun restorePreviousKeyboard() {
        val previous = settings.previousIme.takeIf { it.isNotBlank() } ?: return
        if (currentKeyboardId() != arrowsKeyboardId()) return
        switchKeyboard(previous, remember = false)
        settings.previousIme = ""
    }

    private fun arrowsKeyboardId() =
        ComponentName(this, KeysInputMethodService::class.java).flattenToShortString()

    private fun currentKeyboardId(): String? =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)

    /** True when the switch was accepted. [remember] keeps the keyboard to come back to. */
    private fun switchKeyboard(target: String, remember: Boolean): Boolean {
        val current = currentKeyboardId()
        if (current == target) return true
        return try {
            val ok = softKeyboardController.switchToInputMethod(target)
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
            showFeedback("Keyboard switched back")
        }
    }

    private fun showKeyboardPicker() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showInputMethodPicker()
        showFeedback("Pick Voice Relay arrows in the keyboard list", 3000)
    }

    // --- Volume presets ---

    /** Shows or hides the volume pill to match the setting. Safe to call at any time. */
    fun refreshVolumePill() {
        if (settings.volumePillEnabled) showVolumePill() else hideVolumePill()
    }

    private fun showVolumePill() {
        if (pillView != null) return
        val bubble = layoutParams ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            (VolumePillView.W_DP * dp).toInt(), (VolumePillView.H_DP * dp).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        positionPill(params, bubble)
        val view = VolumePillView(this).apply {
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
        settleBubble()
        raiseBubble()
        ContextCompat.registerReceiver(
            this, volumeReceiver,
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
        try { unregisterReceiver(volumeReceiver) } catch (_: IllegalArgumentException) {}
        handler.removeCallbacks(refreshPill)
        (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        pillView = null
        pillParams = null
    }

    private fun showTrack() {
        if (trackView != null) return
        if (!volume.hasDndAccess) {
            showFeedback("Grant Do Not Disturb access in Voice Relay to use volume presets", 3000)
            return
        }
        val bubble = layoutParams ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            (VolumeTrackView.W_DP * dp).toInt(), (VolumeTrackView.H_DP * dp).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Outside touches are reported (to close the track) and still reach the app.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        positionTrack(params, bubble)
        val view = VolumeTrackView(this).apply {
            current = volume.current().preset?.ordinal ?: -1
            onTouch = { handler.removeCallbacks(closeTrack) }
            onCommit = { preset -> applyPreset(preset) }
            onTap = {
                showFeedback("Taps do nothing. Draw a V: dip, slide, rise.")
                scheduleTrackClose(TRACK_IDLE_MS)
            }
            onCancel = {
                showFeedback("Cancelled, nothing changed")
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

    private fun hideTrack() {
        handler.removeCallbacks(closeTrack)
        val view = trackView ?: return
        (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
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
            showFeedback("${preset.label} applied$note")
        }
        scheduleTrackClose(TRACK_CLOSE_AFTER_APPLY_MS)
    }

    /** Centered over the button, [PILL_GAP_DP] above it; the touch area reaches down to just short of it. */
    private fun positionPill(params: WindowManager.LayoutParams, bubble: WindowManager.LayoutParams) {
        val ring = (RING_DP * dp).toInt()
        val buttonTop = bubble.y + (ring - (BTN_DP * dp).toInt()) / 2
        val pillBottom = buttonTop - (PILL_GAP_DP * dp).toInt()
        params.x = bubble.x + (ring - params.width) / 2
        params.y = (pillBottom + (VolumePillView.BOTTOM_PAD_DP * dp).toInt() - params.height).coerceAtLeast(0)
    }

    /** Above the pill, flush with the button's outer edge so it opens toward the middle of the screen. */
    private fun positionTrack(params: WindowManager.LayoutParams, bubble: WindowManager.LayoutParams) {
        val ring = (RING_DP * dp).toInt()
        val button = (BTN_DP * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()
        val onRight = bubble.x + ring / 2 > screenW / 2
        val x = if (onRight) bubble.x + (ring + button) / 2 - params.width else bubble.x + (ring - button) / 2
        val buttonTop = bubble.y + (ring - button) / 2
        val pillTop = buttonTop - ((PILL_GAP_DP + VolumePillView.PILL_H_DP) * dp).toInt()
        params.x = x.coerceIn(margin, maxOf(margin, screenW - params.width - margin))
        params.y = (pillTop - (TRACK_GAP_DP * dp).toInt() - params.height).coerceAtLeast(margin)
    }

    // --- Glyph pill ---

    /** Shows the Glyph pill when the setting is on and Glyph is installed, else hides it. Safe to call at any time. */
    fun refreshGlyphPill() {
        if (settings.glyphPillEnabled && GlyphPillView.glyphInstalled(this)) showGlyphPill() else hideGlyphPill()
    }

    private fun showGlyphPill() {
        if (glyphView != null) return
        val bubble = layoutParams ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            (GlyphPillView.W_DP * dp).toInt(), (GlyphPillView.H_DP * dp).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        positionGlyphPill(params, bubble)
        val view = GlyphPillView(this).apply { onFire = { openGlyph() } }
        wm.addView(view, params)
        glyphView = view
        glyphParams = params
        settleBubble()
        raiseBubble()
    }

    private fun hideGlyphPill() {
        val view = glyphView ?: return
        (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        glyphView = null
        glyphParams = null
    }

    /** Opens Glyph's canvas in its own task, over whatever is on screen. */
    private fun openGlyph() {
        hideTrack()
        try {
            startActivity(GlyphPillView.openCanvasIntent())
            glyphView?.sparkle()
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "Glyph canvas not found", e)
            showFeedback("Glyph is not installed")
            refreshGlyphPill()
        }
    }

    /** Centered under the button, [PILL_GAP_DP] below it; the touch area reaches up to just short of it. */
    private fun positionGlyphPill(params: WindowManager.LayoutParams, bubble: WindowManager.LayoutParams) {
        val ring = (RING_DP * dp).toInt()
        val buttonBottom = bubble.y + (ring + (BTN_DP * dp).toInt()) / 2
        val pillTop = buttonBottom + (PILL_GAP_DP * dp).toInt()
        params.x = bubble.x + (ring - params.width) / 2
        params.y = (pillTop - (GlyphPillView.TOP_PAD_DP * dp).toInt()).coerceAtMost(maxOf(0, screenH - params.height))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::injector.isInitialized) return
        injector.onEvent(event)
        // Tapping into a text field means typing, so hand the keyboard back at
        // once instead of waiting for the idle timer.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED &&
            event.source?.isEditable == true && !pinnedArrows
        ) restorePreviousKeyboard()
    }
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        try { unregisterReceiver(screenReceiver) } catch (_: IllegalArgumentException) {}
        try { unregisterReceiver(packageReceiver) } catch (_: IllegalArgumentException) {}
        recorder?.stop()
        live?.cancel()
        scope.cancel()
        handler.removeCallbacks(restoreKeyboard)
        restorePreviousKeyboard()
        hideJoystick()
        hideVolumePill()
        hideGlyphPill()
        removeOverlay()
        super.onDestroy()
    }

    // --- State machine ---

    /**
     * Starting a recording takes a double tap, so a stray finger brushing the
     * button does nothing. Stopping, retrying and the long-press stay one touch.
     */
    private fun onTap(downAt: Long, upAt: Long) {
        when (state) {
            State.IDLE -> if (downAt - lastIdleTapAt <= ViewConfiguration.getDoubleTapTimeout()) {
                lastIdleTapAt = 0L
                startRecording()
            } else {
                lastIdleTapAt = upAt
            }
            State.RECORDING -> stopAndProcess()
            State.WORKING -> {}
            State.RETRY -> pendingAudio?.let { process(it, draft = pendingDraft) } ?: setState(State.IDLE)
        }
    }

    private fun onLongPress() {
        when (state) {
            State.IDLE -> fixFocusedText()
            State.RETRY -> {
                val length = pendingAudio?.let { " ${clock(it.seconds)}" } ?: ""
                showFeedback("Discard the$length recording? Tap here to discard it", CONFIRM_WINDOW_MS, Fb.CONFIRM) {
                    if (state == State.RETRY) discardPending()
                }
            }
            else -> {}
        }
    }

    private fun discardPending() {
        pendingAudio = null
        pendingDraft = null
        scope.launch(Dispatchers.IO) { pending.clear() }
        setState(State.IDLE)
        showFeedback("Recording discarded")
    }

    /** A recording that never got transcribed before the service stopped goes back to RETRY. */
    private fun restorePendingRecording() {
        scope.launch {
            val audio = withContext(Dispatchers.IO) { pending.load() } ?: return@launch
            if (state != State.IDLE) return@launch
            Log.i(TAG, "Restored a pending %.1f s recording".format(audio.seconds))
            pendingAudio = audio
            setState(State.RETRY)
            showFeedback("A ${clock(audio.seconds)} recording was not transcribed\nTap to retry, hold to discard", 6000)
        }
    }

    private fun clock(seconds: Double): String {
        val s = seconds.toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun startRecording() {
        if (secrets.getApiKey().isBlank()) {
            showFeedback("Set your OpenAI API key in Voice Relay", 3000)
            return
        }
        val locked = (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        val decision = lockLimits.onStart(locked, MAX_SECONDS)
        if (decision !is LockScreenLimits.Decision.Allow) {
            showFeedback("Unlock your phone to keep dictating (${lockLimits.maxRecordings} locked recordings used)", 3500)
            return
        }
        val session = if (settings.liveTranscription) {
            LiveTranscription(
                secrets.getApiKey(), settings.liveModel, settings.vocabulary, settings.languages,
                log = { Log.i(TAG, it) },
            ).also { it.start() }
        } else null
        val rec = Recorder(
            decision.maxSeconds,
            onLimit = { handler.post { if (state == State.RECORDING) stopAndProcess(hitLimit = true) } },
            onChunk = session?.let { s -> { chunk, n -> s.append(chunk, n) } },
        )
        try {
            rec.start()
        } catch (_: SecurityException) {
            session?.cancel()
            showFeedback("Grant microphone access in Voice Relay", 3000)
            return
        } catch (e: IllegalStateException) {
            session?.cancel()
            showFeedback(e.message ?: "Microphone unavailable", 3000)
            return
        }
        recorder = rec
        live = session
        setState(State.RECORDING)
        injector.rememberFocus()
        if (session != null) startLiveTyping(session)
        OpenAiClient(secrets.getApiKey()).prewarm()
        decision.remaining?.let { left ->
            showFeedback("Phone locked: stops after ${decision.maxSeconds / 60} min, $left more before you must unlock", 3000)
        }
    }

    /** Types recognized words into the focused field as they arrive. */
    private fun startLiveTyping(session: LiveTranscription) {
        partial.clear()
        pendingDraft = null
        draft = injector.beginDraft()
        Log.i(TAG, "Live typing: ${if (draft != null) "into the focused field" else "no editable field"}")
        session.onDelta = { piece ->
            handler.post {
                partial.append(piece)
                if (draft != null && !handler.hasCallbacks(draftTick)) handler.postDelayed(draftTick, DRAFT_INTERVAL_MS)
            }
        }
    }

    private fun stopAndProcess(hitLimit: Boolean = false) {
        val rec = recorder
        val pcm = rec?.stop() ?: ByteArray(0)
        recorder = null
        val session = live
        live = null
        val typed = draft
        draft = null
        handler.removeCallbacks(draftTick)
        // Everything heard so far goes into the field now, before the final text.
        typed?.update(partial.toString().trim())
        if (Recorder.durationSeconds(pcm) < MIN_CLIP_SECONDS) {
            typed?.update("")
            session?.cancel()
            injector.forgetSnapshot()
            setState(State.IDLE)
            showFeedback("Too short, nothing sent")
            return
        }
        if (hitLimit && rec != null) {
            showFeedback("Stopped at the ${rec.maxSeconds / 60} min limit, transcribing", 2500)
        }
        setState(State.WORKING)
        val seconds = Recorder.durationSeconds(pcm)
        Log.i(TAG, "Recorded %.1f s (%s)".format(seconds, if (session != null) "live" else "standard"))
        if (session != null) {
            process(OpenAiClient.Audio(ByteArray(0), "live-stream", "audio/pcm", seconds), session, pcm, typed)
        } else {
            scope.launch { process(withContext(Dispatchers.Default) { encode(pcm, seconds) }) }
        }
    }

    private fun encode(pcm: ByteArray, seconds: Double): OpenAiClient.Audio =
        AacEncoder.encode(pcm, Recorder.SAMPLE_RATE, cacheDir)?.let { OpenAiClient.Audio.m4a(it, seconds) }
            ?: OpenAiClient.Audio.wav(WavWriter.encode(pcm), seconds)

    /**
     * Transcribe and clean up [audio]. With a [session] (live mode) the transcript
     * comes from the live stream, and [pcm] is the fallback: if the live session
     * fails, the same recording goes through the standard upload instead, and it is
     * what a retry resends.
     */
    private fun process(
        audio: OpenAiClient.Audio,
        session: LiveTranscription? = null,
        pcm: ByteArray? = null,
        draft: TextInjector.LiveDraft? = null,
    ) {
        val apiKey = secrets.getApiKey()
        if (apiKey.isBlank()) {
            setState(State.IDLE)
            showFeedback("Set your OpenAI API key in Voice Relay", 3000)
            return
        }
        setState(State.WORKING)
        val client = OpenAiClient(apiKey)
        val standard = standardTranscriber(client)
        var uploaded: OpenAiClient.Audio? = null
        val transcriber = if (session == null || pcm == null) standard else DictationPipeline.Transcriber { clip ->
            val result = session.finish()
            if (result.error == null) {
                usage.recordTranscription(settings.liveModel, clip.seconds)
                result
            } else {
                Log.w(TAG, "Live transcription failed (${result.error}), using the standard upload")
                encode(pcm, clip.seconds).also { uploaded = it }.let { standard.transcribe(it) }
            }
        }
        val pipeline = buildPipeline(transcriber)
        work = scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                // Saved before the upload, so a crash mid-upload cannot lose it either.
                // Live mode keeps the raw audio in memory and saves it only on failure.
                if (session == null && audio !== pendingAudio) savePending(audio)
                pipeline.run(audio)
            }
            if (outcome !is DictationPipeline.Outcome.Failed) withContext(Dispatchers.IO) { pending.clear() }
            when (outcome) {
                is DictationPipeline.Outcome.Text -> {
                    pendingAudio = null
                    pendingDraft = null
                    setState(State.IDLE)
                    deliver(outcome.text, outcome.note, draft)
                }
                DictationPipeline.Outcome.NoSpeech -> {
                    pendingAudio = null
                    pendingDraft = null
                    draft?.update("")
                    setState(State.IDLE)
                    showFeedback("No speech detected")
                }
                is DictationPipeline.Outcome.Failed -> {
                    Log.w(TAG, "Transcription failed: ${outcome.error}")
                    val kept = if (pcm == null) audio
                        else uploaded ?: withContext(Dispatchers.Default) { encode(pcm, audio.seconds) }
                    if (pcm != null) withContext(Dispatchers.IO) { savePending(kept) }
                    pendingAudio = kept
                    pendingDraft = draft
                    setState(State.RETRY)
                    val typed = if ((draft?.typed ?: 0) > 0) "\nThe words so far are in the field." else ""
                    showFeedback("Failed: ${outcome.error.take(80)}$typed\nRecording saved. Tap to retry, hold to discard", 6000)
                }
            }
        }
    }

    /** A full disk must not stop the upload; the recording is still held in memory. */
    private fun savePending(audio: OpenAiClient.Audio) {
        try {
            pending.save(audio)
        } catch (e: Exception) {
            Log.w(TAG, "Could not save the recording for retry: ${e.message}")
        }
    }

    private fun standardTranscriber(client: OpenAiClient): DictationPipeline.Transcriber {
        val vocabulary = settings.vocabulary
        val languages = settings.languages
        val model = settings.transcriptionModel
        return DictationPipeline.Transcriber { audio ->
            client.transcribe(audio, model, vocabulary, languages).also {
                // A clean response is billed even when it heard no speech.
                if (it.error == null) usage.recordTranscription(model, audio.seconds)
            }
        }
    }

    private fun buildPipeline(transcriber: DictationPipeline.Transcriber): DictationPipeline {
        val cleaner = if (settings.cleanupEnabled) TextService(settings, secrets, usage).cleaner(settings.cleanupPrompt) else null
        return DictationPipeline(
            transcriber = transcriber,
            cleaner = cleaner,
            log = { Log.i(TAG, "Dictation: $it") },
        )
    }

    /**
     * Grammar fix for typed text: the selection in the focused field, or the
     * whole field when nothing is selected. Uses the cleanup service and model
     * with the typed-text prompt, regardless of whether dictation cleanup is on.
     */
    private fun fixFocusedText() {
        val service = TextService(settings, secrets, usage)
        service.missingKey()?.let {
            showFeedback(it, 3000)
            return
        }
        val range = injector.readFocusedRange()
        if (range == null) {
            showFeedback("Select text, or focus a field with text, to fix grammar", 3000)
            return
        }
        overlayView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        setState(State.WORKING)
        val cleaner = service.cleaner(settings.fixPrompt)
        val original = range.target
        work = scope.launch {
            val started = System.currentTimeMillis()
            val outcome = withContext(Dispatchers.IO) { DictationPipeline.fixTyped(original, cleaner) }
            Log.i(TAG, "Grammar fix: ${original.length} chars, ${System.currentTimeMillis() - started} ms")
            setState(State.IDLE)
            when (outcome) {
                is DictationPipeline.Outcome.Text -> when {
                    outcome.text == original -> showFeedback("Looks good already")
                    injector.replace(range, outcome.text) -> offerUndo("Grammar fixed")
                    else -> {
                        injector.copyToClipboard(outcome.text)
                        showFeedback("Text changed while fixing, fixed version copied to clipboard", 3000)
                    }
                }
                is DictationPipeline.Outcome.Failed -> showFeedback("Fix failed: ${outcome.error.take(80)}", 3000)
                DictationPipeline.Outcome.NoSpeech -> {}
            }
        }
    }

    private suspend fun deliver(text: String, note: String?, draft: TextInjector.LiveDraft? = null) {
        if (draft != null) {
            // Live typing: swap the partial words for the final text in place.
            if (draft.finish(text)) return offerUndo(note ?: "Inserted")
            if (draft.typed > 0) {
                // The field changed while dictating; inserting again would duplicate the words.
                injector.copyToClipboard(text)
                return showFeedback("The field changed while you were dictating, final text copied to clipboard", 3500)
            }
        }
        val message = when (injector.inject(text)) {
            TextInjector.Outcome.INSERTED -> return offerUndo(note ?: "Inserted")
            TextInjector.Outcome.PASTED -> note
            TextInjector.Outcome.COPIED_NO_FIELD -> "No text field focused, copied to clipboard"
            TextInjector.Outcome.COPIED_FAILED -> "Could not insert, copied to clipboard"
        }
        message?.let { showFeedback(it, 2500) }
    }

    /** Shows [message] as a chip you can tap to undo, for a few seconds after text lands. */
    private fun offerUndo(message: String) {
        if (!injector.canUndo) return showFeedback(message)
        showFeedback(message, UNDO_WINDOW_MS, Fb.ACTION) {
            when (injector.undo()) {
                TextInjector.UndoResult.UNDONE -> showFeedback("Undone")
                TextInjector.UndoResult.CHANGED_SINCE -> showFeedback("The text changed since, nothing undone", 2500)
                TextInjector.UndoResult.NOTHING -> {}
            }
        }
    }

    private fun setState(new: State) {
        state = new
        stopPulse()
        spinner?.visibility = if (new == State.WORKING) View.VISIBLE else View.GONE
        button?.background = circle(
            when (new) {
                State.IDLE -> COLOR_IDLE
                State.RECORDING -> COLOR_RECORDING
                State.WORKING -> COLOR_BUSY
                State.RETRY -> COLOR_RETRY
            }
        )
        retryBadge?.visibility = if (new == State.RETRY) View.VISIBLE else View.GONE
        button?.contentDescription = if (new == State.RETRY) "Retry transcription" else "Dictate"
        if (new == State.RECORDING) startPulse()
    }

    // --- Overlay ---

    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val buttonSize = (BTN_DP * dp).toInt()
        val ringSize = (RING_DP * dp).toInt()
        val pad = (PAD_DP * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()

        val ring = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(COLOR_RING)
            visibility = View.GONE
        }

        val img = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(pad, pad, pad, pad)
            background = circle(COLOR_IDLE)
            contentDescription = "Dictate"
        }

        // Sits over the lower right of the mic while a failed recording waits.
        val badge = ImageView(this).apply {
            setImageResource(R.drawable.ic_retry_badge)
            translationX = buttonSize * 0.22f
            translationY = buttonSize * 0.2f
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val badgeSize = (BADGE_DP * dp).toInt()

        val overlay = FrameLayout(this).apply {
            addView(ring, FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER))
            addView(img, FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.CENTER))
            addView(badge, FrameLayout.LayoutParams(badgeSize, badgeSize, Gravity.CENTER))
        }

        val params = WindowManager.LayoutParams(
            ringSize, ringSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - ringSize - margin
            y = screenH / 2 - ringSize / 2 - (BUTTON_LIFT_DP * dp).toInt()
        }

        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        var longPressed = false
        val longPress = Runnable { longPressed = true; onLongPress() }

        overlay.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = ev.rawX; touchY = ev.rawY
                    longPressed = false
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(ev.rawX - touchX) + abs(ev.rawY - touchY) >= TAP_THRESHOLD_DP * dp) {
                        handler.removeCallbacks(longPress)
                    }
                    val bounds = bubbleBounds()
                    params.x = bounds.clampX(startX + (ev.rawX - touchX).toInt())
                    params.y = bounds.clampY(startY + (ev.rawY - touchY).toInt())
                    wm.updateViewLayout(v, params)
                    followBubble(wm, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    val moved = abs(ev.rawX - touchX) + abs(ev.rawY - touchY)
                    if (moved < TAP_THRESHOLD_DP * dp) {
                        if (!longPressed) onTap(ev.downTime, ev.eventTime)
                    } else {
                        settleBubble()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    true
                }
                else -> false
            }
        }

        val feedback = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            maxWidth = (260 * dp).toInt()
            alpha = 0f
            visibility = View.GONE
        }
        applyFeedbackStyle(feedback, Fb.NOTICE)

        val feedbackParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        feedbackView = feedback
        positionFeedback(feedbackParams, params)

        wm.addView(overlay, params)
        wm.addView(feedback, feedbackParams)
        overlayView = overlay
        button = img
        spinner = ring
        retryBadge = badge
        layoutParams = params
        feedbackLayoutParams = feedbackParams
    }

    /** Rotation or a resize: put the button back against a side and inside the new screen. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.post { settleBubble() }
    }

    /**
     * The safe area, less the room the pills above and below the button need, so
     * none of them can be pushed against an edge and onto the button.
     */
    private fun bubbleBounds(): BubbleBounds {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        // Whole screen if the metrics are unavailable: a drag must never crash the service.
        val safe = runCatching {
            val metrics = wm.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            android.graphics.Rect(
                insets.left, insets.top,
                minOf(screenW, metrics.bounds.width() - insets.right),
                minOf(screenH, metrics.bounds.height() - insets.bottom),
            )
        }.getOrElse { android.graphics.Rect(0, 0, screenW, screenH) }
        val ring = (RING_DP * dp).toInt()
        val inset = (ring - (BTN_DP * dp).toInt()) / 2
        val above = if (pillView == null) 0
            else ((VolumePillView.H_DP - VolumePillView.BOTTOM_PAD_DP + PILL_GAP_DP) * dp).toInt() - inset
        val below = if (glyphView == null) 0
            else ((GlyphPillView.H_DP - GlyphPillView.TOP_PAD_DP + PILL_GAP_DP) * dp).toInt() - inset
        return BubbleBounds(
            left = safe.left, top = safe.top, right = safe.right, bottom = safe.bottom,
            ring = ring, above = maxOf(0, above), below = maxOf(0, below),
        )
    }

    /** Against the nearer side and inside [bubbleBounds], with everything riding along. */
    private fun settleBubble() {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val bounds = bubbleBounds()
        params.x = bounds.snapX(params.x, (MARGIN_DP * dp).toInt())
        params.y = bounds.clampY(params.y)
        wm.updateViewLayout(view, params)
        followBubble(wm, params)
    }

    /**
     * Re-adds the button so it is the top window of the stack. Windows added later
     * draw over earlier ones, so without this a pill added after the button would
     * take its touches wherever the two meet.
     */
    private fun raiseBubble() {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        wm.removeViewImmediate(view)
        wm.addView(view, params)
    }

    /** Keeps the feedback bubble, the volume pill and track, and the Glyph pill next to the button as it moves. */
    private fun followBubble(wm: WindowManager, bubble: WindowManager.LayoutParams) {
        feedbackLayoutParams?.let {
            positionFeedback(it, bubble)
            wm.updateViewLayout(feedbackView, it)
        }
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

    private fun removeOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView?.let { wm.removeView(it); overlayView = null }
        feedbackView?.let { wm.removeView(it); feedbackView = null }
        button = null
        spinner = null
        retryBadge = null
        layoutParams = null
        feedbackLayoutParams = null
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    private fun pill(color: Int, radiusDp: Float = FB_RADIUS_DP) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp * dp
        setColor(color)
    }

    /**
     * Puts the bubble beside the button, on whichever side has room, centered on
     * it. The bubble is measured first, so a short chip sits right next to the
     * button instead of starting at a fixed offset and running under it.
     */
    private fun positionFeedback(
        feedbackParams: WindowManager.LayoutParams,
        bubbleParams: WindowManager.LayoutParams
    ) {
        val view = feedbackView ?: return
        val margin = (MARGIN_DP * dp).toInt()
        val ring = (RING_DP * dp).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(screenW - 2 * margin, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val w = view.measuredWidth
        val h = view.measuredHeight
        val onRight = bubbleParams.x + ring / 2 > screenW / 2
        val x = if (onRight) bubbleParams.x - w - margin else bubbleParams.x + ring + margin
        feedbackParams.x = x.coerceIn(margin, maxOf(margin, screenW - w - margin))
        feedbackParams.y = (bubbleParams.y + (ring - h) / 2)
            .coerceIn(margin, maxOf(margin, screenH - h - margin))
    }

    /** Chip for a tappable offer, rounded box for a notice that can wrap. */
    private fun applyFeedbackStyle(view: TextView, style: Fb) {
        val action = style == Fb.ACTION
        val v = (8 * dp).toInt()
        val start = ((if (action) 14 else 12) * dp).toInt()
        val end = ((if (action) 12 else 12) * dp).toInt()
        view.setPadding(start, v, end, v)
        view.background = pill(
            if (style == Fb.CONFIRM) COLOR_CONFIRM_BG else COLOR_FEEDBACK_BG,
            if (action) FB_CHIP_RADIUS_DP else FB_RADIUS_DP,
        )
        view.maxLines = if (action) 1 else Int.MAX_VALUE
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(
            null, null, if (action) undoArrow() else null, null
        )
        view.compoundDrawablePadding = (10 * dp).toInt()
    }

    private fun undoArrow(): Drawable? =
        undoArrowDrawable ?: ContextCompat.getDrawable(this, R.drawable.ic_undo_chip)
            ?.also { undoArrowDrawable = it }

    /**
     * Shows a message next to the button. With [onClick] the bubble takes touches
     * until it hides; otherwise touches pass through it to the app underneath.
     * [Fb.ACTION] draws it as a one-line chip ending in an undo arrow, so the
     * offer reads as a button; notices keep the rounded box, which can wrap.
     */
    private fun showFeedback(
        text: String,
        durationMs: Long = 2000,
        style: Fb = Fb.NOTICE,
        onClick: (() -> Unit)? = null,
    ) {
        val view = feedbackView ?: return
        val bubbleParams = layoutParams ?: return
        val feedbackParams = feedbackLayoutParams ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        view.text = text
        applyFeedbackStyle(view, style)
        view.contentDescription = when (style) {
            Fb.ACTION -> "$text, tap to undo"
            Fb.CONFIRM -> "$text, tap to confirm"
            Fb.NOTICE -> null
        }
        setFeedbackTouchable(feedbackParams, onClick != null)
        view.setOnClickListener(onClick?.let { action ->
            View.OnClickListener {
                handler.removeCallbacks(hideFeedback)
                hideFeedback.run()
                action()
            }
        })
        view.isClickable = onClick != null
        positionFeedback(feedbackParams, bubbleParams)
        wm.updateViewLayout(view, feedbackParams)

        handler.removeCallbacks(hideFeedback)
        view.animate().cancel()
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(120).start()
        handler.postDelayed(hideFeedback, durationMs)
    }

    private fun setFeedbackTouchable(params: WindowManager.LayoutParams, touchable: Boolean) {
        val flags = if (touchable) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (flags == params.flags) return
        params.flags = flags
        feedbackView?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(it, params) }
    }

    private fun startPulse() {
        button?.let {
            it.animate().alpha(0.4f).setDuration(500).withEndAction {
                it.animate().alpha(1f).setDuration(500).withEndAction {
                    if (state == State.RECORDING) startPulse()
                }.start()
            }.start()
        }
    }

    private fun stopPulse() {
        button?.animate()?.cancel()
        button?.alpha = 1f
    }
}
