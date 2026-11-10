package com.lperezmo.voicerelay

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlin.math.abs

/**
 * Listens for the wake phrase ("hey claude" unless you chose another) on the
 * phone and opens Claude's voice assistant.
 *
 * A microphone foreground service: 16 kHz mono PCM goes straight from
 * AudioRecord into a sherpa-onnx keyword spotter. Nothing is stored or sent
 * anywhere. [WakeWordController] decides when the mic is on; this class wires
 * it to Android: the mic thread, recording and audio-mode callbacks, the
 * VOICE_ASSIST intent and the media PLAY key.
 *
 * Android only lets a microphone foreground service start while the app is in
 * the foreground (or from a few exemptions), so it is started from the
 * settings screen, [WakeControlActivity] (Tasker, the notification), or the
 * accessibility service when Android rebinds it. When a start is refused, a
 * notification offers to turn it back on with one tap.
 */
class WakeWordService : Service() {

    companion object {
        private const val TAG = "VoiceRelay"
        private const val ACTION_START = "com.lperezmo.voicerelay.action.WAKE_SERVICE_START"
        private const val ACTION_STOP = "com.lperezmo.voicerelay.action.WAKE_SERVICE_STOP"
        private const val ACTION_RELOAD = "com.lperezmo.voicerelay.action.WAKE_SERVICE_RELOAD"
        private const val CHANNEL_ID = "wake_word"
        private const val NOTIFICATION_ID = 7001
        private const val RESUME_NOTIFICATION_ID = 7002
        private const val SAMPLE_RATE = 16000
        private const val CHUNK = 1600 // 100 ms
        /** MediaRecorder.AudioSource.HOTWORD is a system API. Other assistants' hotword checks use it. */
        private const val HOTWORD_SOURCE = 1999
        private const val TICK_MS = 250L
        private const val POLL_MS = 1000L
        /** A stopped mic thread still going after this long is logged. */
        private const val SLOW_STOP_MS = 1500L
        /** How long a new mic thread waits for an earlier one to let go of the mic before giving up. */
        private const val PREVIOUS_WAIT_MS = 10_000L

        /**
         * The last mic thread started, by any instance of the service: a quick off and
         * on can start a new instance while the old one's thread is still winding down,
         * and the new thread waits for it. A thread that ended normally clears itself,
         * so a destroyed instance is not kept alive by it.
         */
        private val lastStartedMic = java.util.concurrent.atomic.AtomicReference<Thread?>(null)

        /**
         * Audio sessions of our own recorders that are not released yet, of any
         * instance, including a stopped one still winding down, so none of them
         * looks like another app.
         */
        private val ownSessions: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        /**
         * All-zero chunks in a row before the notification says the mic cannot hear: 5 s,
         * long enough that a HAL's noise gate in a quiet room does not trip it.
         */
        private const val ZERO_CHUNKS = 50

        @Volatile
        var running = false
            private set

        /** Starts listening. Returns false, and offers a tap-to-resume notification, if Android refuses. */
        fun start(context: Context, reason: String): Boolean {
            val intent = Intent(context, WakeWordService::class.java).setAction(ACTION_START)
            return try {
                ContextCompat.startForegroundService(context, intent)
                Log.i(TAG, "Wake word: start requested ($reason)")
                true
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException: started from the background.
                Log.w(TAG, "Wake word: start refused ($reason): ${e.javaClass.simpleName}")
                postResumeNotification(context)
                false
            } catch (e: SecurityException) {
                Log.w(TAG, "Wake word: start refused ($reason): ${e.javaClass.simpleName}")
                postResumeNotification(context)
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }

        @Volatile private var tokensCache: Set<String>? = null

        /** The model's phones, from tokens.txt (read once). Empty if the model is missing. */
        fun modelTokens(context: Context): Set<String> = tokensCache ?: try {
            WakeWord.modelTokens(context.assets.open(WakeWord.TOKENS_ASSET).bufferedReader().use { it.readText() })
                .also { tokensCache = it }
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Wake word: no tokens.txt: ${e.javaClass.simpleName}")
            emptySet()
        }

        /**
         * The phrase and keywords the listener uses: the saved ones, or the default
         * when they no longer fit the model (sherpa-onnx refuses a list with an
         * unknown phone, which would stop the listener). Settings and the
         * notifications show this, so they never name a phrase that is not heard.
         */
        fun effectiveWake(context: Context): WakeWord.Effective {
            val settings = AppSettings(context)
            return WakeWord.effective(settings.wakePhrase, settings.wakeKeywords, modelTokens(context))
        }

        /**
         * Restarts listening with the current sensitivity and phrase. The model is
         * reloaded only for a new sensitivity; a new phrase just needs a new stream.
         * No-op when not running.
         */
        fun reload(context: Context) {
            if (!running) return
            try {
                context.startService(Intent(context, WakeWordService::class.java).setAction(ACTION_RELOAD))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Wake word: reload refused: ${e.javaClass.simpleName}")
            }
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Wake word", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Shown while Voice Relay listens for the wake word"
                        setShowBadge(false)
                    }
                )
            }
        }

        private fun postResumeNotification(context: Context) {
            ensureChannel(context)
            val tap = PendingIntent.getActivity(
                context, 1,
                // The non-exported alias, so this works even when other apps may not control Voice Relay.
                Intent().setClassName(context, WakeControlActivity.INTERNAL_ALIAS)
                    .setAction(WakeControlActivity.ACTION_WAKE_ON)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_voice_mark)
                .setContentTitle("Wake word is off")
                .setContentText("Android stopped it. Tap to listen for ${effectiveWake(context).phrase} again.")
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            try {
                context.getSystemService(NotificationManager::class.java).notify(RESUME_NOTIFICATION_ID, n)
            } catch (_: SecurityException) {
                // No notification permission: the settings screen still shows the switch.
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val controller = WakeWordController()
    /** Whether the running mic hears anything; main thread only. */
    private val hearing = MicHearing()
    private lateinit var settings: AppSettings
    private lateinit var audio: AudioManager
    private var mic: MicThread? = null
    /** Mic threads of this instance that have not ended yet; the spotter is theirs. */
    private val liveThreads = java.util.concurrent.atomic.AtomicInteger(0)
    /** Set by [stopEverything]: the mic thread that brings [liveThreads] to 0 frees the spotter. */
    @Volatile private var stopped = false
    private var foreground = false
    private var callbacksRegistered = false

    /**
     * Built and used only by mic threads, and only while holding [spotterLock].
     * stopMic() does not wait for a mic thread; the next thread (of this or a
     * new instance) waits for the last one to release its AudioRecord, which
     * includes the last one's spotter work (a model load), so there is never
     * more than one spotter or one open recorder. It gives up after
     * [PREVIOUS_WAIT_MS] rather than wait forever on a stuck thread.
     */
    private var spotter: KeywordSpotter? = null
    private val spotterLock = Any()

    /** What to listen for; see [effectiveWake]. */
    private fun currentKeywords(): List<WakeWord.Keyword> {
        val wake = effectiveWake(this)
        if (wake.fellBack) {
            Log.w(TAG, "Wake word: the saved phrase does not fit the model, using ${WakeWord.DEFAULT_PHRASE}")
        }
        return wake.keywords
    }

    private val tick = object : Runnable {
        override fun run() {
            evaluate()
            val delay = when {
                controller.needsTicks -> TICK_MS
                controller.state == WakeWordController.State.LISTENING && Build.VERSION.SDK_INT < 31 -> POLL_MS
                else -> return
            }
            handler.postDelayed(this, delay)
        }
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            // Another app taking the mic pauses listening first, so it is not shown as "cannot hear".
            evaluate()
            checkSilenced(configs)
        }
    }

    private val modeListener: Any? = if (Build.VERSION.SDK_INT >= 31) {
        AudioManager.OnModeChangedListener { evaluate() }
    } else null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        audio = getSystemService(AudioManager::class.java)
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Wake word: stopped from the notification")
                settings.wakeWordEnabled = false
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                // Other states start the mic later, with whatever is current then.
                if (controller.state == WakeWordController.State.LISTENING) {
                    stopMic()
                    startMic()
                }
                // The phrase may have changed: show it.
                shownText = null
                updateNotification()
                return START_STICKY
            }
        }
        // ACTION_START, or a null intent when Android restarts a sticky service.
        if (!settings.wakeWordEnabled) {
            stopEverything()
            return START_NOT_STICKY
        }
        if (!goForeground()) return START_NOT_STICKY
        if (!running) {
            running = true
            stopped = false
            registerCallbacks()
            Log.i(
                TAG,
                "Wake word: service up, phrase \"${effectiveWake(this).phrase}\", sensitivity ${settings.wakeSensitivity} " +
                    "(threshold %.2f), overlay permission %s, accessibility %s".format(
                        WakeWord.threshold(settings.wakeSensitivity),
                        if (Settings.canDrawOverlays(this)) "yes" else "no",
                        if (WhisperAccessibilityService.instance != null) "on" else "off",
                    )
            )
            apply(controller.start(now(), micBusy()))
            updateNotification()
            kickTicks()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    // --- Foreground ---

    private fun goForeground(): Boolean {
        if (foreground) return true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Wake word: no microphone permission")
        }
        return try {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            foreground = true
            getSystemService(NotificationManager::class.java).cancel(RESUME_NOTIFICATION_ID)
            true
        } catch (e: RuntimeException) {
            // SecurityException (no mic permission, or not allowed from the background)
            // or ForegroundServiceStartNotAllowedException.
            Log.w(TAG, "Wake word: could not go foreground: ${e.javaClass.simpleName}")
            postResumeNotification(this)
            stopSelf()
            false
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val text = notificationText()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_voice_mark)
            .setContentTitle("Wake word")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notificationText(): String = when (controller.state) {
        WakeWordController.State.LISTENING -> MicHearing.listeningText(effectiveWake(this).phrase, hearing.deaf)
        WakeWordController.State.WAITING -> "Opening Claude"
        WakeWordController.State.BUSY -> "Paused while the microphone is in use"
        WakeWordController.State.OFF -> "Starting"
    }

    private var shownText: String? = null

    private fun updateNotification() {
        if (!foreground) return
        val text = notificationText()
        if (shownText == text) return
        shownText = text
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        } catch (_: SecurityException) {
        }
    }

    private fun stopEverything() {
        handler.removeCallbacksAndMessages(null)
        if (running) Log.i(TAG, "Wake word: service down")
        running = false
        stopped = true
        apply(controller.stop())
        stopMic()
        unregisterCallbacks()
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        // The spotter is used on the mic threads; free it only once none is left. If one
        // is still going, the last to end frees it (see MicThread). Each side writes
        // before it reads the other's value, so at least one of them frees it.
        if (liveThreads.get() == 0) releaseSpotter()
        stopSelf()
    }

    /** Frees the model. Mic threads hold [spotterLock] while they use it, so this waits for none of them. */
    private fun releaseSpotter() = synchronized(spotterLock) {
        spotter?.release()
        spotter = null
    }

    // --- Controller wiring ---

    private fun now() = SystemClock.elapsedRealtime()

    private fun evaluate() {
        if (!running) return
        apply(controller.update(now(), micBusy(), audio.isMusicActive))
    }

    private fun kickTicks() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun apply(actions: List<WakeWordController.Action>) {
        if (actions.isEmpty()) return
        for (action in actions) {
            when (action) {
                WakeWordController.Action.StartMic -> startMic()
                WakeWordController.Action.StopMic -> stopMic()
                WakeWordController.Action.LaunchAssistant -> launchAssistant()
                WakeWordController.Action.ResumeMedia -> resumeMedia()
                is WakeWordController.Action.Note -> Log.i(TAG, "Wake word: ${action.message}")
            }
        }
        updateNotification()
        kickTicks()
    }

    /**
     * True when someone else needs the mic: any other recorder (Claude, a call,
     * our own dictation button), or an audio mode other than normal. Claude's
     * voice mode sets MODE_IN_COMMUNICATION for the whole conversation, which
     * covers the gaps between its listening turns. Hotword recorders (other
     * assistants' always-on checks, a system API source) are ignored.
     */
    private fun micBusy(): Boolean {
        if (audio.mode != AudioManager.MODE_NORMAL) return true
        return audio.activeRecordingConfigurations.any {
            it.clientAudioSessionId !in ownSessions && it.clientAudioSource != HOTWORD_SOURCE
        }
    }

    private fun registerCallbacks() {
        if (callbacksRegistered) return
        audio.registerAudioRecordingCallback(recordingCallback, handler)
        if (Build.VERSION.SDK_INT >= 31) {
            audio.addOnModeChangedListener(mainExecutor, modeListener as AudioManager.OnModeChangedListener)
        }
        callbacksRegistered = true
    }

    private fun unregisterCallbacks() {
        if (!callbacksRegistered) return
        audio.unregisterAudioRecordingCallback(recordingCallback)
        if (Build.VERSION.SDK_INT >= 31) {
            audio.removeOnModeChangedListener(modeListener as AudioManager.OnModeChangedListener)
        }
        callbacksRegistered = false
    }

    // --- Actions ---

    private fun onKeyword(label: String, seconds: Float) {
        val musicActive = audio.isMusicActive
        val actions = controller.onKeyword(now(), musicActive, settings.wakeResumeMedia)
        if (actions.isEmpty()) return
        Log.i(
            TAG,
            "Wake word: detected $label (threshold %.2f, sensitivity %d, keyword %.2f s, music %s)".format(
                WakeWord.threshold(settings.wakeSensitivity), settings.wakeSensitivity, seconds,
                if (musicActive) "playing" else "not playing",
            )
        )
        apply(actions)
    }

    private fun launchAssistant() {
        val intent = Intent(WakeWord.ACTION_VOICE_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Same app either way; the accessibility service's context is the one Android
        // most clearly lets start activities from the background.
        val ctx: Context = WhisperAccessibilityService.instance ?: this
        try {
            ClaudeVoice.open(ctx)
            Log.i(TAG, "Wake word: opened Claude")
        } catch (_: ActivityNotFoundException) {
            try {
                ctx.startActivity(intent)
                Log.i(TAG, "Wake word: Claude not installed, opened the default assistant")
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "Wake word: no voice assistant to open")
            }
        }
    }

    private fun resumeMedia() {
        val t = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
        Log.i(TAG, "Wake word: sent media PLAY to resume the music")
    }

    // --- Microphone ---

    @SuppressLint("MissingPermission")
    private fun startMic() {
        if (mic != null) return
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, CHUNK * 4),
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "Wake word: AudioRecord failed: ${e.javaClass.simpleName}")
            null
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            apply(controller.onMicFailed(now()))
            return
        }
        // Known before recording starts, so our own stream never looks like someone else's.
        // The thread takes the session out again once it has released the recorder.
        ownSessions += rec.audioSessionId
        hearing.reset()
        val keywords = currentKeywords()
        mic = MicThread(rec, WakeWord.threshold(settings.wakeSensitivity), WakeWord.streamKeywords(keywords), lastStartedMic.get()).also {
            lastStartedMic.set(it)
            liveThreads.incrementAndGet()
            it.start()
        }
    }

    /**
     * Tells the mic thread to stop and returns at once; the thread stops and
     * releases its AudioRecord within about one 100 ms read. A mic started
     * meanwhile waits on its own thread for that release (see [MicThread]).
     */
    private fun stopMic() {
        val m = mic ?: return
        mic = null
        m.running = false
        handler.postDelayed({ if (m.isAlive) Log.w(TAG, "Wake word: mic thread slow to stop") }, SLOW_STOP_MS)
    }

    /**
     * Android silences a recording, rather than failing it, when another app or the
     * microphone privacy switch takes the mic. Reads that from the current mic's
     * configuration and shows it in the notification.
     */
    private fun checkSilenced(configs: List<AudioRecordingConfiguration>) {
        if (controller.state != WakeWordController.State.LISTENING) return
        val m = mic ?: return
        val config = configs.firstOrNull { it.clientAudioSessionId == m.session } ?: return
        val silenced = config.isClientSilenced
        if (hearing.onSilenced(silenced)) {
            Log.i(TAG, if (silenced) "Wake word: Android silenced the mic" else "Wake word: the mic hears again")
            updateNotification()
        }
    }

    /** The current mic thread started or stopped hearing only zeros. */
    private fun onZeros(thread: MicThread, zeros: Boolean) {
        if (mic !== thread) return
        if (hearing.onZeros(zeros)) {
            Log.w(
                TAG,
                if (zeros) "Wake word: the mic has delivered only silence for ${ZERO_CHUNKS / 10} s (another app has it, or mic access is off)"
                else "Wake word: the mic hears again"
            )
            updateNotification()
        }
    }

    /** The mic thread ended on its own (read error, model failure). Back off and retry. */
    private fun micDied(thread: MicThread) {
        if (mic !== thread) return
        mic = null
        apply(controller.onMicFailed(now()))
    }

    private fun buildSpotter(threshold: Float): KeywordSpotter {
        val started = SystemClock.elapsedRealtime()
        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "wake/model/encoder.onnx",
                    decoder = "wake/model/decoder.onnx",
                    joiner = "wake/model/joiner.onnx",
                ),
                tokens = "wake/model/tokens.txt",
                numThreads = 1,
                provider = "cpu",
                modelType = "zipformer2",
            ),
            maxActivePaths = 4,
            // Blank: the phrase's keywords come with each stream (see WakeWord).
            keywordsFile = WakeWord.EMPTY_KEYWORDS_ASSET,
            keywordsScore = WakeWord.BOOST,
            keywordsThreshold = threshold,
            numTrailingBlanks = WakeWord.TRAILING_BLANKS,
        )
        return KeywordSpotter(assets, config).also {
            Log.i(TAG, "Wake word: model loaded in ${SystemClock.elapsedRealtime() - started} ms (threshold %.2f)".format(threshold))
        }
    }

    /**
     * [keywords] is [WakeWord.streamKeywords] of the phrase to listen for.
     * [previous] is the mic thread before this one, which may still be winding
     * down after stopMic(); this one waits for it before touching the mic.
     */
    private inner class MicThread(
        private val rec: AudioRecord,
        private val threshold: Float,
        private val keywords: String,
        private var previous: Thread?,
    ) : Thread("VoiceRelay-wake") {
        @Volatile var running = true
        /** An earlier thread this one gave up waiting for; a later thread waits for it instead. */
        @Volatile var blocker: Thread? = null
        val session = rec.audioSessionId

        /**
         * Waits for every earlier mic thread to end, following [blocker]s past any that
         * gave up. Returns the one still going after [PREVIOUS_WAIT_MS], or null.
         */
        private fun waitForEarlier(): Thread? {
            var p = previous
            previous = null
            while (p != null) {
                try {
                    p.join(PREVIOUS_WAIT_MS)
                } catch (_: InterruptedException) {
                    return p
                }
                if (p.isAlive) return p
                p = (p as? MicThread)?.blocker
            }
            return null
        }

        override fun run() {
            try {
                // The earlier thread releases its AudioRecord as it ends, so wait for that
                // before starting this one: never two of our recorders at once. Always the
                // whole wait, even if this one is stopped meanwhile, so a later thread that
                // waits for this one has waited for every earlier one too.
                val stuck = waitForEarlier()
                if (stuck != null) {
                    // Give up without touching the mic: this ends through micDied, which
                    // backs off and retries, and the next thread waits for the stuck one.
                    blocker = stuck
                    Log.w(TAG, "Wake word: an earlier mic thread has not let go of the mic after ${PREVIOUS_WAIT_MS / 1000} s")
                    return
                }
                // Waits here while an earlier thread (stopped, but still loading the model) finishes.
                synchronized(spotterLock) {
                    if (!running) return
                    var kws = spotter
                    if (kws == null || kws.config.keywordsThreshold != threshold) {
                        kws?.release()
                        spotter = null
                        kws = buildSpotter(threshold)
                        spotter = kws
                    }
                    listen(kws)
                }
            } catch (e: Throwable) {
                // A missing model or native library must not take the app down with it.
                Log.e(TAG, "Wake word: listener failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                try { rec.stop() } catch (_: IllegalStateException) {}
                rec.release()
                ownSessions -= session
                if (running) handler.post { micDied(this) }
                // The service stopped while this thread was still going, so the last of its
                // threads to end frees the spotter.
                if (liveThreads.decrementAndGet() == 0 && stopped) releaseSpotter()
                // Nobody needs to wait for this one any more, unless it left a stuck one behind.
                if (blocker == null) lastStartedMic.compareAndSet(this, null)
            }
        }

        private fun listen(kws: KeywordSpotter) {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.w(TAG, "Wake word: the mic did not start")
                return
            }
            val stream = kws.createStream(keywords)
            Log.i(TAG, "Wake word: listening for ${keywords.split('/').joinToString(", ") { it.substringAfterLast('@') }}")
            val pcm = ShortArray(CHUNK)
            val samples = FloatArray(CHUNK)
            val zeroRun = ZeroRun(ZERO_CHUNKS)
            try {
                while (running) {
                    val n = rec.read(pcm, 0, CHUNK)
                    if (n < 0) {
                        Log.w(TAG, "Wake word: mic read error $n")
                        break
                    }
                    if (n == 0) continue
                    var peak = 0
                    for (i in 0 until n) {
                        val s = pcm[i].toInt()
                        samples[i] = s / 32768f
                        peak = maxOf(peak, abs(s))
                    }
                    // Android hands a recording it took the mic from pure zeros, not an error.
                    zeroRun.feed(peak)?.let { zeros -> handler.post { onZeros(this@MicThread, zeros) } }
                    stream.acceptWaveform(if (n == CHUNK) samples else samples.copyOf(n), SAMPLE_RATE)
                    while (kws.isReady(stream)) {
                        kws.decode(stream)
                        val result = kws.getResult(stream)
                        if (result.keyword.isNotEmpty()) {
                            kws.reset(stream)
                            val ts = result.timestamps
                            val seconds = if (ts.size >= 2) ts.last() - ts.first() else 0f
                            val label = result.keyword
                            handler.post { onKeyword(label, seconds) }
                        }
                    }
                }
            } finally {
                stream.release()
            }
        }
    }
}
