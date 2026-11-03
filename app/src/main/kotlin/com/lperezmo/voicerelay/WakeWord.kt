package com.lperezmo.voicerelay

import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * The wake word's keywords and tuning. Android-free, so it is unit tested.
 *
 * A keyword is a line of CMU ARPAbet phones (the model's tokens) followed by
 * `@LABEL`. Spelling them as phones is what lets accent variants in cheaply:
 * "hey claude" (K L AO1 D), "hey cloud" (K L AW1 D) and "hey clod" (K L AA1 D)
 * are three short lines.
 *
 * The phrase is yours to choose. A typed phrase becomes phones through the CMU
 * Pronouncing Dictionary (`assets/wake/cmudict.txt.gzip`, built by
 * scripts/build_cmudict.py): every pronunciation it lists for each word, in
 * every combination, is a keyword of its own. Words it lacks can be spelled as
 * phones by hand. Either way each phone is checked against the model's
 * tokens.txt before it is saved or used.
 *
 * The keywords go to the spotter per stream, not in its keywords file:
 * sherpa-onnx adds a stream's keywords to the file's, so the file
 * (`assets/wake/empty_keywords.txt`) is blank and a new phrase only needs a new
 * stream, not a new model load.
 */
object WakeWord {
    const val CLAUDE_PACKAGE = "com.anthropic.claude"

    /** Opens the voice assistant; the constant is hidden in the SDK's Intent class. */
    const val ACTION_VOICE_ASSIST = "android.intent.action.VOICE_ASSIST"

    /**
     * Claude's assistant overlay opens in text chat since its 2026-10-29 update, so after
     * the wake word opens it the accessibility service taps the button announced with this
     * label to start voice. Matched whole and case-blind, never by screen position.
     */
    const val VOICE_MODE_LABEL = "Voice Mode"

    /** How long to wait for Claude's overlay to show the Voice Mode button. */
    const val VOICE_MODE_WAIT_MS = 2_500L

    fun isVoiceModeLabel(label: CharSequence?): Boolean =
        label != null && label.toString().trim().equals(VOICE_MODE_LABEL, ignoreCase = true)

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

    data class Keyword(val phones: List<String>, val label: String) {
        /** The line sherpa-onnx reads: the phones, then `@LABEL`. */
        val line: String get() = (phones + "@$label").joinToString(" ")
    }

    /** One way to say a word. [spelling] names it in the keyword's label. */
    data class Pronunciation(val spelling: String, val phones: List<String>)

    /** The phrase the app ships with, and what Reset to default restores. */
    const val DEFAULT_PHRASE = "hey claude"

    /**
     * The keywords for [DEFAULT_PHRASE]: CMUdict's "hey claude", plus "cloud"
     * and "clod" from [ACCENT_VARIANTS]. Built in, so the default never waits on
     * the dictionary; a test checks that the dictionary gives the same lines.
     */
    val DEFAULT_KEYWORDS = listOf(
        Keyword(listOf("HH", "EY1", "K", "L", "AO1", "D"), "HEY_CLAUDE"),
        Keyword(listOf("HH", "EY1", "K", "L", "AW1", "D"), "HEY_CLOUD"),
        Keyword(listOf("HH", "EY1", "K", "L", "AA1", "D"), "HEY_CLOD"),
    )

    /**
     * Pronunciations CMUdict does not list but accents produce. "Claude" comes
     * out as "cloud" or "clod" for many speakers, in any phrase that uses it.
     */
    val ACCENT_VARIANTS: Map<String, List<Pronunciation>> = mapOf(
        "claude" to listOf(
            Pronunciation("cloud", listOf("K", "L", "AW1", "D")),
            Pronunciation("clod", listOf("K", "L", "AA1", "D")),
        ),
    )

    /** Most keyword lines one phrase turns into. Each costs a little decoding and a little false-trigger risk. */
    const val MAX_KEYWORDS = 16

    /** Where the spotter's keywords file is, in assets. Deliberately blank; see the class comment. */
    const val EMPTY_KEYWORDS_ASSET = "wake/empty_keywords.txt"
    /** Gzipped, and named .gzip because the Android build un-gzips .gz assets while packaging. */
    const val DICTIONARY_ASSET = "wake/cmudict.txt.gzip"
    const val TOKENS_ASSET = "wake/model/tokens.txt"

    /** What a typed phrase (and maybe hand-entered phones) came to. */
    sealed interface Result {
        /** Usable: [phrase] is the tidied phrase, [keywords] its lines, first the main pronunciation. */
        data class Ready(val phrase: String, val keywords: List<Keyword>) : Result

        /** Words the dictionary does not have, in phrase order. */
        data class UnknownWords(val words: List<String>) : Result

        /** Phones that are not model tokens, each with a suggestion when one is obvious. */
        data class UnknownPhones(val phones: List<String>, val suggestions: Map<String, String>) : Result

        /** Pronunciations without a vowel: nothing to anchor them, so they would fire on any speech. */
        data class NoVowel(val pronunciations: List<String>) : Result

        /** More hand-entered pronunciations than [MAX_KEYWORDS]. */
        data class TooMany(val count: Int) : Result

        /** No words (or no phones) at all. */
        data object Empty : Result
    }

    /**
     * The words of a typed phrase, lowercased. Letters, digits and apostrophes
     * make up words; anything else (spaces, commas, hyphens) separates them.
     * "Hey, Jarvis!" -> [hey, jarvis]; "O'Brien" -> [o'brien]. Keyboards often
     * type a curly apostrophe (or a modifier letter or accent that looks like
     * one), which CMUdict spells as a straight one.
     */
    fun words(phrase: String): List<String> =
        phrase.lowercase().replace(Regex("[‘’ʼ`´]"), "'").split(Regex("[^\\p{L}\\p{N}']+")).map { it.trim('\'') }.filter { it.isNotEmpty() }

    /** Most characters the hand-entered phones field takes: [MAX_KEYWORDS] lines of a long phrase. */
    const val MAX_PHONES_TEXT = 1200

    /** "  Hey,  Jarvis! " -> "hey jarvis": how a phrase is stored and shown. */
    fun tidy(phrase: String) = words(phrase).joinToString(" ")

    /** The model's phones: tokens.txt's first column, without `<blk>`, `<unk>` and the like. */
    fun modelTokens(tokensTxt: String): Set<String> =
        tokensTxt.lines().map { it.trim().substringBefore(' ') }.filter { it.isNotEmpty() && !it.startsWith("<") }.toSet()

    /**
     * Looks [words] up in the gzipped dictionary (`word PHONES` lines, a word
     * once per pronunciation, `;;;` lines are comments). Streams through it
     * without keeping it in memory. Words it lacks are absent from the result.
     */
    fun lookup(gzipped: InputStream, words: Collection<String>): Map<String, List<List<String>>> {
        val wanted = words.toSet()
        val found = LinkedHashMap<String, MutableList<List<String>>>()
        if (wanted.isEmpty()) return found
        GZIPInputStream(gzipped).bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.startsWith(";;;")) continue
                val space = line.indexOf(' ')
                if (space <= 0) continue
                val word = line.substring(0, space)
                if (word !in wanted) continue
                val phones = line.substring(space + 1).trim().split(' ').filter { it.isNotEmpty() }
                if (phones.isNotEmpty()) found.getOrPut(word) { mutableListOf() } += phones
            }
        }
        return found
    }

    /**
     * Turns a phrase into keywords with the dictionary: every pronunciation of
     * every word (plus [ACCENT_VARIANTS]), combined, capped at [MAX_KEYWORDS].
     * [dictionary] needs only the phrase's words; see [lookup].
     */
    fun fromDictionary(phrase: String, dictionary: Map<String, List<List<String>>>, tokens: Set<String>): Result {
        val words = words(phrase)
        if (words.isEmpty()) return Result.Empty
        val missing = words.filter { dictionary[it].isNullOrEmpty() }.distinct()
        if (missing.isNotEmpty()) return Result.UnknownWords(missing)
        val choices = words.map { w ->
            dictionary.getValue(w).map { Pronunciation(w, it) } + ACCENT_VARIANTS[w].orEmpty()
        }
        return checked(Result.Ready(words.joinToString(" "), combine(choices)), tokens)
    }

    /**
     * Turns hand-entered phones into keywords for [phrase], which names them.
     * One pronunciation per line (or separated by '/' or ','), phones separated
     * by spaces, in CMUdict's ARPAbet with stress digits on vowels: "HH EY1 JH AA1 R V IH0 S".
     * Case does not matter. At most [MAX_KEYWORDS] pronunciations.
     */
    fun fromPhones(phrase: String, phones: String, tokens: Set<String>): Result {
        val words = words(phrase)
        val lines = phones.split('\n', '/', ',')
            .map { line -> line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.uppercase() } }
            .filter { it.isNotEmpty() }
        if (words.isEmpty() || lines.isEmpty()) return Result.Empty
        if (lines.size > MAX_KEYWORDS) return Result.TooMany(lines.size)
        val spelling = words.joinToString("_")
        val keywords = labelled(lines.map { listOf(Pronunciation(spelling, it)) })
        return checked(Result.Ready(words.joinToString(" "), keywords), tokens)
    }

    /**
     * Every phone must be a model token: sherpa-onnx refuses the whole list
     * otherwise. And every keyword needs a vowel: a lone "S" or "SH" matches a
     * little of almost any speech and would fire all the time.
     */
    private fun checked(ready: Result.Ready, tokens: Set<String>): Result {
        val unknown = ready.keywords.flatMap { it.phones }.filter { it !in tokens }.distinct()
        if (unknown.isNotEmpty()) {
            // The usual slip: a vowel without its stress digit (EY for EY1).
            val suggestions = unknown.mapNotNull { p -> listOf("1", "0", "2").map { p + it }.firstOrNull { it in tokens }?.let { p to it } }.toMap()
            return Result.UnknownPhones(unknown, suggestions)
        }
        val voiceless = ready.keywords.filter { syllables(it.phones) == 0 }
        if (voiceless.isNotEmpty()) return Result.NoVowel(voiceless.map { it.phones.joinToString(" ") })
        return ready
    }

    /**
     * One keyword per combination of the words' pronunciations: first the one
     * that takes every word's first pronunciation, then those that swap in one
     * alternative, then two, and so on, up to [MAX_KEYWORDS]. A phone sequence
     * that repeats is listed once.
     */
    fun combine(choices: List<List<Pronunciation>>): List<Keyword> {
        val combos = mutableListOf<List<Pronunciation>>()
        fun pick(i: Int, swapsLeft: Int, picked: List<Pronunciation>) {
            if (combos.size >= MAX_KEYWORDS * 4) return
            if (i == choices.size) {
                if (swapsLeft == 0) combos += picked
                return
            }
            pick(i + 1, swapsLeft, picked + choices[i][0])
            if (swapsLeft > 0) for (j in 1 until choices[i].size) pick(i + 1, swapsLeft - 1, picked + choices[i][j])
        }
        for (swaps in 0..choices.size) {
            pick(0, swaps, emptyList())
            if (combos.distinctBy { c -> c.flatMap { it.phones } }.size >= MAX_KEYWORDS) break
        }
        return labelled(combos).take(MAX_KEYWORDS)
    }

    /** Labels from the spellings ("HEY_CLOUD"), numbered when two would clash; duplicates dropped. */
    private fun labelled(combos: List<List<Pronunciation>>): List<Keyword> {
        val seen = mutableSetOf<List<String>>()
        val labels = mutableMapOf<String, Int>()
        return combos.mapNotNull { combo ->
            val phones = combo.flatMap { it.phones }
            if (!seen.add(phones)) return@mapNotNull null
            val base = combo.joinToString("_") { p -> p.spelling.uppercase().filter { it.isLetterOrDigit() || it == '_' } }
                .ifEmpty { "WAKE" }
            val n = (labels[base] ?: 0) + 1
            labels[base] = n
            Keyword(phones, if (n == 1) base else "${base}_$n")
        }
    }

    /** The keywords as one string for KeywordSpotter.createStream: lines joined by '/'. */
    fun streamKeywords(keywords: List<Keyword>) = keywords.joinToString("/") { it.line }

    /**
     * What the listener actually uses. [fellBack] is true when a phrase was saved
     * but its keywords are no longer usable, so the default is heard instead.
     */
    data class Effective(val phrase: String, val keywords: List<Keyword>, val fellBack: Boolean)

    /**
     * The saved phrase and keywords (lines, as [Keyword.line] writes them), or
     * [DEFAULT_PHRASE] and [DEFAULT_KEYWORDS] when nothing usable is saved:
     * unreadable, a phone the model lacks, a keyword without a vowel, more
     * than [MAX_KEYWORDS] lines, or no phrase to show.
     */
    fun effective(savedPhrase: String, savedKeywords: String, tokens: Set<String>): Effective {
        if (savedKeywords.isBlank()) return Effective(DEFAULT_PHRASE, DEFAULT_KEYWORDS, fellBack = false)
        val parsed = parseKeywordsOrEmpty(savedKeywords)
        val usable = parsed.isNotEmpty() && parsed.size <= MAX_KEYWORDS &&
            parsed.all { k -> syllables(k.phones) > 0 && k.phones.all { p -> p in tokens } }
        val phrase = tidy(savedPhrase)
        return if (usable && phrase.isNotEmpty()) Effective(phrase, parsed, fellBack = false)
        else Effective(DEFAULT_PHRASE, DEFAULT_KEYWORDS, fellBack = true)
    }

    /** True when the keywords include the [ACCENT_VARIANTS] ("hey cloud", "hey clod"). */
    fun hasAccentVariants(keywords: List<Keyword>): Boolean {
        val accents = ACCENT_VARIANTS.values.flatten().map { it.spelling.uppercase() }.toSet()
        return keywords.any { k -> k.label.split('_').any { it in accents } }
    }

    /** Syllables in a phone sequence: ARPAbet vowels are the phones with a stress digit. */
    fun syllables(phones: List<String>) = phones.count { it.lastOrNull()?.isDigit() == true }

    /** A caution for a phrase that is short or long enough to work badly, or null. */
    fun advice(keywords: List<Keyword>): String? {
        val n = syllables(keywords.firstOrNull()?.phones.orEmpty())
        return when {
            n < 2 -> "Only $n syllable${if (n == 1) "" else "s"}: expect it to trigger by accident. 2 to 4 syllables work best."
            n > 5 -> "$n syllables: long phrases are easy to say slightly wrong and get missed. 2 to 4 work best."
            else -> null
        }
    }

    /** Parses keyword lines. Blank lines and lines starting with '#' are skipped. */
    fun parseKeywords(text: String): List<Keyword> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { line ->
            val parts = line.split(Regex("\\s+"))
            val label = parts.lastOrNull { it.startsWith("@") }?.removePrefix("@")
            require(!label.isNullOrEmpty()) { "keyword without @LABEL: $line" }
            val phones = parts.filter { !it.startsWith("@") && !it.startsWith(":") && !it.startsWith("#") }
            require(phones.isNotEmpty()) { "keyword without phones: $line" }
            Keyword(phones, label)
        }

    /** [parseKeywords], or an empty list for text it rejects. */
    fun parseKeywordsOrEmpty(text: String): List<Keyword> = try {
        parseKeywords(text)
    } catch (_: IllegalArgumentException) {
        emptyList()
    }

    /** "HEY_CLOUD" -> "hey cloud", for logs. */
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
