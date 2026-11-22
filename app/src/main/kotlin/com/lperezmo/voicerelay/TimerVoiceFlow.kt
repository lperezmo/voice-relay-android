package com.lperezmo.voicerelay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The phone's side of "hey timer", run by [WakeWordService] when the timer
 * phrase fires: a tone, record until the speaker stops ([EndOfSpeech]),
 * transcribe with OpenAI's regular transcription (the one dictation uses), hand
 * the transcript to Claude with tools ([TimerAssistant]), then say Claude's
 * line out loud and show it in a notification. Music is paused for the length
 * of it by holding the audio focus.
 *
 * The thinking is in the pure classes; this is the glue to the microphone, the
 * network, the speaker and [TimerController]. One request at a time, on its own
 * thread. Logs carry sizes and timings only, never what was said.
 */
class TimerVoiceFlow(
    private val context: Context,
    private val settings: AppSettings,
    /** What the service's notification should say now. Called on the main thread. */
    private val onStatus: (String) -> Unit,
    /** The request is over, however it ended. Called on the main thread. */
    private val onDone: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val secrets = SecretStore(context)
    private val usage = UsageStore(context)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    @Volatile private var running = false
    @Volatile private var cancelled = false
    /** When this request gives up, so a bad connection cannot keep the microphone from the wake word for long. */
    @Volatile private var deadline = 0L
    /** Counted down to end the recording in progress; only the worker thread stops the recorder. */
    @Volatile private var recordingEnded: CountDownLatch? = null
    private val ttsLock = Any()
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private var utterance = 0
    /** Requests give up sooner than dictation does: someone is standing there waiting for an answer. */
    private val http by lazy {
        OpenAiClient.sharedHttp.newBuilder().callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS).build()
    }
    /** The missing-keys notice is shown once, until the keys are there. */
    private var saidKeysMissing = false

    /** Starts one request. False when one is already running. */
    fun start(): Boolean {
        if (running) return false
        running = true
        cancelled = false
        deadline = System.currentTimeMillis() + DEADLINE_MS
        thread(name = "VoiceRelay-timer") {
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(speechAttributes)
                .build()
            try {
                work(focus)
            } catch (e: Exception) {
                Log.w(TAG, "Hey timer: failed: ${e.javaClass.simpleName}")
                finish(TimerAssistant.SAY_PROBLEM)
            } finally {
                audio.abandonAudioFocusRequest(focus)
                running = false
                main.post(onDone)
            }
        }
        return true
    }

    /** Stops a request in progress: the service is going away. */
    fun cancel() {
        cancelled = true
        recordingEnded?.countDown()
        synchronized(ttsLock) { tts?.stop() }
    }

    /** Lets go of the speech engine. Call when the service is destroyed. */
    fun shutdown() {
        cancel()
        synchronized(ttsLock) {
            tts?.shutdown()
            tts = null
            ttsReady = false
        }
    }

    /** True once the request was stopped or has run out of time: nothing more is recorded, sent, set or said. */
    private fun over() = cancelled || System.currentTimeMillis() > deadline

    private fun work(focus: AudioFocusRequest) {
        val started = System.currentTimeMillis()
        val openAiKey = secrets.getApiKey()
        val anthropicKey = secrets.getAnthropicKey()
        if (openAiKey.isBlank() || anthropicKey.isBlank()) {
            Log.i(TAG, "Hey timer: a key is missing, nothing done")
            if (!saidKeysMissing) {
                saidKeysMissing = true
                show("It needs both your OpenAI key and your Anthropic key. Add them in Voice Relay.")
            }
            return
        }
        saidKeysMissing = false
        // Pauses music for the whole exchange; it comes back when the focus is given up.
        audio.requestAudioFocus(focus)

        val cap = DailyCap().tryAcquire(LocalDate.now().toString(), settings.timerCapDay, settings.timerCapCount)
        settings.saveTimerCap(cap.day, cap.count)
        if (!cap.allowed) {
            Log.i(TAG, "Hey timer: daily limit reached")
            finish(DailyCap.SAY_LIMIT)
            return
        }

        val transcript = when (val heard = listen(openAiKey)) {
            Heard.Nothing -> return finish(TimerAssistant.SAY_NO_TIME)
            is Heard.Failed -> return finish(heard.say)
            is Heard.Text -> heard.text
        }
        if (cancelled) return
        status("Setting your timer")
        val client = AnthropicClient(anthropicKey, http = http)
        val model = settings.timerModel
        val system = TimerAssistant.systemPrompt(ZonedDateTime.now())
        val tools = TimerAssistant.definitions()
        val assistant = TimerAssistant(
            send = { messages ->
                if (over()) Turn(error = "stopped", httpCode = -1)
                else client.toolTurn(messages, model, system, tools).also {
                    if (it.error == null) usage.recordText(model, it.inputTokens, it.outputTokens)
                }
            },
            tools = object : TimerAssistant.Tools {
                override fun setTimer(seconds: Long, label: String?): String {
                    check(!over()) { "stopped" }
                    val timer = TimerController.get(context).start(seconds, label)
                    return TimerAssistant.describeSet(timer.label, seconds, ZonedDateTime.now())
                }

                override fun getTime(): String = TimerAssistant.describe(ZonedDateTime.now())

                override fun askUser(question: String): String {
                    check(!over()) { "stopped" }
                    show(question)
                    // A question nobody heard gets no answer: don't record one.
                    if (!say(question)) return ""
                    return (listen(openAiKey) as? Heard.Text)?.text.orEmpty()
                }
            },
        )
        val outcome = assistant.run(transcript)
        Log.i(
            TAG,
            "Hey timer: done in ${System.currentTimeMillis() - started} ms, ${outcome.rounds} rounds, " +
                "${outcome.timersSet} set, ${if (outcome.failed) "failed" else "ok"}"
        )
        val tooLong = outcome.failed && !cancelled && System.currentTimeMillis() > deadline
        finish(if (tooLong) "That took too long, so no timer was set." else outcome.spoken)
    }

    private sealed interface Heard {
        data class Text(val text: String) : Heard
        data object Nothing : Heard
        data class Failed(val say: String) : Heard
    }

    /** A tone, then records until the speaker stops, then transcribes. */
    private fun listen(openAiKey: String): Heard {
        if (over()) return Heard.Nothing
        status("Listening for your timer")
        tone()
        val ended = CountDownLatch(1)
        val ear = EndOfSpeech()
        val rec = Recorder(
            maxSeconds = EndOfSpeech.MAX_SECONDS,
            onLimit = { ended.countDown() },
            onChunk = { bytes, n -> if (ear.feed(bytes, n)) ended.countDown() },
            onError = { ended.countDown() },
        )
        recordingEnded = ended
        if (!startRecording(rec)) return Heard.Failed("The microphone is busy, so I couldn't listen.")
        // Stopped while the tone played or the mic was being opened: end at once.
        if (!cancelled) ended.await(EndOfSpeech.MAX_SECONDS + 2L, TimeUnit.SECONDS)
        recordingEnded = null
        val pcm = try { rec.stop() } catch (_: Exception) { ByteArray(0) }
        val seconds = Recorder.durationSeconds(pcm)
        Log.i(TAG, "Hey timer: recorded %.1f s, speech %s".format(seconds, if (ear.heardSpeech) "heard" else "not heard"))
        if (cancelled || !ear.heardSpeech) return Heard.Nothing

        status("Working out your timer")
        val file = AacEncoder.encode(pcm, Recorder.SAMPLE_RATE, context.cacheDir)?.let { OpenAiClient.Audio.m4a(it, seconds) }
            ?: OpenAiClient.Audio.wav(WavWriter.encode(pcm), seconds)
        val model = settings.transcriptionModel
        val result = OpenAiClient(openAiKey, http = http).transcribe(file, model, settings.vocabulary, settings.languages)
        if (result.error == null) usage.recordTranscription(model, seconds)
        return when {
            result.ok -> Heard.Text(result.text!!.trim())
            result.error == null -> Heard.Nothing
            result.httpCode == 0 -> Heard.Failed(TimerAssistant.SAY_NO_NETWORK)
            result.httpCode == 401 -> Heard.Failed("The OpenAI key was not accepted. Check it in Voice Relay.")
            else -> Heard.Failed("I couldn't turn that into text, so no timer was set.")
        }
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
                Log.i(TAG, "Hey timer: mic not free yet (try ${attempt + 1})")
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
            Log.w(TAG, "Hey timer: no tone: ${e.javaClass.simpleName}")
        }
    }

    private fun finish(line: String) {
        if (cancelled) return
        show(line)
        say(line)
    }


    private fun status(text: String) {
        main.post { onStatus(text) }
    }

    /**
     * Says [line] out loud and waits until it has been said. False when it was not said:
     * the request was stopped, or the phone has no speech engine that works (the
     * notification still shows the line).
     */
    private fun say(line: String): Boolean {
        val engine = engine() ?: return false
        val said = CountDownLatch(1)
        val id: String
        synchronized(ttsLock) {
            if (cancelled || tts !== engine) return false
            id = "timer-${++utterance}"
            // Only this line's own end counts: a cut-off earlier line reports its end here too.
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                private fun end(utteranceId: String?) { if (utteranceId == id) said.countDown() }
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = end(utteranceId)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = end(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) = end(utteranceId)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = end(utteranceId)
            })
            if (engine.speak(line, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) return false
        }
        if (!said.await(SPEAK_WAIT_S, TimeUnit.SECONDS)) synchronized(ttsLock) { tts?.stop() }
        return !cancelled
    }

    /** The speech engine, started the first time it is needed. Null when the phone has none that works (yet). */
    private fun engine(): TextToSpeech? {
        val ready = CountDownLatch(1)
        val engine = synchronized(ttsLock) {
            if (cancelled) return null
            tts?.let { return if (ttsReady) it else null }
            TextToSpeech(context) { status ->
                // Also when it comes up late: the next line is spoken then.
                ttsReady = status == TextToSpeech.SUCCESS
                ready.countDown()
            }.also { tts = it }
        }
        ready.await(TTS_START_S, TimeUnit.SECONDS)
        if (!ttsReady) {
            Log.w(TAG, "Hey timer: no speech engine")
            return null
        }
        engine.setAudioAttributes(speechAttributes)
        return engine
    }

    /** Shows [line] in its own notification: Luis may not be looking, but it is there when he does. */
    private fun show(line: String) {
        if (cancelled) return
        if (notifications.getNotificationChannel(CHANNEL) == null) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL, "Hey timer answers", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "What Voice Relay answered after the timer phrase. It is also said out loud."
                    setSound(null, null)
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_voice_mark)
            .setContentTitle("Hey timer")
            .setContentText(line)
            .setStyle(Notification.BigTextStyle().bigText(line))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(SHOW_MS)
            .build()
        try {
            notifications.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    private companion object {
        const val TAG = "VoiceRelay"
        const val CHANNEL = "timer_voice"
        /** Clear of the wake word's 7001 and 7002 and the Glyph undo's 7101. */
        const val NOTIFICATION_ID = 7003
        const val SHOW_MS = 10 * 60_000L
        const val TONE_MS = 150
        const val TONE_VOLUME = 80
        const val MIC_TRIES = 5
        const val MIC_RETRY_MS = 200L
        const val SPEAK_WAIT_S = 30L
        /** One request to OpenAI or Anthropic, and the whole exchange; both well under the wake word's own 180 s limit. */
        const val CALL_TIMEOUT_S = 30L
        const val DEADLINE_MS = 90_000L
        const val TTS_START_S = 5L
    }
}
