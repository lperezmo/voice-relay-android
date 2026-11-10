package com.lperezmo.voicerelay

import com.lperezmo.voicerelay.DictationMachine.Fb
import com.lperezmo.voicerelay.DictationMachine.State
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/**
 * Drives [DictationMachine] through every transition with fake effects. Coroutines
 * and handler posts are queued and run by [settle], so a test can act in between
 * (a long-press while the upload is still running).
 */
class DictationMachineTest {

    // --- Harness ---

    private class Queue : CoroutineDispatcher() {
        val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
    }

    private data class Feedback(val text: String, val durationMs: Long, val style: Fb, val onClick: (() -> Unit)?)

    private class FakeMic(
        val maxSecondsAtStart: Int,
        val onLimit: () -> Unit,
        val onChunk: ((ByteArray, Int) -> Unit)?,
        val onError: () -> Unit,
        val startError: Exception?,
        val events: MutableList<String>,
    ) : DictationMachine.Mic {
        var pcm = ByteArray(0)
        var started = false
        var stopped = false
        override var maxSeconds = maxSecondsAtStart
        override val elapsedSeconds get() = Recorder.durationSeconds(pcm)
        override fun start() {
            startError?.let { throw it }
            started = true
            events += "mic start"
        }
        override fun stop(): ByteArray {
            stopped = true
            events += "mic stop"
            return pcm
        }
        override fun capAt(seconds: Int) {
            if (seconds < maxSeconds) maxSeconds = seconds
        }
    }

    private class FakeLive(var result: OpenAiClient.Result, val events: MutableList<String>) : DictationMachine.LiveSession {
        override var onDelta: ((String) -> Unit)? = null
        var appended = 0
        var cancelled = false
        override fun append(chunk: ByteArray, length: Int) { appended += length }
        var onFinish: () -> Unit = {}
        override fun finish(): OpenAiClient.Result {
            events += "live finish"
            onFinish()
            return result
        }
        override fun cancel() {
            cancelled = true
            events += "live cancel"
        }
    }

    private class FakeDraft(var accepts: Boolean = true) : DictationMachine.Draft {
        val updates = mutableListOf<String>()
        var finished: String? = null
        override var typed = 0
        override fun update(text: String): Boolean {
            updates += text
            if (accepts) typed = text.length
            return accepts
        }
        override fun finish(text: String): Boolean {
            if (!update(text)) return false
            finished = text
            return true
        }
    }

    private class FakeTarget(override val text: String, var accepts: Boolean = true) : DictationMachine.FixTarget {
        var replaced: String? = null
        override fun replace(replacement: String): Boolean {
            if (accepts) replaced = replacement
            return accepts
        }
    }

    private inner class Fx : DictationMachine.Effects {
        val events = mutableListOf<String>()
        val feedback = mutableListOf<Feedback>()
        val states = mutableListOf<State>()
        val posted = ArrayDeque<Runnable>()
        val delayed = mutableListOf<Runnable>()

        var key = "sk-test"
        var locked = false
        var liveOn = false
        var micStartError: Exception? = null
        val mics = mutableListOf<FakeMic>()
        val mic get() = mics.last()
        var nextPcm = pcm(2.0)
        var liveResult = OpenAiClient.Result("live words", null)
        val lives = mutableListOf<FakeLive>()
        var transcribe: (OpenAiClient.Audio) -> OpenAiClient.Result = { OpenAiClient.Result("hello", null) }
        val uploads = mutableListOf<OpenAiClient.Audio>()
        var cleaner: DictationPipeline.Cleaner? = null
        var onDisk: OpenAiClient.Audio? = null
        var saveFails = false
        val saves = mutableListOf<OpenAiClient.Audio>()
        val liveUsage = mutableListOf<Double>()
        var draft: FakeDraft? = null
        var fixMissingKey: String? = null
        var fixTarget: FakeTarget? = null
        var fix: (String) -> OpenAiClient.Result = { OpenAiClient.Result("Fixed.", null) }
        var injectOutcome = TextInjector.Outcome.INSERTED
        val injected = mutableListOf<String>()
        val clipboard = mutableListOf<String>()
        override var canUndo = true
        var undoResult = TextInjector.UndoResult.UNDONE
        var haptics = 0

        override fun apiKey() = key
        override fun isLocked() = locked
        override fun liveTranscriptionEnabled() = liveOn

        override fun startLiveSession(apiKey: String) = FakeLive(liveResult, events).also { lives += it; events += "live start" }
        override fun newMic(maxSeconds: Int, onLimit: () -> Unit, onChunk: ((ByteArray, Int) -> Unit)?, onError: () -> Unit) =
            FakeMic(maxSeconds, onLimit, onChunk, onError, micStartError, events).also { it.pcm = nextPcm; mics += it }
        override fun prewarm(apiKey: String) { events += "prewarm" }
        var onEncode: () -> Unit = {}
        override fun encode(pcm: ByteArray, seconds: Double): OpenAiClient.Audio {
            events += "encode"
            onEncode()
            return OpenAiClient.Audio.m4a(pcm.copyOf(16), seconds)
        }

        override fun savePending(audio: OpenAiClient.Audio) {
            events += "save"
            if (saveFails) throw java.io.IOException("disk full")
            saves += audio
            onDisk = audio
        }
        override fun loadPending() = onDisk
        override fun clearPending() {
            events += "clear"
            onDisk = null
        }

        override fun standardTranscriber(apiKey: String) = DictationPipeline.Transcriber { audio ->
            events += "upload"
            uploads += audio
            transcribe(audio)
        }
        override fun recordLiveUsage(seconds: Double) { liveUsage += seconds }
        override fun dictationCleaner() = cleaner
        override fun grammarFixMissingKey() = fixMissingKey
        override fun grammarFixCleaner() = DictationPipeline.Cleaner { fix(it) }

        override fun rememberFocus() { events += "remember focus" }
        override fun forgetSnapshot() { events += "forget snapshot" }
        override fun beginDraft() = draft
        override fun readFixTarget() = fixTarget
        override suspend fun inject(text: String): TextInjector.Outcome {
            injected += text
            return injectOutcome
        }
        override fun copyToClipboard(text: String) { clipboard += text }
        override fun undo() = undoResult

        override fun showState(state: State) { states += state }
        override fun showFeedback(text: String, durationMs: Long, style: Fb, onClick: (() -> Unit)?) {
            feedback += Feedback(text, durationMs, style, onClick)
        }
        override fun haptic() { haptics++ }

        override fun post(block: Runnable) { posted.addLast(block) }
        override fun postDelayed(block: Runnable, delayMs: Long) { delayed += block }
        override fun removeCallbacks(block: Runnable) {
            posted.remove(block)
            delayed.remove(block)
        }
        override fun hasCallbacks(block: Runnable) = block in posted || block in delayed

        override fun logInfo(message: String) {}
        override fun logWarn(message: String) {}
        override fun logError(message: String) {}
    }

