package com.lperezmo.voicerelay

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 16 kHz mono PCM16 microphone capture on a background thread.
 *
 * Capped at [maxSeconds]; when the cap is reached, [onLimit] fires on the
 * capture thread and the caller is expected to stop. [capAt] lowers the cap
 * while recording (the screen locked mid-dictation).
 */
class Recorder(
    maxSeconds: Int = 600,
    private val onLimit: () -> Unit = {},
    /** Each captured PCM chunk as it arrives, on the capture thread (live transcription). */
    private val onChunk: ((ByteArray, Int) -> Unit)? = null,
    /** The microphone stopped delivering audio mid-recording. Fires once, on the capture thread. */
    private val onError: () -> Unit = {},
) {
    @Volatile
    var maxSeconds: Int = maxSeconds
        private set
    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var buffer = ByteArrayOutputStream()

    /** Starts capture. Throws SecurityException without RECORD_AUDIO, IllegalStateException if the mic is unavailable. */
    @SuppressLint("MissingPermission")
    fun start() {
        check(!running.get()) { "already recording" }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Microphone unavailable")
        }
        buffer = ByteArrayOutputStream()
        record = rec
        rec.startRecording()
        // Another app holding the mic (a call) leaves it stopped without throwing.
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            record = null
            throw IllegalStateException("Microphone is in use by another app")
        }
        running.set(true)
        val out = buffer
        thread = Thread({
            val chunk = ByteArray(minBuf)
            while (running.get()) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) {
                    out.write(chunk, 0, n)
                    onChunk?.invoke(chunk, n)
                } else if (n < 0) {
                    // A read error (the audio server restarted, the mic was taken away) repeats
                    // forever without blocking; stop instead of spinning.
                    onError()
                    break
                }
                if (out.size() >= maxSeconds * SAMPLE_RATE * 2) {
                    onLimit()
                    break
                }
            }
        }, "VoiceRelay-mic").apply { start() }
    }

    /** Stops capture and returns the PCM captured so far. */
    fun stop(): ByteArray {
        running.set(false)
        thread?.join(1000)
        thread = null
        record?.run {
            try { stop() } catch (_: IllegalStateException) {}
            release()
        }
        record = null
        return buffer.toByteArray()
    }

    val isRecording get() = running.get()

    /** Seconds captured so far. */
    val elapsedSeconds get() = buffer.size() / (SAMPLE_RATE * 2.0)

    /** Lowers the cap (never raises it). */
    fun capAt(seconds: Int) {
        if (seconds < maxSeconds) maxSeconds = seconds
    }

    companion object {
        const val SAMPLE_RATE = 16000

        fun durationSeconds(pcm: ByteArray) = pcm.size / (SAMPLE_RATE * 2.0)
    }
}
