package com.lperezmo.voicerelay

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
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
import android.os.SystemClock
import android.widget.ImageView
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

        /** How faded the button and pills are while dimmed when idle. */
        private const val DIM_ALPHA = 0.45f
        private const val DIM_FADE_MS = 600L
        private const val WAKE_FADE_MS = 120L
        /** While something keeps the cluster awake, how often dimming is looked at again. */
        private const val DIM_RECHECK_MS = 1000L
        private const val COLOR_FEEDBACK_BG = 0xEE1C1C1E.toInt()
        private const val COLOR_CONFIRM_BG = 0xEE991B1B.toInt()
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
                    pills.hidePopups()
                }
            }
        }
    }

    private var overlayView: FrameLayout? = null
    /** What the ring around the button shows; [button] draws it. */
    private val ring = ButtonRing()
    private var button: ButtonRingView? = null
    private var retryBadge: ImageView? = null
    private var feedbackView: TextView? = null
    private lateinit var arrowPad: ArrowPad
    /** The volume and Glyph pills that ride with the button ([FloatingPills]). */
    private lateinit var pills: FloatingPills
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
        arrowPad = ArrowPad(this, settings, injector, handler) { text, ms -> showFeedback(text, ms) }
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
        pills = FloatingPills(
            this, settings, VolumeController(this).apply { resumeDndTimer() },
            // Android forgets alarms over a reboot or an update; this sets the timers' again.
            TimerController.get(this).apply { resume() }, handler,
            ringDp = RING_DP, buttonDp = BTN_DP, marginDp = MARGIN_DP,
            bubble = { layoutParams },
            buttonsHidden = { buttonsHidden },
            onPillAdded = { settleBubble(); raiseBubble(); refreshDim() },
            showFeedback = { text, ms -> showFeedback(text, ms) },
            onTimers = { next, done ->
                ring.timers(next, done, SystemClock.uptimeMillis())
                button?.refresh()
                refreshDim()
            },
            onActivity = { touched() },
        )
        showOverlay()
        machine.restorePending()
        refreshJoystick()
        refreshVolumePill()
        refreshGlyphPill()
        refreshTimerPill()
        ButtonsTileService.requestUpdate(this)
        pills.start()
        // If a crash or an update left the arrows keyboard selected, undo that now.
        arrowPad.restorePreviousKeyboard()
        // Android rebinds this service after a reboot or an app update, which is the
        // one moment the wake word can come back without you opening the app. If
        // Android refuses the start, a notification offers to turn it back on.
        if (settings.anyWakeEnabled && !WakeWordService.running) {
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

    private val voicePress = ClaudeVoicePress.forService(this, handler, { buttonsHidden }, ::setButtonsHidden)

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
            pills.hidePopups()
            arrowPad.hide()
            pills.hide()
            overlayView?.visibility = View.GONE
        } else {
            overlayView?.visibility = View.VISIBLE
            refreshJoystick()
            refreshVolumePill()
            refreshGlyphPill()
            refreshTimerPill()
            refreshDim()
        }
        Log.i(TAG, if (hide) "Buttons: hidden" else "Buttons: shown")
        ButtonsTileService.requestUpdate(this)
    }

    // --- Arrow joystick ---

    /** Shows or hides the arrow pad to match the setting. Safe to call at any time. */
    fun refreshJoystick() = arrowPad.refresh(buttonsHidden)

    // --- Volume and Glyph pills ---

    /** Shows or hides the volume pill to match the setting. Safe to call at any time. */
    fun refreshVolumePill() = pills.refreshVolume()

    /** Shows the Glyph pill when the setting is on and Glyph is installed, else hides it. Safe to call at any time. */
    fun refreshGlyphPill() = pills.refreshGlyph()

    /**
     * Shows or hides the timer pill to match the setting, or moves it after its position
     * setting changed; the button then settles with the room the pills need now.
     */
    fun refreshTimerPill() {
        pills.refreshTimer()
        settleBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::injector.isInitialized) return
        injector.onEvent(event)
        // Tapping into a text field means typing, so hand the keyboard back at
        // once instead of waiting for the idle timer.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED &&
            event.source?.isEditable == true && !arrowPad.pinnedArrows
        ) arrowPad.restorePreviousKeyboard()
    }
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        try { unregisterReceiver(screenReceiver) } catch (_: IllegalArgumentException) {}
        pills.stop()
        // A recording in progress is kept for a retry instead of thrown away.
        machine.destroy()
        voicePress.cancel()
        arrowPad.destroy()
        pills.hide()
        removeOverlay()
        handler.removeCallbacks(checkDim)
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

        override fun grammarFix(): DictationMachine.GrammarFix {
            val service = TextService(settings, secrets, usage)
            return object : DictationMachine.GrammarFix {
                override fun missingKey() = service.missingKey()
                override fun cleaner() = service.cleaner(settings.fixPrompt)
            }
        }

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
        override fun delivered() {
            ring.delivered(SystemClock.uptimeMillis())
            button?.refresh()
            refreshDim()
        }
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

    /** Draws [state] on the button: the ring shows it ([ButtonRing]), the badge marks a retry. */
    private fun showState(new: DictationMachine.State) {
        ring.dictation(
            when (new) {
                State.IDLE -> ButtonRing.Dictation.IDLE
                State.RECORDING -> ButtonRing.Dictation.RECORDING
                State.WORKING -> ButtonRing.Dictation.WORKING
                State.RETRY -> ButtonRing.Dictation.FAILED
            },
            SystemClock.uptimeMillis(),
        )
        button?.refresh()
        retryBadge?.visibility = if (new == State.RETRY) View.VISIBLE else View.GONE
        button?.contentDescription = when (new) {
            State.RETRY -> "Retry transcription"
            State.RECORDING -> "Dictating. Tap to stop."
            State.WORKING -> "Transcribing"
            State.IDLE -> "Dictate"
        }
        refreshDim()
    }

    // --- The ring: wake words and "hey timer" ---

    /** A wake word was heard: the ring shows which one. Main thread. */
    fun onWake(timer: Boolean) {
        ring.wake(timer, SystemClock.uptimeMillis())
        button?.refresh()
        refreshDim()
    }

    /** A step of a "hey timer" request: listening, transcribing, thinking, speaking. Main thread. */
    fun onTimerVoice(stage: ButtonRing.Voice) {
        ring.voice(stage, SystemClock.uptimeMillis())
        button?.refresh()
        refreshDim()
    }

    /** The "hey timer" request is over. Main thread. */
    fun onTimerVoiceDone() {
        ring.voiceDone(SystemClock.uptimeMillis())
        button?.refresh()
        refreshDim()
    }

    // --- Dim when idle ---

    private var dimmed = false
    private val checkDim = Runnable { refreshDim() }

    private fun touched() {
        ring.touched(SystemClock.uptimeMillis())
        refreshDim()
    }

    /**
     * Fades the button and pills when [AppSettings.dimWhenIdle] is on and nothing has
     * happened for a few seconds, and brings them back at once otherwise. Safe to call at any time.
     */
    fun refreshDim() {
        if (!::settings.isInitialized || !::pills.isInitialized || overlayView == null) return
        handler.removeCallbacks(checkDim)
        val now = SystemClock.uptimeMillis()
        val enabled = settings.dimWhenIdle && !buttonsHidden
        val dim = ring.dimmed(now, enabled, pills.popupsOpen())
        if (dim != dimmed) {
            dimmed = dim
            val alpha = if (dim) DIM_ALPHA else 1f
            val ms = if (dim) DIM_FADE_MS else WAKE_FADE_MS
            overlayView?.animate()?.alpha(alpha)?.setDuration(ms)?.start()
            pills.fade(alpha, ms)
        }
        if (enabled && !dim) handler.postDelayed(checkDim, ring.dimDueIn(now).coerceAtLeast(DIM_RECHECK_MS))
    }

    // --- Overlay ---

    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val buttonSize = (BTN_DP * dp).toInt()
        val ringSize = (RING_DP * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()

        val img = ButtonRingView(this, ring).apply {
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
                    touched()
                    pressButton(true)
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
                    pressButton(false)
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
                    pressButton(false)
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
        return BubbleBounds(
            left = safe.left, top = safe.top, right = safe.right, bottom = safe.bottom,
            ring = (RING_DP * dp).toInt(), above = pills.above(), below = pills.below(),
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
        pills.follow(wm, bubble)
    }

    private fun removeOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView?.let { wm.removeView(it); overlayView = null }
        feedbackView?.let { wm.removeView(it); feedbackView = null }
        button = null
        retryBadge = null
        layoutParams = null
        feedbackLayoutParams = null
    }

    /** A tap presses the button in a little: 96%, quick down and a touch slower back. */
    private fun pressButton(down: Boolean) {
        button?.animate()?.cancel()
        button?.animate()
            ?.scaleX(if (down) 0.96f else 1f)?.scaleY(if (down) 0.96f else 1f)
            ?.setDuration(if (down) 90L else 140L)?.start()
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
        // Past the timer pill when it sits beside the button, so the message never covers it.
        val side = pills.beside()
        val x = if (onRight) bubbleParams.x - side - w - margin else bubbleParams.x + ring + side + margin
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
}
