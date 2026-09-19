package com.lperezmo.phonewhisper

/**
 * Recording in, final text out: transcribe, then optionally clean up.
 * Blocking; call off the main thread. Holds no Android state, so it is unit-testable
 * with a fake [Transcriber] / [Cleaner].
 */
class DictationPipeline(
    private val transcriber: Transcriber,
    private val cleaner: Cleaner?,
    /** Receives one metadata line per run (sizes and timings, never text). */
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun interface Transcriber { fun transcribe(audio: OpenAiClient.Audio): OpenAiClient.Result }
    fun interface Cleaner { fun cleanup(text: String): OpenAiClient.Result }

    sealed class Outcome {
        /** [note] explains a degraded result, e.g. cleanup failed and raw text was used. */
        data class Text(val text: String, val note: String? = null) : Outcome()
        object NoSpeech : Outcome()
        /** Transcription failed; the recording is worth keeping for a retry. */
        data class Failed(val error: String) : Outcome()
    }

    fun run(audio: OpenAiClient.Audio): Outcome {
        val t0 = clock()
        val heard = transcriber.transcribe(audio)
        val t1 = clock()
        val timing = "upload ${audio.filename} ${audio.bytes.size / 1024} KB, transcribe ${t1 - t0} ms"
        if (!heard.ok) {
            log("$timing, ${if (heard.error == null) "no speech" else "failed (HTTP ${heard.httpCode})"}")
            return if (heard.error == null) Outcome.NoSpeech else Outcome.Failed(heard.error)
        }
        val raw = heard.text!!
        val cleaner = cleaner ?: return Outcome.Text(raw).also { log("$timing, cleanup off") }
        val cleaned = cleaner.cleanup(raw)
        log("$timing, cleanup ${clock() - t1} ms${if (cleaned.ok) "" else " (failed)"}")
        return when {
            !cleaned.ok -> Outcome.Text(raw, "Cleanup failed, inserted raw text")
            !plausibleCleanup(raw, cleaned.text!!) -> Outcome.Text(raw, "Cleanup changed too much, inserted raw text")
            else -> Outcome.Text(cleaned.text)
        }
    }

    companion object {
        /**
         * Grammar fix for text the user typed. Failures are reported rather than
         * silently keeping the original, since the user explicitly asked for a fix.
         */
        fun fixTyped(text: String, cleaner: Cleaner): Outcome {
            if (text.isBlank()) return Outcome.NoSpeech
            val fixed = cleaner.cleanup(text)
            return when {
                !fixed.ok -> Outcome.Failed(fixed.error ?: "Empty response")
                !plausibleCleanup(text, fixed.text!!) -> Outcome.Failed("The fix changed too much, kept your text")
                else -> Outcome.Text(fixed.text)
            }
        }

        /**
         * Rejects cleanups that are clearly not a cleanup: an answer to a dictated
         * question (much longer) or a lost sentence (much shorter). Command mode
         * legitimately shortens ("ssh into morty at rubicon" -> "ssh morty@rubicon"),
         * so the lower bound only applies to longer dictation.
         */
        fun plausibleCleanup(raw: String, cleaned: String): Boolean {
            if (cleaned.isBlank()) return false
            if (cleaned.length > raw.length * 2 + 40) return false
            if (raw.length >= 60 && cleaned.length < raw.length * 0.35) return false
            return true
        }
    }
}