    private val fx = Fx()
    private val main = Queue()
    private val io = Queue()
    private val cpu = Queue()
    private val limits = LockScreenLimits()
    private val machine = DictationMachine(fx, DOUBLE_TAP, main, io, cpu, limits)
    private var clock = 10_000L

    /** Runs every queued coroutine and handler post until nothing is left. */
    private fun settle() {
        while (true) {
            val next = main.tasks.removeFirstOrNull() ?: io.tasks.removeFirstOrNull()
                ?: cpu.tasks.removeFirstOrNull() ?: fx.posted.removeFirstOrNull() ?: return
            next.run()
        }
    }

    /** Runs the delayed handler posts (the live-typing tick). */
    private fun runDelayed() {
        val due = fx.delayed.toList()
        fx.delayed.clear()
        due.forEach { it.run() }
    }

    private fun tap() {
        clock += 100
        machine.onTap(clock, clock + 50)
        clock += 50
    }

    private fun doubleTap() {
        tap()
        tap()
    }

    private fun lastFeedback() = fx.feedback.last().text

    private fun pcm(seconds: Double) = ByteArray((seconds * Recorder.SAMPLE_RATE * 2).toInt())

    private fun record(seconds: Double = 2.0) {
        fx.nextPcm = pcm(seconds)
        doubleTap()
        assertEquals(State.RECORDING, machine.state)
    }

    /** Records [seconds], taps stop, and runs everything through to the end. */
    private fun dictate(seconds: Double = 2.0) {
        record(seconds)
        tap()
        settle()
    }

    private fun failed(msg: String = "offline") = OpenAiClient.Result(null, msg, 500)

    // --- Taps ---

    @Test fun `a single tap on the idle button does nothing`() {
        tap()
        assertEquals(State.IDLE, machine.state)
        assertTrue(fx.mics.isEmpty())
        clock += 1000
        tap()
        assertEquals(State.IDLE, machine.state)
        assertTrue(fx.mics.isEmpty())
    }

    @Test fun `a double tap starts recording`() {
        doubleTap()
        assertEquals(State.RECORDING, machine.state)
        assertTrue(fx.mic.started)
        assertEquals(DictationMachine.MAX_SECONDS, fx.mic.maxSecondsAtStart)
        assertEquals(listOf("mic start", "remember focus", "prewarm"), fx.events)
        assertEquals(listOf(State.RECORDING), fx.states)
    }

    @Test fun `two taps further apart than the double-tap timeout do not start, but the second pairs with a third`() {
        machine.onTap(1_000, 1_050)
        machine.onTap(1_050 + DOUBLE_TAP + 1, 1_050 + DOUBLE_TAP + 60)
        assertEquals(State.IDLE, machine.state)
        machine.onTap(1_050 + DOUBLE_TAP + 60 + DOUBLE_TAP, 1_050 + DOUBLE_TAP + 60 + DOUBLE_TAP + 50)
        assertEquals(State.RECORDING, machine.state)
    }

    @Test fun `a double tap gap is measured from the first tap's lift to the second tap's press`() {
        machine.onTap(1_000, 1_400)
        machine.onTap(1_400 + DOUBLE_TAP, 1_400 + DOUBLE_TAP + 50)
        assertEquals(State.RECORDING, machine.state)
    }

