package com.lperezmo.phonewhisper

/**
 * The wake word's keywords and tuning. Android-free, so it is unit tested.
 *
 * Keywords live in `assets/wake/keywords.txt`, one per line, written as CMU
 * ARPAbet phones (the model's tokens) followed by `@LABEL`. Spelling them as
 * phones is what lets accent variants in cheaply: "hey claude" (K L AO1 D),
 * "hey cloud" (K L AW1 D) and "hey clod" (K L AA1 D) are three short lines.
 */
object WakeWord {
    const val CLAUDE_PACKAGE = "com.anthropic.claude"

    /** Opens the voice assistant; the constant is hidden in the SDK's Intent class. */
    const val ACTION_VOICE_ASSIST = "android.intent.action.VOICE_ASSIST"

    /** Per-keyword boost for the decoder. 1.5 is the sherpa-onnx default. */
    const val BOOST = 1.5f

    /** Blank frames after the last phone before a keyword fires. 1 answers fastest. */
    const val TRAILING_BLANKS = 1

    const val MIN_SENSITIVITY = 1
    const val MAX_SENSITIVITY = 7
    const val DEFAULT_SENSITIVITY = 5

    /**
     * The spotter's trigger threshold for a sensitivity level: 1 is strictest
     * (0.40), 7 loosest (0.10), and the default 5 is 0.20. Tested offline on
     * synthesized "hey claude" and "hey cloud" with noise and at low volume:
     * 0.20 kept every clean positive and most noisy ones, while the false
     * triggers it did produce ("hey clown", "okay cloudy") sound the same at
     * every threshold, so a stricter setting mostly costs misses.
     */
    fun threshold(sensitivity: Int): Float {
        val level = sensitivity.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
        return (45 - 5 * level) / 100f
    }

    data class Keyword(val phones: List<String>, val label: String)

    /** Parses keywords.txt. Blank lines and lines starting with '#' are skipped. */
    fun parseKeywords(text: String): List<Keyword> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { line ->
            val parts = line.split(Regex("\\s+"))
            val label = parts.lastOrNull { it.startsWith("@") }?.removePrefix("@")
            require(!label.isNullOrEmpty()) { "keyword without @LABEL: $line" }
            val phones = parts.filter { !it.startsWith("@") && !it.startsWith(":") && !it.startsWith("#") }
            require(phones.isNotEmpty()) { "keyword without phones: $line" }
            Keyword(phones, label)
        }

    /** "HEY_CLOUD" -> "hey cloud", for logs and the notification. */
    fun spoken(label: String) = label.replace('_', ' ').lowercase()
}

/**
 * When the wake word listens, and what happens around a detection. Pure logic
 * with the clock passed in, so every transition is unit tested; the service
 * turns the returned [Action]s into mic, intent and media-key calls.
 *
 *   OFF --start--> LISTENING --keyword--> WAITING (mic off, Claude launched)
 *   WAITING --mic busy--> BUSY          WAITING --nobody took the mic in time--> LISTENING
 *   LISTENING --mic busy--> BUSY        BUSY --free for [releaseDelayMs], cooldown over--> LISTENING
 *
 * "Mic busy" means another recorder is active or the audio mode is not normal.
 * Claude's voice mode holds MODE_IN_COMMUNICATION for the whole conversation
 * and records in turns inside it, so the mode is what spans the gaps between
 * turns; a phone call pauses listening the same way.
 *
 * If music was playing when the keyword fired, it is resumed with a media PLAY
 * key once the assistant is done, unless something is already playing again.
 */
class WakeWordController(
    private val cooldownMs: Long = 4_000,
    private val releaseDelayMs: Long = 1_000,
    private val assistantTimeoutMs: Long = 10_000,
    private val micRetryMs: Long = 5_000,
) {
    enum class State { OFF, LISTENING, WAITING, BUSY }

    sealed interface Action {
        data object StartMic : Action
        data object StopMic : Action
        data object LaunchAssistant : Action
        data object ResumeMedia : Action
        data class Note(val message: String) : Action
    }

    var state = State.OFF
        private set

    /** Nothing restarts the mic before this time (after a trigger or a failed mic start). */
    private var quietUntil = Long.MIN_VALUE
    private var triggeredAt = 0L
    private var freeSince: Long? = null
    private var resumeMediaPending = false

    /** The service polls [update] while this is true; LISTENING relies on callbacks. */
    val needsTicks get() = state == State.WAITING || state == State.BUSY

    fun start(now: Long, micBusy: Boolean): List<Action> {
        if (state != State.OFF) return emptyList()
        return if (micBusy) {
            state = State.BUSY
            freeSince = null
            listOf(Action.Note("mic in use, waiting"))
        } else {
            state = State.LISTENING
            listOf(Action.StartMic)
        }
    }

    fun stop(): List<Action> {
        val wasListening = state == State.LISTENING
        state = State.OFF
        freeSince = null
        resumeMediaPending = false
        return if (wasListening) listOf(Action.StopMic) else emptyList()
    }

    /** A keyword fired. [musicActive] is whether media was playing at that moment. */
    fun onKeyword(now: Long, musicActive: Boolean, resumeMediaEnabled: Boolean): List<Action> {
        if (state != State.LISTENING) return emptyList()
        state = State.WAITING
        triggeredAt = now
        quietUntil = now + cooldownMs
        freeSince = null
        // A second trigger while music is still paused from the first keeps the plan to resume it.
        resumeMediaPending = resumeMediaPending || (resumeMediaEnabled && musicActive)
        return listOf(Action.StopMic, Action.LaunchAssistant)
    }

    /** The mic could not be opened. Back off and retry. */
    fun onMicFailed(now: Long): List<Action> {
        if (state != State.LISTENING) return emptyList()
        state = State.BUSY
        freeSince = null
        quietUntil = maxOf(quietUntil, now + micRetryMs)
        return listOf(Action.Note("mic unavailable, retrying in ${micRetryMs / 1000} s"))
    }

    /** Re-evaluate with the current mic and media state. Call on callbacks and ticks. */
    fun update(now: Long, micBusy: Boolean, musicActive: Boolean): List<Action> = when (state) {
        State.OFF -> emptyList()
        State.LISTENING -> if (micBusy) {
            state = State.BUSY
            freeSince = null
            listOf(Action.StopMic, Action.Note("mic in use elsewhere, pausing"))
        } else emptyList()
        State.WAITING -> when {
            micBusy -> {
                state = State.BUSY
                freeSince = null
                listOf(Action.Note("assistant took the mic after ${now - triggeredAt} ms"))
            }
            now - triggeredAt >= assistantTimeoutMs ->
                listOf(Action.Note("assistant did not open the mic within ${assistantTimeoutMs / 1000} s")) +
                    resume(musicActive)
            else -> emptyList()
        }
        State.BUSY -> when {
            micBusy -> {
                freeSince = null
                emptyList()
            }
            else -> {
                val since = freeSince ?: now.also { freeSince = it }
                if (now - since >= releaseDelayMs && now >= quietUntil) resume(musicActive) else emptyList()
            }
        }
    }

    private fun resume(musicActive: Boolean): List<Action> {
        state = State.LISTENING
        freeSince = null
        val actions = mutableListOf<Action>(Action.StartMic)
        if (resumeMediaPending) {
            resumeMediaPending = false
            actions += if (musicActive) Action.Note("music already playing, not resuming")
            else Action.ResumeMedia
        }
        return actions
    }
}
