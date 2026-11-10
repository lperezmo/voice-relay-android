package com.lperezmo.voicerelay

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs one dictation at a time for the floating button:
 *
 *   IDLE --tap--> RECORDING --tap--> WORKING --text--> IDLE
 *                                        \--network error--> RETRY
 *   RETRY --tap--> WORKING (same audio)   RETRY --long-press, then tap "Discard"--> IDLE
 *   IDLE --long-press--> WORKING (grammar fix of the selection or focused field) --> IDLE
 *
 * A recording is saved to disk (pending) before it is uploaded and removed once
 * it is transcribed, so a failed one comes back in RETRY even after the service
 * restarts (an app update, a crash, a reboot).
 *
 * While the phone is locked, recordings are capped by [LockScreenLimits]. After
 * text lands in a field, the feedback bubble offers undo for a few seconds.
 *
 * Holds no Android types: the service supplies the microphone, the network, the
 * text field and the button through [Effects], and the main thread through
 * [Effects.post] and the [main] dispatcher. Every event and callback runs on the
 * main thread.
 */
class DictationMachine(
    private val fx: Effects,
    /** Two taps on the idle button whose gap is at most this long start a recording. */
    private val doubleTapTimeoutMs: Long,
    main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
    private val lockLimits: LockScreenLimits = LockScreenLimits(),
) {
    enum class State { IDLE, RECORDING, WORKING, RETRY }

    /**
     * How the feedback bubble is drawn: a notice that can wrap, a tappable undo
     * chip, or a red box that confirms something destructive when tapped.
     */
    enum class Fb { NOTICE, ACTION, CONFIRM }

    /** The microphone ([Recorder]). Its callbacks fire on the capture thread. */
    interface Mic {
        /** Throws SecurityException without the permission, IllegalStateException if the mic is unavailable. */
        fun start()
        fun stop(): ByteArray
        val elapsedSeconds: Double
        val maxSeconds: Int
        fun capAt(seconds: Int)
    }

    /** A live transcription stream ([LiveTranscription]), already started. */
    interface LiveSession {
        var onDelta: ((String) -> Unit)?
        fun append(chunk: ByteArray, length: Int)
        /** Blocking. */
        fun finish(): OpenAiClient.Result
        fun cancel()
    }

    /** Words typed into the field while talking ([TextInjector.LiveDraft]). */
    interface Draft {
        val typed: Int
        fun update(text: String): Boolean
        fun finish(text: String): Boolean
    }

    /** The text a grammar fix applies to ([TextInjector.FieldRange]). */
    interface FixTarget {
        val text: String
        fun replace(replacement: String): Boolean
    }

    interface Effects {
        // Settings and keys. Read at the moment the machine needs them.
        fun apiKey(): String
        fun isLocked(): Boolean
        fun liveTranscriptionEnabled(): Boolean

        // Audio.
        fun startLiveSession(apiKey: String): LiveSession
        fun newMic(maxSeconds: Int, onLimit: () -> Unit, onChunk: ((ByteArray, Int) -> Unit)?, onError: () -> Unit): Mic
        fun prewarm(apiKey: String)
        /** AAC, or WAV when the encoder fails. Blocking. */
        fun encode(pcm: ByteArray, seconds: Double): OpenAiClient.Audio

        // The recording kept on disk for a retry. Blocking.
        fun savePending(audio: OpenAiClient.Audio)
        fun loadPending(): OpenAiClient.Audio?
        fun clearPending()

        // Requests. The transcriber counts its own usage.
        fun standardTranscriber(apiKey: String): DictationPipeline.Transcriber
        fun recordLiveUsage(seconds: Double)
        /** The dictation cleanup, or null when it is off. */
        fun dictationCleaner(): DictationPipeline.Cleaner?
        /** What to tell the user before a grammar fix can run, or null when the key it needs is set. */
        fun grammarFixMissingKey(): String?
        fun grammarFixCleaner(): DictationPipeline.Cleaner

        // The text field.
        fun rememberFocus()
        fun forgetSnapshot()
        fun beginDraft(): Draft?
        fun readFixTarget(): FixTarget?
        suspend fun inject(text: String): TextInjector.Outcome
        fun copyToClipboard(text: String)
        val canUndo: Boolean
        fun undo(): TextInjector.UndoResult

        // The button.
        fun showState(state: State)
        fun showFeedback(text: String, durationMs: Long = 2000, style: Fb = Fb.NOTICE, onClick: (() -> Unit)? = null)
        fun haptic()

        // The main thread.
        fun post(block: Runnable)
        fun postDelayed(block: Runnable, delayMs: Long)
        fun removeCallbacks(block: Runnable)
        fun hasCallbacks(block: Runnable): Boolean

        fun logInfo(message: String)
        fun logWarn(message: String)
        fun logError(message: String)
    }

    var state = State.IDLE
        private set
    /** When the last tap on the idle button lifted, for pairing two taps into a start. */
    private var lastIdleTapAt = 0L
    /**
     * Anything a dictation or grammar fix throws ends here instead of crashing the
     * process, which would also take the floating button and the wake word down.
     */
    private val crashGuard = CoroutineExceptionHandler { _, e ->
        fx.logError("Unexpected failure: ${e.javaClass.simpleName}: ${e.message}")
        fx.post {
            if (state != State.WORKING) return@post
            val kept = keepWorkRecording() ?: pendingAudio
            endWork()
            pendingAudio = kept
            if (kept != null) {
                setState(State.RETRY)
                fx.showFeedback("Something went wrong\nRecording saved. Tap to retry, hold to discard", 6000)
            } else {
                setState(State.IDLE)
                fx.showFeedback("Something went wrong (${e.javaClass.simpleName})", 4000)
            }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + main + crashGuard)
    private var work: Job? = null
    /** The recording [work] is uploading (standard mode), so giving up on it can keep it for a retry. */
    private var workAudio: OpenAiClient.Audio? = null
    /**
     * The raw recording [work] holds only in memory (while it is encoded, or while a
     * live session finishes), so giving up on it, a crash or a destroy can still keep it.
     */
    private var workPcm: ByteArray? = null
    /** The live session [work] is waiting on, and the words it typed, so giving up can drop the one and keep the other. */
    private var workSession: LiveSession? = null
    private var workDraft: Draft? = null
    /** Set when [work] is given up, for the transcriber on the IO thread: no fallback upload or cleanup after that. */
    private var workGivenUp: AtomicBoolean? = null
    /** Whether a long-press may give up on [work]. */
    private var workCancellable = false
    /** Saves and clears of the pending recording can come from two IO threads at once (a fallback upload and a give-up). */
    private val pendingLock = Any()
    private var recorder: Mic? = null
    /** The live transcription stream for the current recording, in live mode. */
    private var live: LiveSession? = null
    /** Words typed into the field while talking (live mode), and what has been heard so far. */
    private var draft: Draft? = null
    private var pendingDraft: Draft? = null
    private val partial = StringBuilder()
    /** Field updates are batched: at most one every [DRAFT_INTERVAL_MS]. */
    private val draftTick = Runnable { draft?.update(partial.toString().trim()) }
    /** Audio of the last failed dictation, kept until it is sent or discarded. */
    private var pendingAudio: OpenAiClient.Audio? = null
    /**
     * True while [restorePending] reads the disk (a few ms after the service starts).
     * Nothing starts until it is done, so a new dictation's save cannot overwrite a
     * recording that was never offered for a retry.
     */
    private var restoring = false
    /**
     * Whether the running recording is one of the counted locked ones. A dictation
     * holds the button until it ends, so no other can be counted before it is refunded.
     */
    private var countedLocked = false

    // --- Events ---

    /**
     * Starting a recording takes a double tap, so a stray finger brushing the
     * button does nothing. Stopping, retrying and the long-press stay one touch.
     */
    fun onTap(downAt: Long, upAt: Long) {
        if (state == State.IDLE && restoring) return
        when (state) {
            State.IDLE -> if (downAt - lastIdleTapAt <= doubleTapTimeoutMs) {
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

    fun onLongPress() {
        if (state == State.IDLE && restoring) return
        when (state) {
            State.IDLE -> fixFocusedText()
            State.RETRY -> {
                val length = pendingAudio?.let { " ${clock(it.seconds)}" } ?: ""
                fx.showFeedback("Discard the$length recording? Tap here to discard it", CONFIRM_WINDOW_MS, Fb.CONFIRM) {
                    if (state == State.RETRY) discardPending()
                }
            }
            State.WORKING -> cancelWork()
            else -> {}
        }
    }

    /** The buttons are being hidden: a running recording is finished and sent. */
    fun finishRecording() {
        if (state == State.RECORDING) stopAndProcess()
    }

    /** Locking mid-dictation caps the running recording. */
    fun onScreenOff() {
        recorder?.let { it.capAt(lockLimits.capWhenLocked(it.elapsedSeconds)) }
    }

    /** Unlock resets the locked-recording count. */
    fun onUnlock() = lockLimits.onUnlock()

    /** A recording that never got transcribed before the service stopped goes back to RETRY. */
    fun restorePending() {
        restoring = true
        scope.launch {
            val audio = try {
                withContext(io) { fx.loadPending() }
            } finally {
                restoring = false
            } ?: return@launch
            if (state != State.IDLE) return@launch
            fx.logInfo("Restored a pending %.1f s recording".format(audio.seconds))
            pendingAudio = audio
            setState(State.RETRY)
            fx.showFeedback("A ${clock(audio.seconds)} recording was not transcribed\nTap to retry, hold to discard", 6000)
        }
    }

    /** The service is going away. A recording in progress is kept for a retry instead of thrown away. */
    fun destroy() {
        recorder?.stop()?.let { pcm ->
            val seconds = Recorder.durationSeconds(pcm)
            if (seconds >= MIN_CLIP_SECONDS) savePending(wav(pcm))
        }
        recorder = null
        live?.cancel()
        // Work that still holds its recording only in memory saves it now.
        if (workAudio == null) workPcm?.let { savePending(wav(it)) }
        workSession?.cancel()
        scope.cancel()
    }

    // --- Transitions ---

    /**
     * Gives up on a transcription or grammar fix that is taking too long. The
     * recording is already on disk, so it becomes a retry. The request itself is
     * left to finish or time out in the background; its answer is ignored.
     */
    private fun cancelWork() {
        if (!workCancellable) return
        work?.cancel()
        workGivenUp?.set(true)
        workSession?.cancel()
        fx.haptic()
        val kept = keepWorkRecording()
        val typed = workDraft
        endWork()
        if (kept != null) {
            pendingAudio = kept
            pendingDraft = typed
            setState(State.RETRY)
            fx.showFeedback("Stopped\nRecording saved. Tap to retry, hold to discard", 5000)
        } else {
            setState(State.IDLE)
            fx.showFeedback("Cancelled")
        }
    }

    private fun discardPending() {
        pendingAudio = null
        pendingDraft = null
        scope.launch(io) { synchronized(pendingLock) { fx.clearPending() } }
        setState(State.IDLE)
        fx.showFeedback("Recording discarded")
    }

    private fun startRecording() {
        // Read once: each read is a Keystore decrypt, and it runs before the mic opens.
        val apiKey = fx.apiKey()
        if (apiKey.isBlank()) {
            fx.showFeedback("Set your OpenAI API key in Voice Relay", 3000)
            return
        }
        val decision = lockLimits.onStart(fx.isLocked(), MAX_SECONDS)
        if (decision !is LockScreenLimits.Decision.Allow) {
            fx.showFeedback("Unlock your phone to keep dictating (${lockLimits.maxRecordings} locked recordings used)", 3500)
            return
        }
        val session = if (fx.liveTranscriptionEnabled()) fx.startLiveSession(apiKey) else null
        val rec = fx.newMic(
            decision.maxSeconds,
            onLimit = { fx.post { if (state == State.RECORDING) stopAndProcess(hitLimit = true) } },
            onChunk = session?.let { s -> { chunk, n -> s.append(chunk, n) } },
            onError = {
                fx.post {
                    if (state != State.RECORDING) return@post
                    val heard = (recorder?.elapsedSeconds ?: 0.0) >= MIN_CLIP_SECONDS
                    stopAndProcess()
                    fx.showFeedback(
                        if (heard) "The microphone stopped. Transcribing what was recorded"
                        else "The microphone stopped working. Try again",
                        3500,
                    )
                }
            },
        )
        try {
            rec.start()
        } catch (_: SecurityException) {
            session?.cancel()
            if (decision.remaining != null) lockLimits.refund()
            fx.showFeedback("Grant microphone access in Voice Relay", 3000)
            return
        } catch (e: IllegalStateException) {
            session?.cancel()
            if (decision.remaining != null) lockLimits.refund()
            fx.showFeedback(e.message ?: "Microphone unavailable", 3000)
            return
        }
        recorder = rec
        live = session
        countedLocked = decision.remaining != null
        setState(State.RECORDING)
        fx.rememberFocus()
        if (session != null) startLiveTyping(session)
        fx.prewarm(apiKey)
        decision.remaining?.let { left ->
            fx.showFeedback("Phone locked: stops after ${decision.maxSeconds / 60} min, $left more before you must unlock", 3000)
        }
    }

    /** Types recognized words into the focused field as they arrive. */
    private fun startLiveTyping(session: LiveSession) {
        partial.clear()
        pendingDraft = null
        draft = fx.beginDraft()
        fx.logInfo("Live typing: ${if (draft != null) "into the focused field" else "no editable field"}")
        session.onDelta = { piece ->
            fx.post {
                partial.append(piece)
                if (draft != null && !fx.hasCallbacks(draftTick)) fx.postDelayed(draftTick, DRAFT_INTERVAL_MS)
            }
        }
    }

    private fun stopAndProcess(hitLimit: Boolean = false) {
        val rec = recorder
        val counted = countedLocked
        countedLocked = false
        val pcm = rec?.stop() ?: ByteArray(0)
        recorder = null
        val session = live
        live = null
        val typed = draft
        draft = null
        fx.removeCallbacks(draftTick)
        // Everything heard so far goes into the field now, before the final text.
        typed?.update(partial.toString().trim())
        if (Recorder.durationSeconds(pcm) < MIN_CLIP_SECONDS) {
            typed?.update("")
            refund(counted)
            session?.cancel()
            fx.forgetSnapshot()
            setState(State.IDLE)
            fx.showFeedback("Too short, nothing sent")
            return
        }
        if (hitLimit && rec != null) {
            fx.showFeedback("Stopped at the ${rec.maxSeconds / 60} min limit, transcribing", 2500)
        }
        setState(State.WORKING)
        val seconds = Recorder.durationSeconds(pcm)
        fx.logInfo("Recorded %.1f s (%s)".format(seconds, if (session != null) "live" else "standard"))
        if (session != null) {
            process(OpenAiClient.Audio(ByteArray(0), "live-stream", "audio/pcm", seconds), session, pcm, typed, counted)
        } else {
            workAudio = null
            workPcm = pcm
            workCancellable = true
            work = scope.launch { process(withContext(cpu) { fx.encode(pcm, seconds) }, counted = counted) }
        }
    }

    /**
     * The recording [work] is handling, saved for a retry: the upload's file (saved
     * again in case the upload was given up before its own save ran), or the raw
     * recording held in memory, as WAV (no encode on the way out).
     */
    private fun keepWorkRecording(): OpenAiClient.Audio? {
        val kept = workAudio ?: workPcm?.let { wav(it) } ?: return null
        if (kept !== pendingAudio) scope.launch(io) { savePending(kept) }
        return kept
    }

    /** The work is over (done, failed or given up): nothing is left to cancel or keep. */
    private fun endWork() {
        workAudio = null
        workPcm = null
        workSession = null
        workDraft = null
        workGivenUp = null
        workCancellable = false
    }

    private fun wav(pcm: ByteArray) = OpenAiClient.Audio.wav(WavWriter.encode(pcm), Recorder.durationSeconds(pcm))

    /**
     * Transcribe and clean up [audio]. With a [session] (live mode) the transcript
     * comes from the live stream, and [pcm] is the fallback: if the live session
     * fails, the same recording goes through the standard upload instead, and it is
     * what a retry resends.
     */
    private fun process(
        audio: OpenAiClient.Audio,
        session: LiveSession? = null,
        pcm: ByteArray? = null,
        draft: Draft? = null,
        /** A new locked recording, counted against the limit: a failure gives it back. */
        counted: Boolean = false,
    ) {
        val apiKey = fx.apiKey()
        if (apiKey.isBlank()) {
            // The key was removed while recording: keep the recording to send once it is set.
            session?.cancel()
            val kept = if (pcm != null) wav(pcm) else audio
            if (kept !== pendingAudio) scope.launch(io) { savePending(kept) }
            endWork()
            pendingAudio = kept
            pendingDraft = draft
            setState(State.RETRY)
            fx.showFeedback("Set your OpenAI API key in Voice Relay", 3000)
            return
        }
        setState(State.WORKING)
        val standard = fx.standardTranscriber(apiKey)
        var uploaded: OpenAiClient.Audio? = null
        val givenUp = AtomicBoolean(false)
        val transcriber = if (session == null || pcm == null) standard else DictationPipeline.Transcriber { clip ->
            val result = session.finish()
            if (result.error == null) fx.recordLiveUsage(clip.seconds)
            if (givenUp.get()) {
                // Given up while the live session finished: the answer is ignored, so do no more.
                OpenAiClient.Result(null, "Cancelled")
            } else if (result.error == null) {
                result
            } else {
                fx.logWarn("Live transcription failed (${result.error}), using the standard upload")
                // On disk before the upload, like a standard recording.
                fx.encode(pcm, clip.seconds).also { uploaded = it; savePending(it) }.let { standard.transcribe(it) }
            }
        }
        val pipeline = DictationPipeline(
            transcriber = transcriber,
            cleaner = fx.dictationCleaner(),
            log = { fx.logInfo("Dictation: $it") },
        )
        workAudio = if (session == null) audio else null
        workPcm = pcm
        workSession = session
        workDraft = draft
        workGivenUp = givenUp
        workCancellable = true
        work = scope.launch {
            val outcome = withContext(io) {
                // Saved before the upload, so a crash mid-upload cannot lose it either.
                // Live mode keeps the raw audio in memory and saves it only on failure.
                if (session == null && audio !== pendingAudio) savePending(audio)
                pipeline.run(audio)
            }
            endWork()
            if (outcome !is DictationPipeline.Outcome.Failed) withContext(io) { synchronized(pendingLock) { fx.clearPending() } }
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
                    fx.showFeedback("No speech detected")
                }
                is DictationPipeline.Outcome.Failed -> {
                    fx.logWarn("Transcription failed: ${outcome.error}")
                    refund(counted)
                    val kept = if (pcm == null) audio
                        else uploaded ?: withContext(cpu) { fx.encode(pcm, audio.seconds) }
                    if (pcm != null) withContext(io) { savePending(kept) }
                    pendingAudio = kept
                    pendingDraft = draft
                    setState(State.RETRY)
                    val typed = if ((draft?.typed ?: 0) > 0) "\nThe words so far are in the field." else ""
                    fx.showFeedback("Failed: ${outcome.error.take(80)}$typed\nRecording saved. Tap to retry, hold to discard", 6000)
                }
            }
        }
    }

    /** Gives back a locked recording that produced nothing. After an unlock there is nothing to give back. */
    private fun refund(counted: Boolean) {
        if (counted) lockLimits.refund()
    }

    /** A full disk must not stop the upload; the recording is still held in memory. */
    private fun savePending(audio: OpenAiClient.Audio) {
        try {
            synchronized(pendingLock) { fx.savePending(audio) }
        } catch (e: Exception) {
            fx.logWarn("Could not save the recording for retry: ${e.message}")
        }
    }

    /**
     * Grammar fix for typed text: the selection in the focused field, or the
     * whole field when nothing is selected. Uses the cleanup service and model
     * with the typed-text prompt, regardless of whether dictation cleanup is on.
     */
    private fun fixFocusedText() {
        fx.grammarFixMissingKey()?.let {
            fx.showFeedback(it, 3000)
            return
        }
        val range = fx.readFixTarget()
        if (range == null) {
            fx.showFeedback("Select text, or focus a field with text, to fix grammar", 3000)
            return
        }
        fx.haptic()
        setState(State.WORKING)
        val cleaner = fx.grammarFixCleaner()
        val original = range.text
        workAudio = null
        workCancellable = true
        work = scope.launch {
            val started = System.currentTimeMillis()
            val outcome = withContext(io) { DictationPipeline.fixTyped(original, cleaner) }
            fx.logInfo("Grammar fix: ${original.length} chars, ${System.currentTimeMillis() - started} ms")
            endWork()
            setState(State.IDLE)
            when (outcome) {
                is DictationPipeline.Outcome.Text -> when {
                    outcome.text == original -> fx.showFeedback("Looks good already")
                    range.replace(outcome.text) -> offerUndo("Grammar fixed")
                    else -> {
                        fx.copyToClipboard(outcome.text)
                        fx.showFeedback("Text changed while fixing, fixed version copied to clipboard", 3000)
                    }
                }
                is DictationPipeline.Outcome.Failed -> fx.showFeedback("Fix failed: ${outcome.error.take(80)}", 3000)
                DictationPipeline.Outcome.NoSpeech -> {}
            }
        }
    }

    private suspend fun deliver(text: String, note: String?, draft: Draft? = null) {
        if (draft != null) {
            // Live typing: swap the partial words for the final text in place.
            if (draft.finish(text)) return offerUndo(note ?: "Inserted")
            if (draft.typed > 0) {
                // The field changed while dictating; inserting again would duplicate the words.
                fx.copyToClipboard(text)
                return fx.showFeedback("The field changed while you were dictating, final text copied to clipboard", 3500)
            }
        }
        val message = when (fx.inject(text)) {
            TextInjector.Outcome.INSERTED -> return offerUndo(note ?: "Inserted")
            TextInjector.Outcome.PASTED -> note
            TextInjector.Outcome.COPIED_NO_FIELD -> "No text field focused, copied to clipboard"
            TextInjector.Outcome.COPIED_FAILED -> "Could not insert, copied to clipboard"
        }
        message?.let { fx.showFeedback(it, 2500) }
    }

    /** Shows [message] as a chip you can tap to undo, for a few seconds after text lands. */
    private fun offerUndo(message: String) {
        if (!fx.canUndo) return fx.showFeedback(message)
        fx.showFeedback(message, UNDO_WINDOW_MS, Fb.ACTION) {
            when (fx.undo()) {
                TextInjector.UndoResult.UNDONE -> fx.showFeedback("Undone")
                TextInjector.UndoResult.CHANGED_SINCE -> fx.showFeedback("The text changed since, nothing undone", 2500)
                TextInjector.UndoResult.NOTHING -> {}
            }
        }
    }

    private fun setState(new: State) {
        state = new
        fx.showState(new)
    }

    companion object {
        const val MIN_CLIP_SECONDS = 0.3
        const val MAX_SECONDS = 600
        const val UNDO_WINDOW_MS = 5000L
        const val CONFIRM_WINDOW_MS = 5000L
        const val DRAFT_INTERVAL_MS = 250L

        fun clock(seconds: Double): String {
            val s = seconds.toInt()
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}
