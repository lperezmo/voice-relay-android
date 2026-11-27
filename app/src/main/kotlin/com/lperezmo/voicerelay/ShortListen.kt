package com.lperezmo.voicerelay

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import okhttp3.OkHttpClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Records one short phrase and turns it into text: an optional tone, record until
 * the speaker stops ([EndOfSpeech]), then OpenAI's regular transcription (the one
 * dictation uses, with the same model, vocabulary and languages). Used by "hey
 * timer" ([TimerVoiceFlow]) and the timer rename microphone.
 *
 * [listen] blocks: call it on a background thread. [stop] and [cancel] may be
 * called from any thread. One instance is for one request: once cancelled, every
 * later [listen] returns [Heard.Nothing] at once, so make a new one per request.
 * It does not take [MicGuard]; the caller does. Logs carry sizes and timings only,
 * never what was said.
 */
class ShortListen(
    private val context: Context,
    private val settings: AppSettings,
    private val http: OkHttpClient,
    /** Where the transcription's cost is counted. Null counts nothing. */
    private val usage: UsageStore?,
    /** Each step, called on the listening thread just as it starts. */
    private val onStage: (Stage) -> Unit = {},
    /** Starts each log line, such as "Hey timer". */
    private val logName: String = "Short listen",
) {
    enum class Stage { LISTENING, TRANSCRIBING }

    /** How a listen ended. Each caller picks its own words for the failures. */
    sealed interface Heard {
        data class Text(val text: String) : Heard
        /** No speech, an empty transcript, or stopped. */
        data object Nothing : Heard
        /** The microphone could not be opened (in use, or no permission). */
        data object MicBusy : Heard
        /** OpenAI could not be reached. */
        data object NoNetwork : Heard
        /** OpenAI refused the key (401). */
        data object KeyRejected : Heard
        /** Any other transcription failure. */
        data class Failed(val httpCode: Int) : Heard
    }

    @Volatile private var cancelled = false
    /** Counted down to end the recording in progress; only the listening thread stops the recorder. */
    @Volatile private var recordingEnded: CountDownLatch? = null
    /** Set by [stop]; seen by [listen] even when [stop] came before the recording started (during the tone). */
    @Volatile private var stopRequested = false

    /** True once [cancel] was called. */
    val isCancelled: Boolean get() = cancelled

    /**
     * Plays a tone when [tone] is true, records until the speaker stops (at most
     * [EndOfSpeech.MAX_SECONDS]), then transcribes. Blocking.
     */
    fun listen(openAiKey: String, tone: Boolean = true): Heard {
        if (cancelled) return Heard.Nothing
        onStage(Stage.LISTENING)
        if (tone) tone()
        val ended = CountDownLatch(1)
        val ear = EndOfSpeech()
        val rec = Recorder(
            maxSeconds = EndOfSpeech.MAX_SECONDS,
            onLimit = { ended.countDown() },
            onChunk = { bytes, n -> if (ear.feed(bytes, n)) ended.countDown() },
            onError = { ended.countDown() },
        )
        recordingEnded = ended
        // A stop during the tone found no latch yet: honour it now.
        if (stopRequested) ended.countDown()
        if (!startRecording(rec)) {
            recordingEnded = null
            stopRequested = false
            return if (cancelled) Heard.Nothing else Heard.MicBusy
        }
        // Stopped while the tone played or the mic was being opened: end at once.
        if (!cancelled) ended.await(EndOfSpeech.MAX_SECONDS + 2L, TimeUnit.SECONDS)
        recordingEnded = null
        // A stop is for this recording only; a later one (an answer to a question) starts fresh.
        stopRequested = false
        val pcm = try { rec.stop() } catch (_: Exception) { ByteArray(0) }
        val seconds = Recorder.durationSeconds(pcm)
        Log.i(TAG, "$logName: recorded %.1f s, speech %s".format(seconds, if (ear.heardSpeech) "heard" else "not heard"))
        if (cancelled || !ear.heardSpeech) return Heard.Nothing

        onStage(Stage.TRANSCRIBING)
        val file = AacEncoder.encode(pcm, Recorder.SAMPLE_RATE, context.cacheDir)?.let { OpenAiClient.Audio.m4a(it, seconds) }
            ?: OpenAiClient.Audio.wav(WavWriter.encode(pcm), seconds)
        val model = settings.transcriptionModel
        val result = OpenAiClient(openAiKey, http = http).transcribe(file, model, settings.vocabulary, settings.languages)
        if (result.error == null) usage?.recordTranscription(model, seconds)
        return heardFrom(result)
    }

    /** Ends the recording in progress now (or as soon as it starts) and transcribes what was heard so far. */
    fun stop() {
        stopRequested = true
        recordingEnded?.countDown()
    }

    /** Ends the recording in progress and gives up: nothing is sent, [listen] returns [Heard.Nothing]. */
    fun cancel() {
        cancelled = true
        recordingEnded?.countDown()
    }

    /** The wake word's own recorder lets go of the mic a moment after it is told to; try a few times. */
    private fun startRecording(rec: Recorder): Boolean {
        repeat(MIC_TRIES) { attempt ->
            if (cancelled) return false
            try {
                rec.start()
                return true
            } catch (e: SecurityException) {
                return false
            } catch (e: IllegalStateException) {
                Log.i(TAG, "$logName: mic not free yet (try ${attempt + 1})")
                Thread.sleep(MIC_RETRY_MS)
            }
        }
        return false
    }

    /** A short beep that says "go ahead", finished before the recording starts so it is not in it. */
    private fun tone() {
        try {
            val generator = ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME)
            generator.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_MS)
            Thread.sleep(TONE_MS + 100L)
            generator.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "$logName: no tone: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "VoiceRelay"
        private const val TONE_MS = 150
        private const val TONE_VOLUME = 80
        private const val MIC_TRIES = 5
        private const val MIC_RETRY_MS = 200L

        /** What a transcription result means for the caller. Pure, so it is unit tested. */
        fun heardFrom(result: OpenAiClient.Result): Heard = when {
            result.ok -> Heard.Text(result.text!!.trim())
            result.error == null -> Heard.Nothing
            result.httpCode == 0 -> Heard.NoNetwork
            result.httpCode == 401 -> Heard.KeyRejected
            else -> Heard.Failed(result.httpCode)
        }
    }
}