    @Test fun `the tap that started a recording does not count toward the next double tap`() {
        dictate()
        assertEquals(State.IDLE, machine.state)
        // One tap right after: a fresh pair is needed.
        tap()
        assertEquals(State.IDLE, machine.state)
        assertEquals(1, fx.mics.size)
    }

    @Test fun `a tap while working does nothing`() {
        record()
        tap()
        assertEquals(State.WORKING, machine.state)
        tap()
        tap()
        assertEquals(State.WORKING, machine.state)
        assertEquals(1, fx.mics.size)
        settle()
        assertEquals(State.IDLE, machine.state)
        assertEquals(1, fx.uploads.size)
    }

    // --- Starting ---

    @Test fun `with no API key a recording does not start`() {
        fx.key = ""
        doubleTap()
        assertEquals(State.IDLE, machine.state)
        assertTrue(fx.mics.isEmpty())
        assertEquals("Set your OpenAI API key in Voice Relay", lastFeedback())
    }

    @Test fun `no microphone permission`() {
        fx.micStartError = SecurityException()
        doubleTap()
        assertEquals(State.IDLE, machine.state)
        assertEquals("Grant microphone access in Voice Relay", lastFeedback())
        assertTrue(fx.states.isEmpty())
    }

    @Test fun `microphone in use shows the recorder's message, and a live session is dropped`() {
        fx.liveOn = true
        fx.micStartError = IllegalStateException("Microphone is in use by another app")
        doubleTap()
        assertEquals(State.IDLE, machine.state)
        assertEquals("Microphone is in use by another app", lastFeedback())
        assertTrue(fx.lives.single().cancelled)
    }

    // --- Standard mode ---

    @Test fun `standard dictation saves the recording before the upload and clears it after`() {
        dictate()
        assertEquals(State.IDLE, machine.state)
        assertEquals(listOf(State.RECORDING, State.WORKING, State.WORKING, State.IDLE), fx.states)
        assertEquals(
            listOf("mic start", "remember focus", "prewarm", "mic stop", "encode", "save", "upload", "clear"),
            fx.events,
        )
        assertSame(fx.saves.single(), fx.uploads.single())
        assertNull(fx.onDisk)
        assertEquals(listOf("hello"), fx.injected)
        assertEquals("Inserted", lastFeedback())
        assertEquals(Fb.ACTION, fx.feedback.last().style)
    }

    @Test fun `a recording under the minimum is not sent`() {
        dictate(0.2)
        assertEquals(State.IDLE, machine.state)
        assertEquals("Too short, nothing sent", lastFeedback())
        assertTrue(fx.uploads.isEmpty())
        assertTrue("forget snapshot" in fx.events)
        assertTrue(fx.saves.isEmpty())
    }

    @Test fun `no speech clears the recording`() {
        fx.transcribe = { OpenAiClient.Result("", null) }
        dictate()
        assertEquals(State.IDLE, machine.state)
        assertEquals("No speech detected", lastFeedback())
        assertNull(fx.onDisk)
    }

    @Test fun `a failed upload keeps the recording and a tap retries the same audio`() {
        fx.transcribe = { failed() }
        dictate()
        assertEquals(State.RETRY, machine.state)
        val kept = fx.onDisk
        assertNotNull(kept)
        assertFalse("clear" in fx.events)
        assertEquals("Failed: offline\nRecording saved. Tap to retry, hold to discard", lastFeedback())
        assertEquals(6000L, fx.feedback.last().durationMs)

        fx.transcribe = { OpenAiClient.Result("second try", null) }
        fx.events.clear()
        tap()
        assertEquals(State.WORKING, machine.state)
        settle()
        assertEquals(State.IDLE, machine.state)
        assertSame(kept, fx.uploads.last())
        // Already on disk: not saved again before the retry.
        assertEquals(listOf("upload", "clear"), fx.events)
        assertEquals(listOf("second try"), fx.injected)
    }

    @Test fun `a retry that fails again stays in retry with the same recording`() {
        fx.transcribe = { failed() }
        dictate()
        val kept = fx.onDisk
        tap()
        settle()
        assertEquals(State.RETRY, machine.state)
        assertEquals(2, fx.uploads.size)
        assertSame(kept, fx.uploads[1])
        assertSame(kept, fx.onDisk)
    }

    @Test fun `a long error is cut to 80 characters`() {
        fx.transcribe = { failed("x".repeat(200)) }
        dictate()
        assertEquals("Failed: ${"x".repeat(80)}\nRecording saved. Tap to retry, hold to discard", lastFeedback())
    }

    @Test fun `a full disk does not stop the upload`() {
        fx.saveFails = true
        dictate()
        assertEquals(State.IDLE, machine.state)
        assertEquals(listOf("hello"), fx.injected)
    }

