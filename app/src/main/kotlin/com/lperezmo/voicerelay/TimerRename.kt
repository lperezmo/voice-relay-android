package com.lperezmo.voicerelay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.util.Log
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The rules for renaming a timer from its row in the timer list: which icons show,
 * when the microphone is refused, and what to say after a spoken name. Pure Kotlin,
 * so it is unit tested; [TimerRenameVoice] is the glue.
 */
object TimerRename {

    /** Which of the two icons a timer's row shows. */
    data class Icons(val pencil: Boolean, val mic: Boolean)

    /** The pencil needs only the setting; the microphone also needs an OpenAI key. */
    fun icons(settingOn: Boolean, openAiKeySet: Boolean) = Icons(pencil = settingOn, mic = settingOn && openAiKeySet)

    const val SAY_OFF = "Rename timers is off in Voice Relay."
    const val SAY_NO_PERMISSION = "Allow the microphone for Voice Relay first."
    const val SAY_NO_KEY = "Set your OpenAI API key in Voice Relay"
    const val SAY_LISTENING = "Say the new name."
    const val SAY_NO_NAME = "I didn't catch a name."
    const val SAY_CANCELLED = "Cancelled, the name was not changed."
    const val SAY_GONE = "That timer has ended."
    const val SAY_NO_NETWORK = "I couldn't reach the internet, so the name was not changed."
    const val SAY_KEY_REJECTED = "The OpenAI key was not accepted. Check it in Voice Relay."
    const val SAY_FAILED = "I couldn't turn that into text, so the name was not changed."

    /**
     * Why a tap on the microphone cannot listen now, or null when it can. The microphone
     * is busy while the dictate button records or another short recording holds [MicGuard].
     */
    fun refusal(settingOn: Boolean, micAllowed: Boolean, openAiKeySet: Boolean, dictating: Boolean, micHeld: Boolean): String? = when {
        !settingOn -> SAY_OFF
        !micAllowed -> SAY_NO_PERMISSION
        !openAiKeySet -> SAY_NO_KEY
        dictating || micHeld -> MicGuard.SAY_BUSY
        else -> null
    }

    /** The new name in what was heard, tidied ([TimerText.nameFromSpeech]), or null when there is none. */
    fun nameFrom(heard: ShortListen.Heard): String? =
        (heard as? ShortListen.Heard.Text)?.let { TimerText.nameFromSpeech(it.text) }

    /** What to show when [heard] gives no name. */
    fun lineFor(heard: ShortListen.Heard): String = when (heard) {
        is ShortListen.Heard.Text -> SAY_NO_NAME
        ShortListen.Heard.Nothing -> SAY_NO_NAME
        ShortListen.Heard.MicBusy -> MicGuard.SAY_BUSY
        ShortListen.Heard.NoNetwork -> SAY_NO_NETWORK
        ShortListen.Heard.KeyRejected -> SAY_KEY_REJECTED
        is ShortListen.Heard.Failed -> SAY_FAILED
    }

    /** What to show after a rename went through. */
    fun renamed(name: String) = "Renamed to $name"
}

/**
 * The microphone in a timer's row: records one short phrase ([ShortListen], with a tone
 * and an end-of-speech stop), transcribes it with OpenAI only (no Claude, and not counted
 * in the "hey timer" daily limit) and makes it the timer's name. One listen at a time,
 * holding [MicGuard] while it lasts. The button's ring shows listening and
 * transcribing; results show as the button's feedback. Main thread, apart from the
 * listen itself.
 */
