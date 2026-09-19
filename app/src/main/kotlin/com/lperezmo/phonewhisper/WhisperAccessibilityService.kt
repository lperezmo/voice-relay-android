package com.lperezmo.phonewhisper

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewConfiguration
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
 *   RETRY --tap--> WORKING (same audio)   RETRY --long-press--> IDLE (audio discarded)
 *   IDLE --long-press--> WORKING (grammar fix of the selection or focused field) --> IDLE
 *
 * While the phone is locked, recordings are capped by [LockScreenLimits]. After
 * text lands in a field, the feedback bubble offers undo for a few seconds.
 *
 * The overlay is a TYPE_ACCESSIBILITY_OVERLAY window, and the service only
 * reads the screen when inserting text (see [TextInjector]).
 */
class WhisperAccessibilityService : AccessibilityService() {

    companion object {
        var instance: WhisperAccessibilityService? = null
        private const val TAG = "PhoneWhisper"
        private const val BTN_DP = 44
        private const val PAD_DP = 10
        private const val MARGIN_DP = 8
        private const val TAP_THRESHOLD_DP = 10
        private const val RING_DP = 56
        private const val FEEDBACK_OFFSET_DP = 64
        private const val MIN_CLIP_SECONDS = 0.3
        private const val MAX_SECONDS = 600
        private const val UNDO_WINDOW_MS = 5000L

        private const val COLOR_IDLE = 0xDD1C1C1E.toInt()
        private const val COLOR_RECORDING = 0xDDEF4444.toInt()
        private const val COLOR_BUSY = 0xDD6B6B6B.toInt()
        private const val COLOR_RETRY = 0xDDD97706.toInt()
        private const val COLOR_FEEDBACK_BG = 0xEE1C1C1E.toInt()
        private const val COLOR_RING = 0xFFE8EAED.toInt()
    }

    private enum class State { IDLE, RECORDING, WORKING, RETRY }

