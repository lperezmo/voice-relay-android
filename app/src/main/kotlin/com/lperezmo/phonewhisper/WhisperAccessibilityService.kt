package com.lperezmo.phonewhisper

import android.accessibilityservice.AccessibilityService
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
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
    /** Audio of the last failed dictation, kept until it is sent or discarded. */
    private var pendingWav: ByteArray? = null

    private lateinit var settings: AppSettings
    private lateinit var secrets: SecretStore
    private lateinit var injector: TextInjector

    private var overlayView: FrameLayout? = null
    private var button: ImageView? = null
    private var spinner: ProgressBar? = null
    private var feedbackView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var feedbackLayoutParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideFeedback = Runnable {
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
        showOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        recorder?.stop()
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
            State.RETRY -> pendingWav?.let { process(it) } ?: setState(State.IDLE)
        }
    }

    private fun onLongPress() {
        if (state == State.RETRY) {
            pendingWav = null
            setState(State.IDLE)
            showFeedback("Recording discarded")
        }
    }

    private fun startRecording() {
        if (secrets.getApiKey().isBlank()) {
            showFeedback("Set your OpenAI API key in Phone Whisper", 3000)
            return
        }
        val rec = Recorder(onLimit = { handler.post { if (state == State.RECORDING) stopAndProcess() } })
        try {
            rec.start()
        } catch (_: SecurityException) {
            showFeedback("Grant microphone access in Phone Whisper", 3000)
            return
        } catch (e: IllegalStateException) {
            showFeedback(e.message ?: "Microphone unavailable", 3000)
            return
        }
        recorder = rec
        setState(State.RECORDING)
    }

    private fun stopAndProcess() {
        val pcm = recorder?.stop() ?: ByteArray(0)
        recorder = null
        if (Recorder.durationSeconds(pcm) < MIN_CLIP_SECONDS) {
            setState(State.IDLE)
            showFeedback("Too short, nothing sent")
            return
        }
        process(WavWriter.encode(pcm))
    }

    private fun process(wav: ByteArray) {
        val apiKey = secrets.getApiKey()
        if (apiKey.isBlank()) {
            setState(State.IDLE)
            showFeedback("Set your OpenAI API key in Phone Whisper", 3000)
            return
        }
        setState(State.WORKING)
        val pipeline = buildPipeline(OpenAiClient(apiKey))
        work = scope.launch {
            val outcome = withContext(Dispatchers.IO) { pipeline.run(wav) }
            when (outcome) {
                is DictationPipeline.Outcome.Text -> {
                    pendingWav = null
                    setState(State.IDLE)
                    deliver(outcome.text, outcome.note)
                }
                DictationPipeline.Outcome.NoSpeech -> {
                    pendingWav = null
                    setState(State.IDLE)
                    showFeedback("No speech detected")
                }
                is DictationPipeline.Outcome.Failed -> {
                    Log.w(TAG, "Transcription failed: ${outcome.error}")
                    pendingWav = wav
                    setState(State.RETRY)
                    showFeedback("Failed: ${outcome.error.take(80)}\nTap to retry, hold to discard", 5000)
                }
            }
        }
    }

    private fun buildPipeline(client: OpenAiClient): DictationPipeline {
        val vocabulary = settings.vocabulary
        val languages = settings.languages
        val transcriptionModel = settings.transcriptionModel
        val cleaner = if (settings.cleanupEnabled) {
            val model = settings.cleanupModel
            val instructions = Prompts.forRequest(settings.cleanupPrompt, vocabulary)
            DictationPipeline.Cleaner { client.cleanup(it, model, instructions) }
        } else null
        return DictationPipeline(
            transcriber = { client.transcribe(it, transcriptionModel, vocabulary, languages) },
            cleaner = cleaner,
        )
    }

    private fun deliver(text: String, note: String?) {
        val message = when (injector.inject(text)) {
            TextInjector.Outcome.INSERTED, TextInjector.Outcome.PASTED -> note
            TextInjector.Outcome.COPIED_NO_FIELD -> "No text field focused, copied to clipboard"
            TextInjector.Outcome.COPIED_FAILED -> "Could not insert, copied to clipboard"
        }
        message?.let { showFeedback(it, 2500) }
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

    private fun showFeedback(text: String, durationMs: Long = 2000) {
        val view = feedbackView ?: return
        val bubbleParams = layoutParams ?: return
        val feedbackParams = feedbackLayoutParams ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        view.text = text
        positionFeedback(feedbackParams, bubbleParams)
        wm.updateViewLayout(view, feedbackParams)

        handler.removeCallbacks(hideFeedback)
        view.animate().cancel()
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(120).start()
        handler.postDelayed(hideFeedback, durationMs)
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