    @Test fun `the API key removed while recording keeps the recording for a retry`() {
        record()
        fx.key = ""
        tap()
        settle()
        assertEquals(State.RETRY, machine.state)
        assertEquals("Set your OpenAI API key in Voice Relay", lastFeedback())
        assertTrue(fx.uploads.isEmpty())
        val kept = fx.onDisk!!
        assertEquals("audio.m4a", kept.filename)

        // Still no key: a tap stays in retry without saving again.
        fx.events.clear()
        tap()
        settle()
        assertEquals(State.RETRY, machine.state)
        assertFalse("save" in fx.events)

        fx.key = "sk-test"
        tap()
        settle()
        assertEquals(State.IDLE, machine.state)
        assertSame(kept, fx.uploads.single())
    }

    @Test fun `the API key removed while recording in live mode cancels the session and keeps the audio`() {
        fx.liveOn = true
        fx.draft = FakeDraft()
        record()
        fx.key = ""
        tap()
        settle()
        assertEquals(State.RETRY, machine.state)
        assertTrue(fx.lives.single().cancelled)
        assertFalse("live finish" in fx.events)
        assertEquals("audio.wav", fx.onDisk!!.filename)
        assertEquals(2.0, fx.onDisk!!.seconds, 0.001)

        fx.key = "sk-test"
        fx.transcribe = { OpenAiClient.Result("Later.", null) }
        tap()
        settle()
        assertEquals(State.IDLE, machine.state)
        assertEquals("Later.", fx.draft!!.finished)
    }

    // --- Long-press while working ---

    @Test fun `long-press while uploading keeps the recording for a retry and ignores the late answer`() {
        record()
        fx.transcribe = {
            // The user gives up while the request is still running.
            machine.onLongPress()
            OpenAiClient.Result("late answer", null)
        }
        tap()
        settle()
        assertEquals(State.RETRY, machine.state)
        assertEquals(1, fx.haptics)
        assertTrue(fx.feedback.any { it.text == "Stopped\nRecording saved. Tap to retry, hold to discard" })
        val kept = fx.onDisk
        assertNotNull(kept)
        assertFalse("clear" in fx.events)
        assertTrue(fx.injected.isEmpty())

        fx.transcribe = { OpenAiClient.Result("retried", null) }
        tap()
        settle()
        assertEquals(State.IDLE, machine.state)
        assertSame(kept, fx.uploads.last())
        assertEquals(listOf("retried"), fx.injected)
        assertNull(fx.onDisk)
    }

    /** Long-press while the recording is still being encoded: kept as WAV for a retry, never uploaded. */
    private fun assertCancelledDuringEncode(uploadsBefore: Int) {
        assertEquals(State.RETRY, machine.state)
        assertEquals("Stopped\nRecording saved. Tap to retry, hold to discard", lastFeedback())
        assertEquals(uploadsBefore, fx.uploads.size)
        assertEquals("audio.wav", fx.onDisk!!.filename)
        assertEquals(2.0, fx.onDisk!!.seconds, 0.001)
        fx.transcribe = { OpenAiClient.Result("retried", null) }
        tap()
        settle()
        assertEquals(State.IDLE, machine.state)
        assertEquals("audio.wav", fx.uploads.last().filename)
        assertEquals("retried", fx.injected.last())
    }

    @Test fun `long-press while the first recording is still encoding keeps it for a retry`() {
        record()
        tap()
        // The encode is queued; the long-press lands before it finishes.
        machine.onLongPress()
        assertEquals(1, fx.haptics)
        settle()
        assertCancelledDuringEncode(uploadsBefore = 0)
    }

    @Test fun `long-press while encoding after an earlier dictation behaves the same`() {
        dictate()
        record()
        tap()
        machine.onLongPress()
        settle()
        assertCancelledDuringEncode(uploadsBefore = 1)
        assertEquals(listOf("hello", "retried"), fx.injected)
    }

    @Test fun `long-press in the middle of the encode drops its result`() {
        record()
        fx.onEncode = { machine.onLongPress() }
        tap()
        settle()
        fx.onEncode = {}
        assertCancelledDuringEncode(uploadsBefore = 0)
    }

    @Test fun `a finished dictation leaves nothing for a later long-press to cancel`() {
        dictate()
        fx.fixTarget = null
        machine.onLongPress()
        // Idle: the long-press is the grammar fix, not a stale cancel.
        assertEquals("Select text, or focus a field with text, to fix grammar", lastFeedback())
        assertEquals(0, fx.haptics)
    }

    @Test fun `long-press while a live session finishes keeps the recording and the typed words for a retry`() {
        fx.liveOn = true
        fx.draft = FakeDraft()
        record()
        val session = fx.lives.single()
        session.onDelta!!("some words")
        settle()
        runDelayed()
        // The user gives up while the live result is awaited; dropping the session fails it.
        session.result = OpenAiClient.Result(null, "Live connection failed")
        session.onFinish = { machine.onLongPress() }
        tap()
        settle()
        assertEquals(State.RETRY, machine.state)
        assertEquals(1, fx.haptics)
        assertTrue(session.cancelled)
        assertEquals("Stopped\nRecording saved. Tap to retry, hold to discard", lastFeedback())
        // No fallback upload, no encode: the recording is kept as WAV.
        assertFalse("upload" in fx.events)
        assertFalse("encode" in fx.events)
        assertEquals("audio.wav", fx.onDisk!!.filename)

        fx.transcribe = { OpenAiClient.Result("Some words.", null) }
        tap()
        settle()
        assertEquals(State.IDLE, machine.state)
        assertEquals("audio.wav", fx.uploads.single().filename)
        assertEquals("Some words.", fx.draft!!.finished)
    }