    private var state = State.IDLE
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null
    private var recorder: Recorder? = null
    /** The live transcription stream for the current recording, in live mode. */
    private var live: LiveTranscription? = null
    /** Audio of the last failed dictation, kept until it is sent or discarded. */
    private var pendingAudio: OpenAiClient.Audio? = null

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
                Intent.ACTION_SCREEN_OFF -> recorder?.let { it.capAt(lockLimits.capWhenLocked(it.elapsedSeconds)) }
            }
        }
    }

    private var overlayView: FrameLayout? = null
    private var button: ImageView? = null
    private var spinner: ProgressBar? = null
    private var feedbackView: TextView? = null
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
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        showOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null && ::injector.isInitialized) injector.onEvent(event)
    }
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        try { unregisterReceiver(screenReceiver) } catch (_: IllegalArgumentException) {}
        recorder?.stop()
        live?.cancel()
        scope.cancel()
        removeOverlay()
        super.onDestroy()
    }

    // --- State machine ---

    private fun onTap() {
        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopAndProcess()
            State.WORKING -> {}
            State.RETRY -> pendingAudio?.let { process(it) } ?: setState(State.IDLE)
        }
    }

    private fun onLongPress() {
        when (state) {
            State.IDLE -> fixFocusedText()
            State.RETRY -> {
                pendingAudio = null
                setState(State.IDLE)
                showFeedback("Recording discarded")
            }
            else -> {}
        }
    }

    private fun startRecording() {
        if (secrets.getApiKey().isBlank()) {
            showFeedback("Set your OpenAI API key in Whisper LP", 3000)
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
            showFeedback("Grant microphone access in Whisper LP", 3000)
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
        OpenAiClient(secrets.getApiKey()).prewarm()
        decision.remaining?.let { left ->
            showFeedback("Phone locked: stops after ${decision.maxSeconds / 60} min, $left more before you must unlock", 3000)
        }
    }

    private fun stopAndProcess(hitLimit: Boolean = false) {
        val rec = recorder
        val pcm = rec?.stop() ?: ByteArray(0)
        recorder = null
        val session = live
        live = null
        if (Recorder.durationSeconds(pcm) < MIN_CLIP_SECONDS) {
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
            process(OpenAiClient.Audio(ByteArray(0), "live-stream", "audio/pcm", seconds), session, pcm)
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
    private fun process(audio: OpenAiClient.Audio, session: LiveTranscription? = null, pcm: ByteArray? = null) {
        val apiKey = secrets.getApiKey()
        if (apiKey.isBlank()) {
            setState(State.IDLE)
            showFeedback("Set your OpenAI API key in Whisper LP", 3000)
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
        val pipeline = buildPipeline(client, transcriber)
        work = scope.launch {
            val outcome = withContext(Dispatchers.IO) { pipeline.run(audio) }
            when (outcome) {
                is DictationPipeline.Outcome.Text -> {
                    pendingAudio = null
                    setState(State.IDLE)
                    deliver(outcome.text, outcome.note)
                }
                DictationPipeline.Outcome.NoSpeech -> {
                    pendingAudio = null
                    setState(State.IDLE)
                    showFeedback("No speech detected")
                }
                is DictationPipeline.Outcome.Failed -> {
                    Log.w(TAG, "Transcription failed: ${outcome.error}")
                    pendingAudio = if (pcm == null) audio
                        else uploaded ?: withContext(Dispatchers.Default) { encode(pcm, audio.seconds) }
                    setState(State.RETRY)
                    showFeedback("Failed: ${outcome.error.take(80)}\nTap to retry, hold to discard", 5000)
                }
            }
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

    private fun buildPipeline(client: OpenAiClient, transcriber: DictationPipeline.Transcriber): DictationPipeline {
        val cleaner = if (settings.cleanupEnabled) {
            val model = settings.cleanupModel
            val instructions = Prompts.forRequest(settings.cleanupPrompt, settings.vocabulary)
            DictationPipeline.Cleaner { text ->
                client.cleanup(text, model, instructions).also { if (it.ok) usage.recordText(model, it.inputTokens, it.outputTokens) }
            }
        } else null
        return DictationPipeline(
            transcriber = transcriber,
            cleaner = cleaner,
            log = { Log.i(TAG, "Dictation: $it") },
        )
    }

    /**
     * Grammar fix for typed text: the selection in the focused field, or the
     * whole field when nothing is selected. Uses the cleanup model with the
     * typed-text prompt, regardless of whether dictation cleanup is on.
     */
    private fun fixFocusedText() {
        val apiKey = secrets.getApiKey()
        if (apiKey.isBlank()) {
            showFeedback("Set your OpenAI API key in Whisper LP", 3000)
            return
        }
        val range = injector.readFocusedRange()
        if (range == null) {
            showFeedback("Select text, or focus a field with text, to fix grammar", 3000)
            return
        }
        overlayView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        setState(State.WORKING)
        val client = OpenAiClient(apiKey)
        val model = settings.cleanupModel
        val instructions = Prompts.forRequest(Prompts.TYPED, settings.vocabulary)
        val original = range.target
        work = scope.launch {
            val started = System.currentTimeMillis()
            val outcome = withContext(Dispatchers.IO) {
                DictationPipeline.fixTyped(original) { text ->
                    client.cleanup(text, model, instructions).also { if (it.ok) usage.recordText(model, it.inputTokens, it.outputTokens) }
                }
            }
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

    private suspend fun deliver(text: String, note: String?) {
        val message = when (injector.inject(text)) {
            TextInjector.Outcome.INSERTED -> return offerUndo(note ?: "Inserted")
            TextInjector.Outcome.PASTED -> note
            TextInjector.Outcome.COPIED_NO_FIELD -> "No text field focused, copied to clipboard"
            TextInjector.Outcome.COPIED_FAILED -> "Could not insert, copied to clipboard"
        }
        message?.let { showFeedback(it, 2500) }
    }

    /** Shows [message] with a tap-to-undo for a few seconds after text lands in a field. */
    private fun offerUndo(message: String) {
        if (!injector.canUndo) return showFeedback(message)
        showFeedback("$message. Tap here to undo", UNDO_WINDOW_MS) {
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

        val overlay = FrameLayout(this).apply {
            addView(ring, FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER))
            addView(img, FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.CENTER))
        }

        val params = WindowManager.LayoutParams(
            ringSize, ringSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - ringSize - margin
            y = screenH / 2 - ringSize / 2
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
                    params.x = startX + (ev.rawX - touchX).toInt()
                    params.y = startY + (ev.rawY - touchY).toInt()
                    wm.updateViewLayout(v, params)
                    feedbackLayoutParams?.let {
                        positionFeedback(it, params)
                        wm.updateViewLayout(feedbackView, it)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    val moved = abs(ev.rawX - touchX) + abs(ev.rawY - touchY)
                    if (moved < TAP_THRESHOLD_DP * dp) {
                        if (!longPressed) onTap()
                    } else {
                        params.x = if (params.x + ringSize / 2 > screenW / 2)
                            screenW - ringSize - margin else margin
                        wm.updateViewLayout(v, params)
                        feedbackLayoutParams?.let {
                            positionFeedback(it, params)
                            wm.updateViewLayout(feedbackView, it)
                        }
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
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            background = pill(COLOR_FEEDBACK_BG)
            maxWidth = (260 * dp).toInt()
            alpha = 0f
            visibility = View.GONE
        }

        val feedbackParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        positionFeedback(feedbackParams, params)

        wm.addView(overlay, params)
        wm.addView(feedback, feedbackParams)
        overlayView = overlay
        button = img
        spinner = ring
        feedbackView = feedback
        layoutParams = params
        feedbackLayoutParams = feedbackParams
    }

    private fun removeOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView?.let { wm.removeView(it); overlayView = null }
        feedbackView?.let { wm.removeView(it); feedbackView = null }
        button = null
        spinner = null
        layoutParams = null
        feedbackLayoutParams = null
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 16 * dp
        setColor(color)
    }

    private fun positionFeedback(
        feedbackParams: WindowManager.LayoutParams,
        bubbleParams: WindowManager.LayoutParams
    ) {
        val margin = (MARGIN_DP * dp).toInt()
        val offset = (FEEDBACK_OFFSET_DP * dp).toInt()
        feedbackParams.x = maxOf(margin, bubbleParams.x - offset)
        feedbackParams.y = maxOf(margin, bubbleParams.y - margin)
    }

    /**
     * Shows a message next to the button. With [onClick] the bubble takes touches
     * until it hides; otherwise touches pass through it to the app underneath.
     */
    private fun showFeedback(text: String, durationMs: Long = 2000, onClick: (() -> Unit)? = null) {
        val view = feedbackView ?: return
        val bubbleParams = layoutParams ?: return
        val feedbackParams = feedbackLayoutParams ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        view.text = text
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
