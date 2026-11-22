package com.lperezmo.voicerelay

import com.lperezmo.voicerelay.WakeWordController.Action
import com.lperezmo.voicerelay.WakeWordController.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WakeWordTest {

    // --- Keywords file and tuning ---

    /** Gradle runs unit tests from the module directory. */
    private val assets = File("src/main/assets/wake")

    /** Every CMUdict phone: 15 vowels with 3 stresses and 24 consonants, as the model's tokens.txt has them. */
    private val arpabet: Set<String> =
        "AA AE AH AO AW AY EH ER EY IH IY OW OY UH UW".split(" ").flatMap { v -> listOf("${v}0", "${v}1", "${v}2") }.toSet() +
            "B CH D DH F G HH JH K L M N NG P R S SH T TH V W Y Z ZH".split(" ")

    private fun dictionary(vararg words: String) =
        File(assets, "cmudict.txt.gzip").inputStream().use { WakeWord.lookup(it, words.toList()) }

    private fun ready(r: WakeWord.Result) = r as? WakeWord.Result.Ready ?: throw AssertionError("not ready: $r")

    @Test fun `the default keywords cover claude and the cloud and clod accents`() {
        val byLabel = WakeWord.DEFAULT_KEYWORDS.associate { it.label to it.phones.joinToString(" ") }
        assertEquals("HH EY1 K L AO1 D", byLabel["HEY_CLAUDE"])
        assertEquals("HH EY1 K L AW1 D", byLabel["HEY_CLOUD"])
        assertEquals("HH EY1 K L AA1 D", byLabel["HEY_CLOD"])
        assertEquals(WakeWord.DEFAULT_KEYWORDS.first().label, "HEY_CLAUDE")
    }

    @Test fun `the dictionary spells the default phrase as the built-in default`() {
        val r = ready(WakeWord.fromDictionary(WakeWord.DEFAULT_PHRASE, dictionary("hey", "claude"), arpabet))
        assertEquals(WakeWord.DEFAULT_PHRASE, r.phrase)
        assertEquals(WakeWord.DEFAULT_KEYWORDS, r.keywords)
    }

    @Test fun `every default and dictionary phone is a model token`() {
        val tokensFile = File(assets, "model/tokens.txt")
        // The model is fetched, not committed; CI and local builds run scripts/fetch_sherpa.sh first.
        if (!tokensFile.exists()) return
        val tokens = WakeWord.modelTokens(tokensFile.readText())
        for (k in WakeWord.DEFAULT_KEYWORDS) {
            for (phone in k.phones) assertTrue("${k.label}: $phone is not a model token", phone in tokens)
        }
        assertTrue("CMUdict uses a phone the model lacks: ${arpabet - tokens}", tokens.containsAll(arpabet))
        assertFalse("<blk> is not a phone", "<blk>" in tokens)
    }

    @Test fun `the spotter's own keywords file is blank, so only the chosen phrase is heard`() {
        // sherpa-onnx adds each stream's keywords to this file's, and would parse '#' as a threshold.
        assertTrue(File(assets, "empty_keywords.txt").readText().isBlank())
        assertFalse(File(assets, "keywords.txt").exists())
    }

    @Test fun `the dictionary asset is where the app opens it, under a name the build leaves alone`() {
        assertEquals("wake/cmudict.txt.gzip", WakeWord.DICTIONARY_ASSET)
        assertTrue(File(assets.parentFile, WakeWord.DICTIONARY_ASSET).isFile)
        // The Android build un-gzips *.gz assets while packaging, so the APK would have a different file.
        val gz = assets.parentFile.walk().filter { it.isFile && it.name.endsWith(".gz") }.toList()
        assertTrue("assets ending in .gz are unpacked by the build: $gz", gz.isEmpty())
    }

    @Test fun `the shipped dictionary is the text scripts build_cmudict py pins`() {
        val text = java.util.zip.GZIPInputStream(File(assets, "cmudict.txt.gzip").inputStream()).use { it.readBytes() }
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(text).joinToString("") { "%02x".format(it) }
        val pinned = Regex("TEXT_SHA256 = \"([0-9a-f]{64})\"").find(File("../scripts/build_cmudict.py").readText())
        assertEquals(pinned?.groupValues?.get(1), sha)
    }

    @Test fun `the shipped dictionary has names and common words, every pronunciation of each`() {
        val d = dictionary("jarvis", "computer", "hey", "tomato", "notaword")
        assertEquals(
            listOf(listOf("JH", "AA1", "R", "V", "AH0", "S"), listOf("JH", "AA1", "R", "V", "IH0", "S")),
            d["jarvis"],
        )
        assertEquals(listOf("K", "AH0", "M", "P", "Y", "UW1", "T", "ER0"), d["computer"]?.first())
        assertTrue((d["tomato"]?.size ?: 0) >= 2)
        assertFalse("notaword" in d)
        for (prons in d.values) for (p in prons.flatten()) assertTrue("$p is not ARPAbet", p in arpabet)
    }

    // --- Phrase to phones ---

    private val tiny = mapOf(
        "hey" to listOf(listOf("HH", "EY1")),
        "jarvis" to listOf(listOf("JH", "AA1", "R", "V", "AH0", "S")),
        "tomato" to listOf(listOf("T", "AH0", "M", "EY1", "T", "OW2"), listOf("T", "AH0", "M", "AA1", "T", "OW2")),
        "claude" to listOf(listOf("K", "L", "AO1", "D")),
        "ok" to listOf(listOf("OW2", "K", "EY1")),
    )

    @Test fun `typed phrases are tidied into words`() {
        assertEquals(listOf("hey", "jarvis"), WakeWord.words("  Hey,  JARVIS! "))
        assertEquals(listOf("o'brien", "hi"), WakeWord.words("O'Brien-hi"))
        assertEquals(listOf("josé"), WakeWord.words("'José'"))
        assertEquals("hey jarvis", WakeWord.tidy("hey\tjarvis."))
        assertTrue(WakeWord.words(" ,.! ").isEmpty())
    }

    @Test fun `a phrase becomes its phones with a label from its words`() {
        val r = ready(WakeWord.fromDictionary("Hey Jarvis", tiny, arpabet))
        assertEquals("hey jarvis", r.phrase)
        assertEquals(listOf("HH EY1 JH AA1 R V AH0 S @HEY_JARVIS"), r.keywords.map { it.line })
    }

    @Test fun `every dictionary pronunciation becomes a keyword, the first one first`() {
        val r = ready(WakeWord.fromDictionary("hey tomato", tiny, arpabet))
        assertEquals(
            listOf("HH EY1 T AH0 M EY1 T OW2 @HEY_TOMATO", "HH EY1 T AH0 M AA1 T OW2 @HEY_TOMATO_2"),
            r.keywords.map { it.line },
        )
    }

    @Test fun `claude keeps its accent variants in any phrase`() {
        val r = ready(WakeWord.fromDictionary("ok claude", tiny, arpabet))
        assertEquals(listOf("OK_CLAUDE", "OK_CLOUD", "OK_CLOD"), r.keywords.map { it.label })
    }

    @Test fun `variants combine across words, fewest swaps first, capped`() {
        val two = listOf(listOf("A1"), listOf("B1"))
        val choices = List(3) { i -> listOf(WakeWord.Pronunciation("w$i", two[0]), WakeWord.Pronunciation("w$i", listOf("C$i"))) }
        val keywords = WakeWord.combine(choices)
        assertEquals(8, keywords.size)
        assertEquals(listOf("A1", "A1", "A1"), keywords[0].phones)
        // The next three swap in exactly one alternative.
        assertTrue(keywords.subList(1, 4).all { k -> k.phones.count { it != "A1" } == 1 })
        assertEquals(listOf("C0", "C1", "C2"), keywords.last().phones)

        val many = List(6) { i -> List(3) { j -> WakeWord.Pronunciation("w", listOf("P$i$j")) } }
        val capped = WakeWord.combine(many)
        assertEquals(WakeWord.MAX_KEYWORDS, capped.size)
        assertEquals(capped.size, capped.map { it.label }.toSet().size)
        assertEquals(List(6) { "P${it}0" }, capped.first().phones)
    }

    @Test fun `identical pronunciations are listed once`() {
        val same = listOf(listOf("HH", "EY1"), listOf("HH", "EY1"))
        val r = ready(WakeWord.fromDictionary("hey", mapOf("hey" to same), arpabet))
        assertEquals(1, r.keywords.size)
    }

    @Test fun `unknown words are named, in order, once`() {
        assertEquals(
            WakeWord.Result.UnknownWords(listOf("jarvys", "zork")),
            WakeWord.fromDictionary("hey jarvys zork jarvys", tiny, arpabet),
        )
        assertEquals(WakeWord.Result.Empty, WakeWord.fromDictionary(" !! ", tiny, arpabet))
    }

    @Test fun `dictionary phones are checked against the model too`() {
        val r = WakeWord.fromDictionary("hey", tiny, arpabet - "EY1")
        assertEquals(listOf("EY1"), (r as WakeWord.Result.UnknownPhones).phones)
    }

    @Test fun `phones typed by hand are checked against the model`() {
        val r = ready(WakeWord.fromPhones("Hey Jarvis", "hh ey1 jh aa1 r v ih0 s\nHH EY1 JH AA1 R V AH0 S", arpabet))
        assertEquals("hey jarvis", r.phrase)
        assertEquals(
            listOf("HH EY1 JH AA1 R V IH0 S @HEY_JARVIS", "HH EY1 JH AA1 R V AH0 S @HEY_JARVIS_2"),
            r.keywords.map { it.line },
        )
        assertEquals(2, ready(WakeWord.fromPhones("x", "K L AO1 D / K L AW1 D", arpabet)).keywords.size)

        val bad = WakeWord.fromPhones("hey jarvis", "HH EY JH AA1 R V IS", arpabet) as WakeWord.Result.UnknownPhones
        assertEquals(listOf("EY", "IS"), bad.phones)
        assertEquals(mapOf("EY" to "EY1"), bad.suggestions)
        // Not a phone even though tokens.txt lists it: sherpa-onnx's special tokens are filtered out.
        val tokens = WakeWord.modelTokens("<blk> 0\n<unk> 2\nHH 3\nEY1 4\n")
        assertEquals(setOf("HH", "EY1"), tokens)
    }

    @Test fun `hand phones need a phrase to name them, and some phones`() {
        assertEquals(WakeWord.Result.Empty, WakeWord.fromPhones("", "HH EY1", arpabet))
        assertEquals(WakeWord.Result.Empty, WakeWord.fromPhones("hey", " \n / ", arpabet))
    }

    @Test fun `saved keywords are used only while they fit the model, and the phrase shown follows`() {
        val saved = WakeWord.DEFAULT_KEYWORDS.take(1).map { it.copy(label = "MINE") }
        val text = saved.joinToString("\n") { it.line }
        assertEquals(WakeWord.Effective("hey mine", saved, fellBack = false), WakeWord.effective("Hey  mine", text, arpabet))

        val default = WakeWord.Effective(WakeWord.DEFAULT_PHRASE, WakeWord.DEFAULT_KEYWORDS, fellBack = false)
        assertEquals(default, WakeWord.effective(WakeWord.DEFAULT_PHRASE, "", arpabet))

        // Something was saved but cannot be used: the default is heard, and shown, instead.
        val fellBack = default.copy(fellBack = true)
        assertEquals(fellBack, WakeWord.effective("hey jarvis", "HH EY1 no label", arpabet))
        assertEquals(fellBack, WakeWord.effective("hey jarvis", "HH EY9 @X", arpabet))
        assertEquals(fellBack, WakeWord.effective("hey jarvis", "S @X", arpabet))
        assertEquals(fellBack, WakeWord.effective("", text, arpabet))
        val tooMany = List(WakeWord.MAX_KEYWORDS + 1) { "HH EY1 @X$it" }.joinToString("\n")
        assertEquals(fellBack, WakeWord.effective("hey", tooMany, arpabet))
        // The model changed under a saved phrase.
        assertEquals(fellBack, WakeWord.effective("hey mine", text, arpabet - "AO1"))
    }

    @Test fun `curly apostrophes read as straight ones`() {
        assertEquals(listOf("what's", "up"), WakeWord.words("what’s up"))
        assertEquals(listOf("what's"), WakeWord.words("WHAT‘S"))
        assertEquals(listOf("o'brien"), WakeWord.words("oʼbrien"))
        val d = dictionary("what's", "up")
        val r = ready(WakeWord.fromDictionary("What’s up", d, arpabet))
        assertEquals("what's up", r.phrase)
        assertEquals("W AH1 T S AH1 P", r.keywords.first().phones.joinToString(" "))
    }

    @Test fun `hand phones are capped at the keyword limit`() {
        val max = List(WakeWord.MAX_KEYWORDS) { "HH EY1" }.joinToString("\n")
        // Duplicates collapse, but the count that is refused is what was typed.
        assertEquals(1, ready(WakeWord.fromPhones("hey", max, arpabet)).keywords.size)
        val over = List(WakeWord.MAX_KEYWORDS + 1) { i -> "HH EY1 ${if (i % 2 == 0) "K" else "L"}" }.joinToString("\n")
        assertEquals(WakeWord.Result.TooMany(WakeWord.MAX_KEYWORDS + 1), WakeWord.fromPhones("hey", over, arpabet))
    }

    @Test fun `a pronunciation without a vowel is refused, typed or from the dictionary`() {
        assertEquals(WakeWord.Result.NoVowel(listOf("S")), WakeWord.fromPhones("ss", "S", arpabet))
        assertEquals(WakeWord.Result.NoVowel(listOf("SH")), WakeWord.fromPhones("shh", "HH EY1\nSH", arpabet))
        val shh = mapOf("shh" to listOf(listOf("SH")))
        assertEquals(WakeWord.Result.NoVowel(listOf("SH")), WakeWord.fromDictionary("shh", shh, arpabet))
        // One vowel anywhere in the line is enough.
        ready(WakeWord.fromDictionary("hey shh", tiny + shh, arpabet))
    }

    @Test fun `accent variants are spotted in any phrase with claude`() {
        assertTrue(WakeWord.hasAccentVariants(WakeWord.DEFAULT_KEYWORDS))
        assertTrue(WakeWord.hasAccentVariants(ready(WakeWord.fromDictionary("ok claude", tiny, arpabet)).keywords))
        assertFalse(WakeWord.hasAccentVariants(ready(WakeWord.fromDictionary("hey jarvis", tiny, arpabet)).keywords))
    }

    @Test fun `the stream gets every keyword line, slash separated`() {
        assertEquals(
            "HH EY1 K L AO1 D @HEY_CLAUDE/HH EY1 K L AW1 D @HEY_CLOUD/HH EY1 K L AA1 D @HEY_CLOD",
            WakeWord.streamKeywords(WakeWord.DEFAULT_KEYWORDS),
        )
    }

    @Test fun `short and long phrases get a caution, 2 to 4 syllables do not`() {
        fun advice(phrase: String) = WakeWord.advice(ready(WakeWord.fromDictionary(phrase, tiny, arpabet)).keywords)
        assertEquals(2, WakeWord.syllables(WakeWord.DEFAULT_KEYWORDS.first().phones))
        assertEquals(null, advice("hey claude"))
        assertEquals(null, advice("hey jarvis"))
        assertTrue(advice("hey")!!.startsWith("Only 1 syllable"))
        assertTrue(advice("hey tomato tomato")!!.startsWith("7 syllables"))
    }

    @Test fun `lookup reads the gzipped format and skips its header`() {
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use {
                it.write(";;; header hey HH EY1\nhey HH EY1\nhey HH EY2\nhi HH AY1\n".toByteArray())
            }
        }.toByteArray()
        val found = WakeWord.lookup(bytes.inputStream(), listOf("hey", "nope"))
        assertEquals(mapOf("hey" to listOf(listOf("HH", "EY1"), listOf("HH", "EY2"))), found)
    }

    @Test fun `parsing skips comments and drops per-keyword boosts`() {
        val parsed = WakeWord.parseKeywords("# comment\n\nHH EY1 K L AO1 D :2.0 #0.3 @HEY_CLAUDE\n")
        assertEquals(listOf(WakeWord.Keyword(listOf("HH", "EY1", "K", "L", "AO1", "D"), "HEY_CLAUDE")), parsed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a keyword without a label is rejected`() {
        WakeWord.parseKeywords("HH EY1 K L AO1 D\n")
    }

    @Test fun `sensitivity maps to a threshold, loosest at the top`() {
        assertEquals(0.40f, WakeWord.threshold(1), 1e-6f)
        assertEquals(0.20f, WakeWord.threshold(WakeWord.DEFAULT_SENSITIVITY), 1e-6f)
        assertEquals(0.10f, WakeWord.threshold(7), 1e-6f)
        assertEquals(0.10f, WakeWord.threshold(99), 1e-6f)
        assertEquals(0.40f, WakeWord.threshold(-3), 1e-6f)
    }

    @Test fun `labels read as speech`() {
        assertEquals("hey cloud", WakeWord.spoken("HEY_CLOUD"))
    }

    // --- Controller ---

    private val c = WakeWordController(cooldownMs = 4_000, releaseDelayMs = 1_000, assistantTimeoutMs = 10_000, micRetryMs = 5_000)

    private fun List<Action>.withoutNotes() = filterNot { it is Action.Note }

    @Test fun `start listens when the mic is free and waits when it is not`() {
        assertEquals(listOf(Action.StartMic), c.start(0, micBusy = false))
        assertEquals(State.LISTENING, c.state)
        assertTrue(c.start(0, micBusy = false).isEmpty())

        val busy = WakeWordController()
        assertTrue(busy.start(0, micBusy = true).withoutNotes().isEmpty())
        assertEquals(State.BUSY, busy.state)
        busy.update(100, micBusy = false, musicActive = false)
        assertEquals(listOf(Action.StartMic), busy.update(1_100, micBusy = false, musicActive = false))
    }

    @Test fun `a keyword frees the mic and opens the assistant, once`() {
        c.start(0, false)
        assertEquals(listOf(Action.StopMic, Action.LaunchAssistant), c.onKeyword(1_000, false, true))
        assertEquals(State.WAITING, c.state)
        assertTrue(c.onKeyword(1_100, false, true).isEmpty())
        assertTrue(c.needsTicks)
    }

    @Test fun `listening resumes only after the assistant lets go, the delay, and the cooldown`() {
        c.start(0, false)
        c.onKeyword(10_000, musicActive = false, resumeMediaEnabled = true)
        c.update(10_500, micBusy = true, musicActive = false)
        assertEquals(State.BUSY, c.state)
        // Claude's turns: recording stops between them, but the call-style audio mode keeps it busy.
        c.update(20_000, micBusy = true, musicActive = false)
        assertTrue(c.update(30_000, micBusy = false, musicActive = false).isEmpty())
        assertTrue(c.update(30_900, micBusy = false, musicActive = false).isEmpty())
        // Busy again before the delay ran out: the delay starts over.
        c.update(30_950, micBusy = true, musicActive = false)
        c.update(31_000, micBusy = false, musicActive = false)
        assertTrue(c.update(31_900, micBusy = false, musicActive = false).isEmpty())
        assertEquals(listOf(Action.StartMic), c.update(32_000, micBusy = false, musicActive = false))
        assertEquals(State.LISTENING, c.state)
        assertFalse(c.needsTicks)
    }

    @Test fun `the cooldown holds even when the assistant is quick`() {
        c.start(0, false)
        c.onKeyword(10_000, false, true)
        c.update(10_200, micBusy = true, musicActive = false)
        c.update(10_400, micBusy = false, musicActive = false)
        assertTrue(c.update(12_000, micBusy = false, musicActive = false).withoutNotes().isEmpty())
        assertTrue(c.update(13_900, micBusy = false, musicActive = false).withoutNotes().isEmpty())
        assertEquals(listOf(Action.StartMic), c.update(14_000, micBusy = false, musicActive = false))
    }

    @Test fun `if the assistant never takes the mic, listening comes back after the timeout`() {
        c.start(0, false)
        c.onKeyword(10_000, false, true)
        assertTrue(c.update(19_000, micBusy = false, musicActive = false).isEmpty())
        assertEquals(listOf(Action.StartMic), c.update(20_000, micBusy = false, musicActive = false).withoutNotes())
    }

    @Test fun `music playing at the trigger is resumed when the assistant is done`() {
        c.start(0, false)
        c.onKeyword(10_000, musicActive = true, resumeMediaEnabled = true)
        c.update(10_300, micBusy = true, musicActive = false)
        c.update(20_000, micBusy = false, musicActive = false)
        assertEquals(listOf(Action.StartMic, Action.ResumeMedia), c.update(21_000, micBusy = false, musicActive = false))
    }

    @Test fun `music is not pressed again if it is already playing, or if the setting is off`() {
        c.start(0, false)
        c.onKeyword(10_000, musicActive = true, resumeMediaEnabled = true)
        c.update(10_300, micBusy = true, musicActive = false)
        c.update(20_000, micBusy = false, musicActive = true)
        assertEquals(listOf(Action.StartMic), c.update(21_000, micBusy = false, musicActive = true).withoutNotes())

        val off = WakeWordController()
        off.start(0, false)
        off.onKeyword(10_000, musicActive = true, resumeMediaEnabled = false)
        off.update(10_300, micBusy = true, musicActive = false)
        off.update(20_000, micBusy = false, musicActive = false)
        assertEquals(listOf(Action.StartMic), off.update(21_000, micBusy = false, musicActive = false))
    }

    @Test fun `no music, no media key`() {
        c.start(0, false)
        c.onKeyword(10_000, musicActive = false, resumeMediaEnabled = true)
        c.update(10_300, micBusy = true, musicActive = false)
        c.update(20_000, micBusy = false, musicActive = false)
        assertEquals(listOf(Action.StartMic), c.update(21_000, micBusy = false, musicActive = false))
    }

    @Test fun `someone else recording pauses listening without a media key afterwards`() {
        c.start(0, false)
        assertEquals(listOf(Action.StopMic), c.update(5_000, micBusy = true, musicActive = true).withoutNotes())
        assertEquals(State.BUSY, c.state)
        c.update(9_000, micBusy = false, musicActive = false)
        assertEquals(listOf(Action.StartMic), c.update(10_000, micBusy = false, musicActive = false))
    }

    @Test fun `a failed mic start backs off before retrying`() {
        c.start(0, false)
        c.onMicFailed(1_000)
        assertEquals(State.BUSY, c.state)
        c.update(1_100, micBusy = false, musicActive = false)
        assertTrue(c.update(5_900, micBusy = false, musicActive = false).isEmpty())
        assertEquals(listOf(Action.StartMic), c.update(6_000, micBusy = false, musicActive = false))
    }

    @Test fun `stop turns the mic off only if it was on`() {
        c.start(0, false)
        assertEquals(listOf(Action.StopMic), c.stop())
        assertEquals(State.OFF, c.state)
        assertTrue(c.update(1_000, micBusy = false, musicActive = false).isEmpty())
        c.start(2_000, false)
        c.onKeyword(3_000, false, true)
        assertTrue(c.stop().isEmpty())
    }

    // --- Claude voice mode button ---

    @Test fun `the voice mode label matches whole and case blind only`() {
        assertTrue(WakeWord.isVoiceModeLabel("Voice Mode"))
        assertTrue(WakeWord.isVoiceModeLabel(" voice mode "))
        assertTrue(WakeWord.isVoiceModeLabel("Talk with Claude"))
        assertFalse(WakeWord.isVoiceModeLabel("Exit Voice Mode"))
        assertFalse(WakeWord.isVoiceModeLabel("Start speech input"))
        assertFalse(WakeWord.isVoiceModeLabel(null))
    }

    // --- The timer phrase ---

    @Test fun `the dictionary spells the timer phrase as the built-in timer keywords`() {
        val r = ready(WakeWord.fromDictionary(WakeWord.TIMER_PHRASE, dictionary("hey", "timer"), arpabet))
        assertEquals(WakeWord.TIMER_PHRASE, r.phrase)
        assertEquals(WakeWord.TIMER_KEYWORDS, r.keywords)
    }

    @Test fun `both phrases are heard together, only the timer's labels tagged`() {
        val all = WakeWord.listenFor(WakeWord.DEFAULT_KEYWORDS, WakeWord.TIMER_KEYWORDS)
        assertEquals(WakeWord.DEFAULT_KEYWORDS + WakeWord.TIMER_KEYWORDS.map { it.copy(label = WakeWord.TIMER_TAG + it.label) }, all)
        assertEquals("TIMER__HEY_TIMER", all.last().label)
        assertEquals(listOf(false, false, false, true), all.map { WakeWord.isTimerLabel(it.label) })
        assertFalse(WakeWord.isTimerLabel("HEY_TIMER"))
        assertFalse(WakeWord.isTimerLabel("HEY_CLAUDE"))
    }

    @Test fun `a phrase that is off is left out`() {
        assertEquals(WakeWord.DEFAULT_KEYWORDS, WakeWord.listenFor(WakeWord.DEFAULT_KEYWORDS, null))
        val timerOnly = WakeWord.listenFor(null, WakeWord.TIMER_KEYWORDS)
        assertEquals(listOf("TIMER__HEY_TIMER"), timerOnly.map { it.label })
        assertEquals(WakeWord.TIMER_KEYWORDS.map { it.phones }, timerOnly.map { it.phones })
        assertTrue(WakeWord.listenFor(null, null).isEmpty())
    }

    @Test fun `a timer keyword that sounds exactly like a claude one is dropped`() {
        val clash = WakeWord.Keyword(WakeWord.DEFAULT_KEYWORDS[1].phones, "HEY_TIMER_2")
        val all = WakeWord.listenFor(WakeWord.DEFAULT_KEYWORDS, WakeWord.TIMER_KEYWORDS + clash)
        assertEquals(4, all.size)
        assertEquals(listOf("TIMER__HEY_TIMER"), all.filter { WakeWord.isTimerLabel(it.label) }.map { it.label })
        assertEquals("HEY_CLOUD", all.single { it.phones == clash.phones }.label)
    }

    private fun many(prefix: String, n: Int) = List(n) { WakeWord.Keyword(listOf("AA1", "$prefix$it"), "$prefix$it") }

    @Test fun `together the phrases never pass the keyword limit, each keeping its first ones`() {
        fun split(claude: Int, timer: Int): Pair<List<String>, List<String>> {
            val all = WakeWord.listenFor(many("C", claude), many("T", timer))
            assertTrue(all.size <= WakeWord.MAX_KEYWORDS)
            val (t, c) = all.partition { WakeWord.isTimerLabel(it.label) }
            return c.map { it.label } to t.map { it.label.removePrefix(WakeWord.TIMER_TAG) }
        }
        assertEquals(many("C", 8).map { it.label } to many("T", 8).map { it.label }, split(16, 16))
        assertEquals(many("C", 3).map { it.label } to many("T", 13).map { it.label }, split(3, 16))
        assertEquals(many("C", 14).map { it.label } to many("T", 2).map { it.label }, split(16, 2))
        assertEquals(many("C", 16).map { it.label } to emptyList<String>(), split(16, 0))
        assertEquals(emptyList<String>() to many("T", 16).map { it.label }, split(0, 16))
    }

    @Test fun `the stream for both phrases parses back with the tagged labels`() {
        val all = WakeWord.listenFor(WakeWord.DEFAULT_KEYWORDS, WakeWord.TIMER_KEYWORDS)
        val lines = WakeWord.streamKeywords(all).split('/').joinToString("\n")
        assertEquals(all, WakeWord.parseKeywords(lines))
    }

    @Test fun `the timer phrase falls back to its own defaults`() {
        fun effective(phrase: String, keywords: String) =
            WakeWord.effective(phrase, keywords, arpabet, WakeWord.TIMER_PHRASE, WakeWord.TIMER_KEYWORDS)
        assertEquals(WakeWord.Effective(WakeWord.TIMER_PHRASE, WakeWord.TIMER_KEYWORDS, fellBack = false), effective("", ""))
        val fellBack = WakeWord.Effective(WakeWord.TIMER_PHRASE, WakeWord.TIMER_KEYWORDS, fellBack = true)
        assertEquals(fellBack, effective("hey oven", "HH EY9 @X"))
        assertEquals(fellBack, effective("hey oven", "S @X"))
        assertEquals(fellBack, effective("hey oven", "HH EY1 no label"))
        // A usable saved phrase still wins.
        val saved = "HH EY1 AH1 V AH0 N @HEY_OVEN"
        assertEquals(
            WakeWord.Effective("hey oven", WakeWord.parseKeywords(saved), fellBack = false),
            effective("hey oven", saved),
        )
    }

    // --- Controller and the timer phrase ---

    @Test fun `a timer keyword hands the mic to the timer flow instead of opening the assistant`() {
        c.start(0, false)
        assertEquals(listOf(Action.StopMic, Action.StartTimerFlow), c.onKeyword(1_000, false, true, timer = true))
        assertEquals(State.TIMER, c.state)
        assertTrue(c.needsTicks)
        // Already in the flow: a second keyword does nothing.
        assertTrue(c.onKeyword(1_100, false, true, timer = true).isEmpty())
        assertTrue(c.onKeyword(1_200, false, true).isEmpty())
        assertEquals(State.TIMER, c.state)
    }

    @Test fun `a timer keyword while a timer rings stops it here, with no recording, and keeps listening`() {
        c.start(0, false)
        assertEquals(listOf(Action.StopRingingTimers), c.onKeyword(1_000, false, true, timer = true, timerRinging = true))
        assertEquals(State.LISTENING, c.state)
        assertFalse(c.needsTicks)
        // The echo of the same "hey timer" right after starts nothing.
        assertTrue(c.onKeyword(1_500, false, true, timer = true).isEmpty())
        assertEquals(State.LISTENING, c.state)
        // "hey claude" is not held back.
        assertEquals(listOf(Action.StopMic, Action.LaunchAssistant), c.onKeyword(1_600, false, true))
    }

    @Test fun `a timer keyword with nothing ringing goes to the timer flow as before`() {
        c.start(0, false)
        assertEquals(listOf(Action.StopMic, Action.StartTimerFlow), c.onKeyword(1_000, false, true, timer = true, timerRinging = false))
        assertEquals(State.TIMER, c.state)
    }

    @Test fun `after a stop by voice, a later timer keyword with nothing ringing records again`() {
        c.start(0, false)
        c.onKeyword(1_000, false, true, timer = true, timerRinging = true)
        assertEquals(listOf(Action.StopMic, Action.StartTimerFlow), c.onKeyword(10_000, false, true, timer = true))
    }

    @Test fun `the Claude phrase never stops a ringing timer`() {
        c.start(0, false)
        assertEquals(listOf(Action.StopMic, Action.LaunchAssistant), c.onKeyword(1_000, false, true, timerRinging = true))
    }

    @Test fun `a timer keyword is ignored when not listening`() {
        val off = WakeWordController()
        assertTrue(off.onKeyword(0, false, true, timer = true).isEmpty())
        assertEquals(State.OFF, off.state)

        val busy = WakeWordController()
        busy.start(0, micBusy = true)
        assertTrue(busy.onKeyword(100, false, true, timer = true).isEmpty())
        assertEquals(State.BUSY, busy.state)

        c.start(0, false)
        c.onKeyword(1_000, false, true)
        assertTrue(c.onKeyword(1_100, false, true, timer = true).isEmpty())
        assertEquals(State.WAITING, c.state)
    }

    @Test fun `during the timer flow the busy mic is its own, so update waits`() {
        c.start(0, false)
        c.onKeyword(1_000, false, true, timer = true)
        assertTrue(c.update(2_000, micBusy = true, musicActive = false).isEmpty())
        assertTrue(c.update(60_000, micBusy = false, musicActive = true).isEmpty())
        assertEquals(State.TIMER, c.state)
        assertTrue(c.needsTicks)
    }

    @Test fun `when the timer flow is done, listening comes back, or waits for a busy mic`() {
        c.start(0, false)
        c.onKeyword(1_000, false, true, timer = true)
        assertEquals(listOf(Action.StartMic), c.onTimerDone(5_000, micBusy = false))
        assertEquals(State.LISTENING, c.state)
        assertFalse(c.needsTicks)

        val busy = WakeWordController()
        busy.start(0, false)
        busy.onKeyword(1_000, false, true, timer = true)
        assertTrue(busy.onTimerDone(5_000, micBusy = true).withoutNotes().isEmpty())
        assertEquals(State.BUSY, busy.state)
        busy.update(6_000, micBusy = false, musicActive = false)
        assertEquals(listOf(Action.StartMic), busy.update(7_000, micBusy = false, musicActive = false))
    }

    @Test fun `timer done does nothing outside the timer flow`() {
        val off = WakeWordController()
        assertTrue(off.onTimerDone(0, false).isEmpty())
        assertEquals(State.OFF, off.state)

        val listening = WakeWordController()
        listening.start(0, false)
        assertTrue(listening.onTimerDone(100, false).isEmpty())
        assertEquals(State.LISTENING, listening.state)

        val waiting = WakeWordController()
        waiting.start(0, false)
        waiting.onKeyword(1_000, false, true)
        assertTrue(waiting.onTimerDone(1_100, false).isEmpty())
        assertEquals(State.WAITING, waiting.state)

        val busy = WakeWordController()
        busy.start(0, micBusy = true)
        assertTrue(busy.onTimerDone(100, false).isEmpty())
        assertEquals(State.BUSY, busy.state)
    }

    @Test fun `a timer flow that never finishes gives the mic back after the timeout`() {
        c.start(0, false)
        c.onKeyword(10_000, false, true, timer = true)
        assertTrue(c.update(189_999, micBusy = false, musicActive = false).isEmpty())
        assertEquals(listOf(Action.StartMic), c.update(190_000, micBusy = false, musicActive = false).withoutNotes())
        assertEquals(State.LISTENING, c.state)

        val short = WakeWordController(timerTimeoutMs = 30_000)
        short.start(0, false)
        short.onKeyword(1_000, false, true, timer = true)
        assertTrue(short.update(30_999, micBusy = true, musicActive = false).isEmpty())
        assertEquals(listOf(Action.StartMic), short.update(31_000, micBusy = true, musicActive = false).withoutNotes())
        assertEquals(State.LISTENING, short.state)
        // A late done from the flow changes nothing.
        assertTrue(short.onTimerDone(32_000, false).isEmpty())
    }

    @Test fun `music playing at a timer keyword is not resumed by a media key`() {
        c.start(0, false)
        c.onKeyword(10_000, musicActive = true, resumeMediaEnabled = true, timer = true)
        assertEquals(listOf(Action.StartMic), c.onTimerDone(20_000, micBusy = false))

        // Nor later, after a Claude keyword with no music.
        c.onKeyword(30_000, musicActive = false, resumeMediaEnabled = true)
        c.update(30_300, micBusy = true, musicActive = false)
        c.update(40_000, micBusy = false, musicActive = false)
        assertEquals(listOf(Action.StartMic), c.update(41_000, micBusy = false, musicActive = false))

        // Nor through the busy path or the timeout.
        val busy = WakeWordController()
        busy.start(0, false)
        busy.onKeyword(10_000, musicActive = true, resumeMediaEnabled = true, timer = true)
        busy.onTimerDone(20_000, micBusy = true)
        busy.update(21_000, micBusy = false, musicActive = false)
        assertEquals(listOf(Action.StartMic), busy.update(22_000, micBusy = false, musicActive = false))

        val timedOut = WakeWordController(timerTimeoutMs = 30_000)
        timedOut.start(0, false)
        timedOut.onKeyword(1_000, musicActive = true, resumeMediaEnabled = true, timer = true)
        assertEquals(listOf(Action.StartMic), timedOut.update(31_000, micBusy = false, musicActive = false).withoutNotes())
    }

    @Test fun `a claude keyword still opens the assistant, with or without the timer flag`() {
        c.start(0, false)
        assertEquals(listOf(Action.StopMic, Action.LaunchAssistant), c.onKeyword(1_000, false, true, timer = false))
        assertEquals(State.WAITING, c.state)

        val after = WakeWordController()
        after.start(0, false)
        after.onKeyword(1_000, false, true, timer = true)
        after.onTimerDone(2_000, false)
        assertEquals(listOf(Action.StopMic, Action.LaunchAssistant), after.onKeyword(3_000, musicActive = true, resumeMediaEnabled = true))
        after.update(3_300, micBusy = true, musicActive = false)
        after.update(10_000, micBusy = false, musicActive = false)
        assertEquals(listOf(Action.StartMic, Action.ResumeMedia), after.update(11_000, micBusy = false, musicActive = false))
    }

    @Test fun `a timer phrase that sounds just like the Claude phrase is reported as shadowed`() {
        val claude = WakeWord.DEFAULT_KEYWORDS
        // Every way of saying it is taken: it would never fire.
        assertTrue(WakeWord.shadowed(claude, listOf(WakeWord.Keyword(claude[0].phones, "SAME"))))
        // One way of its own is enough.
        assertFalse(WakeWord.shadowed(claude, WakeWord.TIMER_KEYWORDS + WakeWord.Keyword(claude[1].phones, "SAME")))
        // With the Claude phrase off, nothing is in the way.
        assertFalse(WakeWord.shadowed(null, listOf(WakeWord.Keyword(claude[0].phones, "SAME"))))
        assertFalse(WakeWord.shadowed(claude, emptyList()))
    }
}