    @Test fun `a live result that arrives after giving up is ignored`() {
        fx.liveOn = true
        record()
        val session = fx.lives.single()
        session.result = OpenAiClient.Result("late words", null)
        session.onFinish = { machine.onLongPress() }
        tap()
        settle()
        assertEquals(State.RETRY, machine.state)
        assertTrue(fx.injected.isEmpty())
        assertNotNull(fx.onDisk)
    }

    @Test fun `a crash while a live session finishes keeps the recording`() {
        fx.liveOn = true
        record()
        fx.lives.single().onFinish = { throw IllegalStateException("boom") }
        tap()
        settle()
        assertEquals(State.RETRY, machine.state)
        assertEquals("Something went wrong\nRecording saved. Tap to retry, hold to discard", lastFeedback())
        assertEquals("audio.wav", fx.onDisk!!.filename)
    }

    @Test fun `destroy while a live session finishes saves the recording and drops the session`() {
        fx.liveOn = true
        record()
        tap()
        assertEquals(State.WORKING, machine.state)
        machine.destroy()
        assertEquals("audio.wav", fx.saves.single().filename)
        assertTrue(fx.lives.single().cancelled)
    }

    @Test fun `destroy while the recording is encoding saves it`() {
        record()
        tap()
        machine.destroy()
        assertEquals("audio.wav", fx.saves.single().filename)
        settle()
        assertTrue(fx.uploads.isEmpty())
    }

    // --- Live mode ---

    @Test fun `live dictation types words as they arrive and swaps in the final text`() {
        fx.liveOn = true
        fx.draft = FakeDraft()
        record()
        val session = fx.lives.single()
        // Mic chunks go to the session.
        fx.mic.onChunk!!(ByteArray(640), 640)
        assertEquals(640, session.appended)

        session.onDelta!!("hello")
        session.onDelta!!(" world")
        settle()
        // Batched: one tick for both pieces.
        assertEquals(1, fx.delayed.size)
        runDelayed()
        assertEquals(listOf("hello world"), fx.draft!!.updates)

        session.result = OpenAiClient.Result("Hello world.", null)
        tap()
        settle()
        assertEquals(State.IDLE, machine.state)
        assertEquals("Hello world.", fx.draft!!.finished)
        assertTrue(fx.injected.isEmpty())
        assertTrue(fx.uploads.isEmpty())
        // Live mode keeps the audio in memory; nothing goes to disk on success.
        assertTrue(fx.saves.isEmpty())
        assertEquals(listOf(2.0), fx.liveUsage)
    }

    @Test fun `stopping flushes the words heard so far before the final text`() {
        fx.liveOn = true
        fx.draft = FakeDraft()
        record()
        fx.lives.single().onDelta!!("partial")
        settle()
        tap()
        // The pending tick is dropped and the words go in at once.
        assertTrue(fx.delayed.isEmpty())
        assertEquals(listOf("partial"), fx.draft!!.updates)
    }

    @Test fun `a too-short live recording clears the typed words and drops the session`() {
        fx.liveOn = true
        fx.draft = FakeDraft()
        record(0.1)
        fx.lives.single().onDelta!!("uh")
        settle()
        tap()
        assertEquals(listOf("uh", ""), fx.draft!!.updates)
        assertTrue(fx.lives.single().cancelled)
        assertEquals("Too short, nothing sent", lastFeedback())
    }

    @Test fun `live failure saves the recording and falls back to the standard upload`() {
        fx.liveOn = true
        record()
        fx.lives.single().result = OpenAiClient.Result(null, "Live connection failed")
        tap()
        settle()
        assertEquals(State.IDLE, machine.state)
        assertEquals(
            listOf("live finish", "encode", "save", "upload", "clear"),
            fx.events.dropWhile { it != "live finish" },
        )
        assertEquals("audio.m4a", fx.uploads.single().filename)
        assertSame(fx.saves.single(), fx.uploads.single())
        assertEquals(listOf("hello"), fx.injected)
        assertTrue(fx.liveUsage.isEmpty())
        assertNull(fx.onDisk)
    }

    @Test fun `live and fallback both failing leaves the uploaded recording for a retry`() {
        fx.liveOn = true
        fx.draft = FakeDraft()
        record()
        val session = fx.lives.single()
        session.onDelta!!("some words")
        settle()
        runDelayed()
        session.result = OpenAiClient.Result(null, "Live connection failed")
        fx.transcribe = { failed() }
        tap()
        settle()
        assertEquals(State.RETRY, machine.state)
        val uploaded = fx.uploads.single()
        assertSame(uploaded, fx.onDisk)
        // Encoded once, for the fallback; the retry keeps that same file.
        assertEquals(1, fx.events.count { it == "encode" })
        assertEquals(
            "Failed: offline\nThe words so far are in the field.\nRecording saved. Tap to retry, hold to discard",
            lastFeedback(),
        )

        // The retry resends it and finishes the same draft.
        fx.transcribe = { OpenAiClient.Result("Some words.", null) }
        tap()
        settle()
        assertEquals(State.IDLE, machine.state)
        assertSame(uploaded, fx.uploads.last())
        assertEquals("Some words.", fx.draft!!.finished)
    }

