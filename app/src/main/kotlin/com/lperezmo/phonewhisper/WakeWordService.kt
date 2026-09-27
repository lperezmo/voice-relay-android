package com.lperezmo.phonewhisper

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
 * Listens for "hey claude" on the phone and opens Claude's voice assistant.
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
        private const val TAG = "PhoneWhisper"
        private const val ACTION_START = "com.lperezmo.phonewhisper.action.WAKE_SERVICE_START"
        private const val ACTION_STOP = "com.lperezmo.phonewhisper.action.WAKE_SERVICE_STOP"
        private const val ACTION_RELOAD = "com.lperezmo.phonewhisper.action.WAKE_SERVICE_RELOAD"
        private const val CHANNEL_ID = "wake_word"
        private const val NOTIFICATION_ID = 7001
        private const val RESUME_NOTIFICATION_ID = 7002
        private const val SAMPLE_RATE = 16000
        private const val CHUNK = 1600 // 100 ms
        /** MediaRecorder.AudioSource.HOTWORD is a system API. Other assistants' hotword checks use it. */
        private const val HOTWORD_SOURCE = 1999
        private const val TICK_MS = 250L
        private const val POLL_MS = 1000L

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

        /** Rebuilds the spotter, for a new sensitivity. No-op when not running. */
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
                        description = "Shown while Whisper LP listens for hey claude"
                        setShowBadge(false)
                    }
                )
            }
        }

        private fun postResumeNotification(context: Context) {
            ensureChannel(context)
            val tap = PendingIntent.getActivity(
                context, 1,
                Intent(context, WakeControlActivity::class.java).setAction(WakeControlActivity.ACTION_WAKE_ON)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Wake word is off")
                .setContentText("Android stopped it. Tap to listen for hey claude again.")
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
    private lateinit var settings: AppSettings
    private lateinit var audio: AudioManager
    private var mic: MicThread? = null
    private var lastMicThread: Thread? = null
    @Volatile private var ownSession = -1
    private var foreground = false
    private var callbacksRegistered = false

    /** Only touched on mic threads, one at a time (each is joined before the next starts). */
    private var spotter: KeywordSpotter? = null
    @Volatile private var spotterStale = false

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
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) = evaluate()
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
                spotterStale = true
                if (controller.state == WakeWordController.State.LISTENING) {
                    stopMic()
                    startMic()
                }
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
            registerCallbacks()
            Log.i(
                TAG,
                "Wake word: service up, sensitivity ${settings.wakeSensitivity} " +
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
        val text = when (controller.state) {
            WakeWordController.State.LISTENING -> "Listening for hey claude. Audio stays on the phone."
            WakeWordController.State.WAITING -> "Opening Claude"
            WakeWordController.State.BUSY -> "Paused while the microphone is in use"
            WakeWordController.State.OFF -> "Starting"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Wake word")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private var shownState: WakeWordController.State? = null

    private fun updateNotification() {
        if (!foreground || shownState == controller.state) return
        shownState = controller.state
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        } catch (_: SecurityException) {
        }
    }

    private fun stopEverything() {
        handler.removeCallbacksAndMessages(null)
        if (running) Log.i(TAG, "Wake word: service down")
        running = false
        apply(controller.stop())
        stopMic()
        unregisterCallbacks()
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        // The spotter is used on the mic thread; free it only once that thread is gone.
        if (lastMicThread?.isAlive != true) {
            spotter?.release()
            spotter = null
        }
        stopSelf()
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
        val own = ownSession
        return audio.activeRecordingConfigurations.any {
            it.clientAudioSessionId != own && it.clientAudioSource != HOTWORD_SOURCE
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
            ctx.startActivity(Intent(intent).setPackage(WakeWord.CLAUDE_PACKAGE))
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
        ownSession = rec.audioSessionId
        mic = MicThread(rec, WakeWord.threshold(settings.wakeSensitivity)).also {
            lastMicThread = it
            it.start()
        }
    }

    private fun stopMic() {
        val m = mic ?: return
        mic = null
        m.running = false
        m.join(1500)
        if (m.isAlive) Log.w(TAG, "Wake word: mic thread slow to stop")
        ownSession = -1
    }

    /** The mic thread ended on its own (read error, model failure). Back off and retry. */
    private fun micDied(thread: MicThread) {
        if (mic !== thread) return
        mic = null
        ownSession = -1
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
            keywordsFile = "wake/keywords.txt",
            keywordsScore = WakeWord.BOOST,
            keywordsThreshold = threshold,
            numTrailingBlanks = WakeWord.TRAILING_BLANKS,
        )
        return KeywordSpotter(assets, config).also {
            Log.i(TAG, "Wake word: model loaded in ${SystemClock.elapsedRealtime() - started} ms (threshold %.2f)".format(threshold))
        }
    }

    private inner class MicThread(private val rec: AudioRecord, private val threshold: Float) :
        Thread("PhoneWhisper-wake") {
        @Volatile var running = true

        override fun run() {
            try {
                var kws = spotter
                if (kws == null || spotterStale || kws.config.keywordsThreshold != threshold) {
                    kws?.release()
                    spotter = null
                    kws = buildSpotter(threshold)
                    spotter = kws
                    spotterStale = false
                }
                listen(kws)
            } catch (e: Throwable) {
                // A missing model or native library must not take the app down with it.
                Log.e(TAG, "Wake word: listener failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                try { rec.stop() } catch (_: IllegalStateException) {}
                rec.release()
                if (running) handler.post { micDied(this) }
            }
        }

        private fun listen(kws: KeywordSpotter) {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.w(TAG, "Wake word: the mic did not start")
                return
            }
            Log.i(TAG, "Wake word: listening")
            val stream = kws.createStream()
            val pcm = ShortArray(CHUNK)
            val samples = FloatArray(CHUNK)
            var silentChunks = 0
            var warnedSilent = false
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
                    // Android hands a background app without mic access pure zeros, not an error.
                    if (peak == 0) {
                        if (++silentChunks == 50 && !warnedSilent) {
                            warnedSilent = true
                            Log.w(TAG, "Wake word: the mic has delivered only silence for 5 s (no mic access in the background?)")
                        }
                    } else silentChunks = 0
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