class TimerRenameVoice(
    private val context: Context,
    private val settings: AppSettings,
    private val handler: Handler,
    private val openAiKey: () -> String,
    /** True while the dictate button records. */
    private val dictating: () -> Boolean,
    private val showFeedback: (text: String, durationMs: Long) -> Unit,
    /** Each step, for the button's ring. Main thread. */
    private val onStage: (ButtonRing.Voice) -> Unit,
    /** The listen is over, however it ended. Main thread. */
    private val onDone: () -> Unit,
) {
    /** The listen in progress, or null. Main thread only. */
    private var ear: ShortListen? = null
    @Volatile private var recording = false
    /**
     * Gives up sooner than dictation does (as "hey timer" does), so a bad connection cannot
     * keep [MicGuard], and with it "hey timer", busy for minutes.
     */
    private val http by lazy {
        OpenAiClient.sharedHttp.newBuilder().callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS).build()
    }
    private val audio = context.getSystemService(AudioManager::class.java)

    /** True while a listen is in progress (recording or transcribing). */
    val isBusy: Boolean get() = ear != null

    /**
     * A tap on a timer's microphone: starts listening for its new name. A tap while a
     * listen is in progress cancels it instead.
     */
    fun tap(id: Int) {
        ear?.let {
            it.cancel()
            showFeedback(TimerRename.SAY_CANCELLED, FEEDBACK_MS)
            return
        }
        val micAllowed = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val key = openAiKey()
        TimerRename.refusal(settings.timerRename, micAllowed, key.isNotBlank(), dictating(), MicGuard.holder() != null)?.let {
            showFeedback(it, FEEDBACK_MS)
            return
        }
        if (!MicGuard.tryAcquire(MicGuard.TIMER_RENAME)) {
            showFeedback(MicGuard.SAY_BUSY, FEEDBACK_MS)
            return
        }
        val listener = ShortListen(
            context, settings, http, UsageStore(context),
            onStage = { step -> handler.post { stage(step) } },
            logName = "Timer rename",
        )
        ear = listener
        thread(name = "VoiceRelay-rename") {
            var heard: ShortListen.Heard = ShortListen.Heard.Nothing
            // Pauses music while it listens, so the music is not recorded and the end of speech is heard.
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            try {
                audio?.requestAudioFocus(focus)
                heard = listener.listen(key)
            } catch (e: Exception) {
                Log.w(TAG, "Timer rename: failed: ${e.javaClass.simpleName}")
                heard = ShortListen.Heard.Failed(-1)
            } finally {
                recording = false
                audio?.abandonAudioFocusRequest(focus)
                MicGuard.release(MicGuard.TIMER_RENAME)
                handler.post { finish(id, listener, heard) }
            }
        }
    }

    /**
     * Ends a recording in progress now and uses what was said so far, as when the speaker
     * stops. False when nothing is being recorded.
     */
    fun stopEarly(): Boolean {
        val listener = ear ?: return false
        if (!recording) return false
        listener.stop()
        return true
    }

    /** The service is going away: give up any listen in progress. */
    fun cancel() {
        ear?.cancel()
    }

    private fun stage(step: ShortListen.Stage) {
        val listener = ear ?: return
        if (listener.isCancelled) return
        recording = step == ShortListen.Stage.LISTENING
        if (recording) showFeedback(TimerRename.SAY_LISTENING, FEEDBACK_MS)
        onStage(if (recording) ButtonRing.Voice.LISTENING else ButtonRing.Voice.TRANSCRIBING)
    }

    private fun finish(id: Int, listener: ShortListen, heard: ShortListen.Heard) {
        if (ear === listener) ear = null
        onDone()
        if (listener.isCancelled) return
        val name = TimerRename.nameFrom(heard) ?: return showFeedback(TimerRename.lineFor(heard), FEEDBACK_MS)
        val timers = TimerController.get(context)
        if (timers.timers().none { it.id == id }) return showFeedback(TimerRename.SAY_GONE, FEEDBACK_MS)
        // Null here means the name was already that: it still reads as done.
        val entry = timers.rename(id, name)
        showFeedback(TimerRename.renamed(entry?.label ?: name), FEEDBACK_MS)
    }

    private companion object {
        const val TAG = "VoiceRelay"
        const val FEEDBACK_MS = 2400L
        /** One transcription request; with the one retry on a 400, about a minute at most. */
        const val CALL_TIMEOUT_S = 30L
    }
}