    @Test fun `the field changing during live typing copies the final text instead of inserting twice`() {
        fx.liveOn = true
        val draft = FakeDraft()
        fx.draft = draft
        record()
        fx.lives.single().onDelta!!("words")
        settle()
        runDelayed()
        draft.accepts = false
        tap()
        settle()
        assertEquals(listOf("live words"), fx.clipboard)
        assertTrue(fx.injected.isEmpty())
        assertEquals("The field changed while you were dictating, final text copied to clipboard", lastFeedback())
    }

    @Test fun `live mode with no editable field inserts the final text normally`() {
        fx.liveOn = true
        fx.draft = null
        record()
        fx.lives.single().onDelta!!("ignored")
        settle()
        assertTrue(fx.delayed.isEmpty())
        tap()
        settle()
        assertEquals(listOf("live words"), fx.injected)
    }

    // --- Recorder events ---

    @Test fun `recorder error mid-recording transcribes what was heard`() {
        record(3.0)
        fx.mic.onError()
        settle()
        assertTrue(fx.mic.stopped)
        assertEquals(State.IDLE, machine.state)
        assertEquals(1, fx.uploads.size)
        assertTrue(fx.feedback.any { it.text == "The microphone stopped. Transcribing what was recorded" })
    }

    @Test fun `recorder error before anything was heard`() {
        record(0.1)
        fx.mic.onError()
        settle()
        assertEquals(State.IDLE, machine.state)
        assertTrue(fx.uploads.isEmpty())
        assertEquals("The microphone stopped working. Try again", lastFeedback())
        assertEquals(3500L, fx.feedback.last().durationMs)
    }

    @Test fun `a recorder error that arrives after stopping is ignored`() {
        record()
        val mic = fx.mic
        tap()
        mic.onError()
        settle()
        assertEquals(State.IDLE, machine.state)
        assertFalse(fx.feedback.any { it.text.startsWith("The microphone stopped") })
    }

    @Test fun `hitting the length limit stops and transcribes`() {
        record()
        fx.mic.onLimit()
        settle()
        assertTrue(fx.feedback.any { it.text == "Stopped at the 10 min limit, transcribing" })
        assertEquals(1, fx.uploads.size)
        assertEquals(State.IDLE, machine.state)
    }

    @Test fun `hiding the buttons finishes a running recording`() {
        record()
        machine.finishRecording()
        assertEquals(State.WORKING, machine.state)
        settle()
        assertEquals(1, fx.uploads.size)
        machine.finishRecording()
        assertEquals(State.IDLE, machine.state)
    }

    // --- Lock screen ---

    @Test fun `a locked recording is capped and counted`() {
        fx.locked = true
        record()
        assertEquals(LockScreenLimits.MAX_SECONDS, fx.mic.maxSecondsAtStart)
        assertEquals("Phone locked: stops after 2 min, 4 more before you must unlock", lastFeedback())
        assertEquals(1, limits.used)
    }

    @Test fun `after the locked limit the button asks for an unlock, and unlocking resets it`() {
        fx.locked = true
        repeat(LockScreenLimits.MAX_RECORDINGS) { dictate() }
        assertEquals(LockScreenLimits.MAX_RECORDINGS, fx.mics.size)
        doubleTap()
        assertEquals(State.IDLE, machine.state)
        assertEquals(LockScreenLimits.MAX_RECORDINGS, fx.mics.size)
        assertEquals("Unlock your phone to keep dictating (5 locked recordings used)", lastFeedback())
        machine.onUnlock()
        doubleTap()
        assertEquals(State.RECORDING, machine.state)
    }

    @Test fun `a locked recording whose microphone fails to start is refunded`() {
        fx.locked = true
        fx.micStartError = IllegalStateException("Microphone unavailable")
        doubleTap()
        assertEquals(0, limits.used)
        fx.micStartError = SecurityException()
        doubleTap()
        assertEquals(0, limits.used)
    }

    @Test fun `an unlocked microphone failure refunds nothing it did not count`() {
        fx.locked = true
        dictate()
        fx.locked = false
        fx.micStartError = SecurityException()
        doubleTap()
        assertEquals(1, limits.used)
    }

    @Test fun `a locked recording whose upload fails is given back`() {
        fx.locked = true
        fx.transcribe = { failed() }
        dictate()
        assertEquals(State.RETRY, machine.state)
        assertEquals(0, limits.used)
        // The retry is not a new recording: it neither counts nor refunds.
        tap()
        settle()
        assertEquals(0, limits.used)
    }

