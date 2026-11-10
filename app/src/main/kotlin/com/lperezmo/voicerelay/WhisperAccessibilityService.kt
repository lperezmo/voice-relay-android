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
import com.lperezmo.voicerelay.DictationMachine.Fb
import com.lperezmo.voicerelay.DictationMachine.State
import kotlin.math.abs

/**
 * Hosts the floating dictation button. The dictation itself (idle, recording,
 * working, retry) is [DictationMachine]; this service gives it the microphone,
 * the network, the text field and the button through [DictationMachine.Effects].
 * The recording kept for a retry lives in [PendingRecording].
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
        /** How far above the middle the button starts. The right edge at mid-height is
         *  where chat apps put their send and voice buttons (an empty Claude chat centres
         *  its composer there), so the whole stack, the pill under the button included,
         *  has to end well above the middle. */
        private const val BUTTON_LIFT_DP = 200
        private const val FB_RADIUS_DP = 16f
        private const val FB_CHIP_RADIUS_DP = 18f
        private const val BADGE_DP = 20
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

    /** The on-disk copy of the recording waiting for a retry, and of the recording being uploaded. */
    private lateinit var pending: PendingRecording

    private lateinit var settings: AppSettings
    private lateinit var secrets: SecretStore
    private lateinit var injector: TextInjector
    private lateinit var usage: UsageStore

    private val machine = DictationMachine(DictationEffects(), ViewConfiguration.getDoubleTapTimeout().toLong())

    /** Unlock resets the locked-recording count; locking mid-dictation caps the running recording. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> machine.onUnlock()
                Intent.ACTION_SCREEN_OFF -> {
                    machine.onScreenOff()
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
        machine.restorePending()
        refreshJoystick()
        refreshVolumePill()
        refreshGlyphPill()
        ButtonsTileService.requestUpdate(this)
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

    // --- Hide all buttons (screen recording) ---

    /**
     * True while every floating button is hidden, from the Quick Settings tile or a
     * Tasker intent. Not saved: a restart of the service brings the buttons back.
     */
    var buttonsHidden = false
        private set

    private val voicePress = ClaudeVoicePress(this, handler, { buttonsHidden }, ::setButtonsHidden)

    /** After the wake word opens Claude on a new chat: presses its voice control ([ClaudeVoicePress]). */
    fun tapClaudeVoiceMode() = voicePress.start()

    /** Hides or shows the bubble and every pill at once. A running recording is finished first. */
    fun setButtonsHidden(hide: Boolean) {
        if (hide == buttonsHidden) return
        if (hide) machine.finishRecording()
        buttonsHidden = hide
        if (hide) {
            handler.removeCallbacks(hideFeedback)
            feedbackView?.visibility = View.GONE
            hideTrack()
            hideJoystick()
            hideVolumePill()
            hideGlyphPill()
            overlayView?.visibility = View.GONE
        } else {
            overlayView?.visibility = View.VISIBLE
            refreshJoystick()
            refreshVolumePill()
            refreshGlyphPill()
        }
        Log.i(TAG, if (hide) "Buttons: hidden" else "Buttons: shown")
        ButtonsTileService.requestUpdate(this)
    }

    // --- Arrow joystick ---

    /** Shows or hides the arrow pad to match the setting. Safe to call at any time. */
    fun refreshJoystick() {
        if (settings.joystickEnabled && !buttonsHidden) showJoystick() else hideJoystick()
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
        if (settings.volumePillEnabled && !buttonsHidden) showVolumePill() else hideVolumePill()
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
        if (settings.glyphPillEnabled && !buttonsHidden && GlyphPillView.glyphInstalled(this)) showGlyphPill() else hideGlyphPill()
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
        // A recording in progress is kept for a retry instead of thrown away.
        machine.destroy()
        handler.removeCallbacks(restoreKeyboard)
        restorePreviousKeyboard()
        hideJoystick()
        hideVolumePill()
        hideGlyphPill()
        removeOverlay()
        ButtonsTileService.requestUpdate(this)
        super.onDestroy()
    }

    // --- Dictation ---

    /** What [DictationMachine] does to the phone: microphone, network, text field and button. */
    private inner class DictationEffects : DictationMachine.Effects {
        override fun apiKey() = secrets.getApiKey()
        override fun isLocked() = (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        override fun liveTranscriptionEnabled() = settings.liveTranscription

        override fun startLiveSession(apiKey: String): DictationMachine.LiveSession {
            val session = LiveTranscription(
                apiKey, settings.liveModel, settings.vocabulary, settings.languages,
                log = { Log.i(TAG, it) },
            ).also { it.start() }
            return object : DictationMachine.LiveSession {
                override var onDelta: ((String) -> Unit)?
                    get() = session.onDelta
                    set(value) { session.onDelta = value }
                override fun append(chunk: ByteArray, length: Int) = session.append(chunk, length)
                override fun finish() = session.finish()
                override fun cancel() = session.cancel()
            }
        }

        override fun newMic(
            maxSeconds: Int,
            onLimit: () -> Unit,
            onChunk: ((ByteArray, Int) -> Unit)?,
            onError: () -> Unit,
        ): DictationMachine.Mic {
            val rec = Recorder(maxSeconds, onLimit = onLimit, onChunk = onChunk, onError = onError)
            return object : DictationMachine.Mic {
                override fun start() = rec.start()
                override fun stop() = rec.stop()
                override val elapsedSeconds get() = rec.elapsedSeconds
                override val maxSeconds get() = rec.maxSeconds
                override fun capAt(seconds: Int) = rec.capAt(seconds)
            }
        }

        override fun prewarm(apiKey: String) = OpenAiClient(apiKey).prewarm()

        override fun encode(pcm: ByteArray, seconds: Double): OpenAiClient.Audio =
            AacEncoder.encode(pcm, Recorder.SAMPLE_RATE, cacheDir)?.let { OpenAiClient.Audio.m4a(it, seconds) }
                ?: OpenAiClient.Audio.wav(WavWriter.encode(pcm), seconds)

        override fun savePending(audio: OpenAiClient.Audio) = pending.save(audio)
        override fun loadPending() = pending.load()
        override fun clearPending() = pending.clear()

        override fun standardTranscriber(apiKey: String): DictationPipeline.Transcriber {
            val client = OpenAiClient(apiKey)
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

        override fun recordLiveUsage(seconds: Double) = usage.recordTranscription(settings.liveModel, seconds)

        override fun dictationCleaner() =
            if (settings.cleanupEnabled) TextService(settings, secrets, usage).cleaner(settings.cleanupPrompt) else null

        override fun grammarFixMissingKey() = TextService(settings, secrets, usage).missingKey()
        override fun grammarFixCleaner() = TextService(settings, secrets, usage).cleaner(settings.fixPrompt)

        override fun rememberFocus() = injector.rememberFocus()
        override fun forgetSnapshot() = injector.forgetSnapshot()

        override fun beginDraft(): DictationMachine.Draft? {
            val draft = injector.beginDraft() ?: return null
            return object : DictationMachine.Draft {
                override val typed get() = draft.typed
                override fun update(text: String) = draft.update(text)
                override fun finish(text: String) = draft.finish(text)
            }
        }

        override fun readFixTarget(): DictationMachine.FixTarget? {
            val range = injector.readFocusedRange() ?: return null
            return object : DictationMachine.FixTarget {
                override val text get() = range.target
                override fun replace(replacement: String) = injector.replace(range, replacement)
            }
        }

        override suspend fun inject(text: String) = injector.inject(text)
        override fun copyToClipboard(text: String) = injector.copyToClipboard(text)
        override val canUndo get() = injector.canUndo
        override fun undo() = injector.undo()

        override fun showState(state: DictationMachine.State) = this@WhisperAccessibilityService.showState(state)
        override fun showFeedback(text: String, durationMs: Long, style: Fb, onClick: (() -> Unit)?) =
            this@WhisperAccessibilityService.showFeedback(text, durationMs, style, onClick)
        override fun haptic() {
            overlayView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        override fun post(block: Runnable) {
            handler.post(block)
        }
        override fun postDelayed(block: Runnable, delayMs: Long) {
            handler.postDelayed(block, delayMs)
        }
        override fun removeCallbacks(block: Runnable) = handler.removeCallbacks(block)
        override fun hasCallbacks(block: Runnable) = handler.hasCallbacks(block)

        override fun logInfo(message: String) {
            Log.i(TAG, message)
        }
        override fun logWarn(message: String) {
            Log.w(TAG, message)
        }
        override fun logError(message: String) {
            Log.e(TAG, message)
        }
    }

    /** Draws [state] on the button. */
    private fun showState(new: DictationMachine.State) {
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
            setImageResource(R.drawable.ic_voice_mark)
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
            // Where it was last dragged to, if anywhere. The pills are added later and
            // settle it again with their room.
            if (!placeAtSavedPosition(this)) {
                x = screenW - ringSize - margin
                y = screenH / 2 - ringSize / 2 - (BUTTON_LIFT_DP * dp).toInt()
            }
        }

        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        var longPressed = false
        val longPress = Runnable { longPressed = true; machine.onLongPress() }

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
                        if (!longPressed) machine.onTap(ev.downTime, ev.eventTime)
                    } else {
                        settleBubble()
                        saveBubblePosition()
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

    /**
     * Rotation or a resize: put the button back at the side and share of the height
     * it was dragged to (or just inside the new screen), with everything riding along.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.post {
            layoutParams?.let { placeAtSavedPosition(it) }
            settleBubble()
        }
    }

    /** Puts [params] where the button was last dragged to, inside this screen. False if it never was. */
    private fun placeAtSavedPosition(params: WindowManager.LayoutParams): Boolean {
        val fraction = settings.bubbleYFraction
        if (fraction < 0f) return false
        val bounds = bubbleBounds()
        params.x = bounds.sideX(settings.bubbleOnRight, (MARGIN_DP * dp).toInt())
        params.y = bounds.clampY(BubbleBounds.yAt(fraction, (RING_DP * dp).toInt(), screenH))
        return true
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

    /** Remembers the side and height the button was dragged to, for the next start of the service. */
    private fun saveBubblePosition() {
        val params = layoutParams ?: return
        val onRight = bubbleBounds().isRight(params.x)
        val fraction = BubbleBounds.fraction(params.y, (RING_DP * dp).toInt(), screenH)
        settings.saveBubble(onRight, fraction)
        Log.i(TAG, "Buttons: dictate button left on the ${if (onRight) "right" else "left"} at %.2f of the height".format(fraction))
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
        if (buttonsHidden) return
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
                    if (machine.state == State.RECORDING) startPulse()
                }.start()
            }.start()
        }
    }

    private fun stopPulse() {
        button?.animate()?.cancel()
        button?.alpha = 1f
    }
}
