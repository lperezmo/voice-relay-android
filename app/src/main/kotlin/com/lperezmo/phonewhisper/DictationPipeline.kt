package com.lperezmo.phonewhisper

/**
 * Recording in, final text out: transcribe, then optionally clean up.
 * Blocking; call off the main thread. Holds no Android state, so it is unit-testable
 * with a fake [Transcriber] / [Cleaner].
 */
class DictationPipeline(
    private val transcriber: Transcriber,
    private val cleaner: Cleaner?,
) {
    fun interface Transcriber { fun transcribe(wav: ByteArray): OpenAiClient.Result }
    fun interface Cleaner { fun cleanup(text: String): OpenAiClient.Result }

    sealed class Outcome {
        /** [note] explains a degraded result, e.g. cleanup failed and raw text was used. */
        data class Text(val text: String, val note: String? = null) : Outcome()
        object NoSpeech : Outcome()
        /** Transcription failed; the recording is worth keeping for a retry. */
        data class Failed(val error: String) : Outcome()
    }

    fun run(wav: ByteArray): Outcome {
        val heard = transcriber.transcribe(wav)
        if (!heard.ok) {
            return if (heard.error == null) Outcome.NoSpeech else Outcome.Failed(heard.error)
        }
        val raw = heard.text!!
        val cleaner = cleaner ?: return Outcome.Text(raw)
        val cleaned = cleaner.cleanup(raw)
        return when {
            !cleaned.ok -> Outcome.Text(raw, "Cleanup failed, inserted raw text")
            !plausibleCleanup(raw, cleaned.text!!) -> Outcome.Text(raw, "Cleanup changed too much, inserted raw text")
            else -> Outcome.Text(cleaned.text)
        }
    }

    companion object {
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