    @Test fun `a too-short locked recording is given back`() {
        fx.locked = true
        dictate(0.1)
        assertEquals("Too short, nothing sent", lastFeedback())
        assertEquals(0, limits.used)
    }

    @Test fun `a successful locked recording stays counted`() {
        fx.locked = true
        dictate()
        dictate(0.1)
        assertEquals(1, limits.used)
    }

    @Test fun `failed locked recordings never use up the limit`() {
        fx.locked = true
        fx.transcribe = { failed() }
        repeat(LockScreenLimits.MAX_RECORDINGS + 2) {
            record()
            tap()
            settle()
            machine.onLongPress()
            fx.feedback.last().onClick!!()
            settle()
        }
        assertEquals(0, limits.used)
    }

    @Test fun `the screen locking mid-recording caps it from now`() {
        record(30.0)
        machine.onScreenOff()
        assertEquals(30 + LockScreenLimits.MAX_SECONDS, fx.mic.maxSeconds)
        // With no recording running it does nothing.
        tap()
        settle()
        machine.onScreenOff()
        assertEquals(State.IDLE, machine.state)
    }

    // --- Retry and discard ---

    @Test fun `long-press in retry asks before discarding`() {
        fx.transcribe = { failed() }
        dictate(65.0)
        machine.onLongPress()
        val confirm = fx.feedback.last()
        assertEquals("Discard the 1:05 recording? Tap here to discard it", confirm.text)
        assertEquals(Fb.CONFIRM, confirm.style)
        assertEquals(DictationMachine.CONFIRM_WINDOW_MS, confirm.durationMs)
        assertEquals(State.RETRY, machine.state)

        confirm.onClick!!()
        settle()
        assertEquals(State.IDLE, machine.state)
        assertEquals("Recording discarded", lastFeedback())
        assertNull(fx.onDisk)
        // Nothing left to retry: a tap is an ordinary idle tap.
        tap()
        assertEquals(State.IDLE, machine.state)
    }

    @Test fun `a discard confirmed after the state moved on does nothing`() {
        fx.transcribe = { failed() }
        dictate()
        machine.onLongPress()
        val confirm = fx.feedback.last()
        fx.transcribe = { OpenAiClient.Result("ok", null) }
        tap()
        settle()
        assertEquals(State.IDLE, machine.state)
        fx.events.clear()
        confirm.onClick!!()
        settle()
        assertFalse("clear" in fx.events)
        assertEquals(State.IDLE, machine.state)
    }

    @Test fun `a recording left on disk comes back as a retry`() {
        fx.onDisk = OpenAiClient.Audio.m4a(ByteArray(8), 75.0)
        machine.restorePending()
        settle()
        assertEquals(State.RETRY, machine.state)
        assertEquals("A 1:15 recording was not transcribed\nTap to retry, hold to discard", lastFeedback())
        val restored = fx.onDisk
        tap()
        settle()
        assertSame(restored, fx.uploads.single())
        assertEquals(State.IDLE, machine.state)
    }

    @Test fun `a restored recording does not interrupt a dictation already under way`() {
        fx.onDisk = OpenAiClient.Audio.m4a(ByteArray(8), 5.0)
        machine.restorePending()
        record()
        settle()
        assertEquals(State.RECORDING, machine.state)
    }

    @Test fun `nothing on disk stays idle`() {
        machine.restorePending()
        settle()
        assertEquals(State.IDLE, machine.state)
        assertTrue(fx.feedback.isEmpty())
    }

    // --- Destroy ---

    @Test fun `destroy while recording saves the audio for a retry`() {
        record(4.0)
        machine.destroy()
        assertTrue(fx.mic.stopped)
        val saved = fx.saves.single()
        assertEquals("audio.wav", saved.filename)
        assertEquals(4.0, saved.seconds, 0.001)
        assertEquals(44 + pcm(4.0).size, saved.bytes.size)
    }

    @Test fun `destroy while recording in live mode saves the audio and drops the session`() {
        fx.liveOn = true
        record(4.0)
        machine.destroy()
        assertEquals(1, fx.saves.size)
        assertTrue(fx.lives.single().cancelled)
    }

    @Test fun `destroy after a too-short recording saves nothing`() {
        record(0.2)
        machine.destroy()
        assertTrue(fx.saves.isEmpty())
    }

    @Test fun `destroy while idle saves nothing`() {
        machine.destroy()
        assertTrue(fx.saves.isEmpty())
        assertTrue(fx.events.isEmpty())
    }

    @Test fun `destroy while uploading drops the answer and leaves the recording on disk`() {
        record()
        tap()
        // Run up to the upload, which saves the recording and gets an answer...
        while (io.tasks.isEmpty()) (main.tasks.removeFirstOrNull() ?: cpu.tasks.removeFirst()).run()
        io.tasks.removeFirst().run()
        // ...and the service goes away before the answer is handled.
        machine.destroy()
        settle()
        assertNotNull(fx.onDisk)
        assertFalse("clear" in fx.events)
        assertTrue(fx.injected.isEmpty())
    }

    // --- Delivery ---

    @Test fun `cleanup note is shown when the text was pasted`() {
        fx.cleaner = DictationPipeline.Cleaner { OpenAiClient.Result(null, "timeout") }
        fx.injectOutcome = TextInjector.Outcome.PASTED
        dictate()
        assertEquals("Cleanup failed (timeout), inserted raw text", lastFeedback())
        assertEquals(2500L, fx.feedback.last().durationMs)
    }

    @Test fun `pasted without a note says nothing`() {
        fx.injectOutcome = TextInjector.Outcome.PASTED
        dictate()
        assertTrue(fx.feedback.isEmpty())
    }

    @Test fun `no field copies to the clipboard`() {
        fx.injectOutcome = TextInjector.Outcome.COPIED_NO_FIELD
        dictate()
        assertEquals("No text field focused, copied to clipboard", lastFeedback())
        fx.injectOutcome = TextInjector.Outcome.COPIED_FAILED
        dictate()
        assertEquals("Could not insert, copied to clipboard", lastFeedback())
    }

    @Test fun `the undo chip undoes, or says the text changed`() {
        dictate()
        val chip = fx.feedback.last()
        assertEquals(DictationMachine.UNDO_WINDOW_MS, chip.durationMs)
        chip.onClick!!()
        assertEquals("Undone", lastFeedback())
        fx.undoResult = TextInjector.UndoResult.CHANGED_SINCE
        chip.onClick!!()
        assertEquals("The text changed since, nothing undone", lastFeedback())
    }

    @Test fun `with nothing to undo the notice is plain`() {
        fx.canUndo = false
        dictate()
        assertEquals(Fb.NOTICE, fx.feedback.last().style)
        assertNull(fx.feedback.last().onClick)
    }

    // --- Grammar fix ---

    @Test fun `long-press on idle fixes the focused text`() {
        fx.fixTarget = FakeTarget("helo ther")
        fx.fix = { OpenAiClient.Result("Hello there.", null) }
        machine.onLongPress()
        assertEquals(State.WORKING, machine.state)
        assertEquals(1, fx.haptics)
        settle()
        assertEquals(State.IDLE, machine.state)
        assertEquals("Hello there.", fx.fixTarget!!.replaced)
        assertEquals("Grammar fixed", lastFeedback())
        assertEquals(Fb.ACTION, fx.feedback.last().style)
    }

    @Test fun `grammar fix needs a key and a field`() {
        fx.fixMissingKey = "Set your Anthropic API key in Voice Relay"
        machine.onLongPress()
        assertEquals("Set your Anthropic API key in Voice Relay", lastFeedback())
        fx.fixMissingKey = null
        machine.onLongPress()
        assertEquals("Select text, or focus a field with text, to fix grammar", lastFeedback())
        assertEquals(State.IDLE, machine.state)
        assertTrue(fx.states.isEmpty())
    }

    @Test fun `grammar fix outcomes`() {
        fx.fixTarget = FakeTarget("Fine.")
        fx.fix = { OpenAiClient.Result("Fine.", null) }
        machine.onLongPress(); settle()
        assertEquals("Looks good already", lastFeedback())

        fx.fixTarget = FakeTarget("helo", accepts = false)
        fx.fix = { OpenAiClient.Result("Hello", null) }
        machine.onLongPress(); settle()
        assertEquals(listOf("Hello"), fx.clipboard)
        assertEquals("Text changed while fixing, fixed version copied to clipboard", lastFeedback())

        fx.fixTarget = FakeTarget("helo")
        fx.fix = { OpenAiClient.Result(null, "offline") }
        machine.onLongPress(); settle()
        assertEquals("Fix failed: offline", lastFeedback())
        assertEquals(State.IDLE, machine.state)
    }

    @Test fun `long-press while fixing cancels it`() {
        fx.fixTarget = FakeTarget("helo")
        fx.fix = {
            machine.onLongPress()
            OpenAiClient.Result("Hello", null)
        }
        machine.onLongPress()
        settle()
        assertEquals(State.IDLE, machine.state)
        assertEquals("Cancelled", lastFeedback())
        assertNull(fx.fixTarget!!.replaced)
        assertEquals(2, fx.haptics)
    }

    // --- Crash guard ---

    @Test fun `a crash during an upload keeps the recording for a retry`() {
        fx.transcribe = { throw IllegalStateException("boom") }
        dictate()
        assertEquals(State.RETRY, machine.state)
        assertEquals("Something went wrong\nRecording saved. Tap to retry, hold to discard", lastFeedback())
        assertNotNull(fx.onDisk)
        fx.transcribe = { OpenAiClient.Result("ok", null) }
        tap()
        settle()
        assertEquals(State.IDLE, machine.state)
    }

    @Test fun `a crash during a grammar fix goes back to idle`() {
        fx.fixTarget = FakeTarget("helo")
        fx.fix = { throw IllegalStateException("boom") }
        machine.onLongPress()
        settle()
        assertEquals(State.IDLE, machine.state)
        assertEquals("Something went wrong (IllegalStateException)", lastFeedback())
    }

    companion object {
        private const val DOUBLE_TAP = 300L
    }
}
