package com.lperezmo.voicerelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordAiTest {
    private val tokens = setOf(
        "HH", "EY1", "OW1", "Y", "EY0", "K", "L", "AO1", "AW1", "D", "JH", "AA1", "R", "V", "AH0", "S", "IY1", "N",
    )

    @Test fun `keeps valid pronunciations with their notes`() {
        val p = WakeWordAi.parse(
            """{"pronunciations":[{"phones":"OW1 Y EY0 K L AW1 D","note":"Spanish"},""" +
                """{"phones":"ow1 y ey0 k l ao1 d","note":"English speaker"}]}""",
            tokens,
        )!!
        assertEquals(listOf("OW1", "Y", "EY0", "K", "L", "AW1", "D"), p.kept[0].phones)
        assertEquals("Spanish", p.kept[0].note)
        assertEquals(listOf("OW1", "Y", "EY0", "K", "L", "AO1", "D"), p.kept[1].phones)
        assertTrue(p.dropped.isEmpty())
    }

    @Test fun `ignores code fences and text around the json`() {
        val p = WakeWordAi.parse("Here you go:\n```json\n{\"pronunciations\":[{\"phones\":\"HH EY1\",\"note\":\"\"}]}\n```", tokens)!!
        assertEquals(1, p.kept.size)
    }

    @Test fun `refuses unknown phones and pronunciations without a vowel`() {
        val p = WakeWordAi.parse(
            """{"pronunciations":[{"phones":"HH EY K","note":"a"},{"phones":"S","note":"b"},{"phones":"HH EY1","note":"c"}]}""",
            tokens,
        )!!
        assertEquals(listOf(listOf("HH", "EY1")), p.kept.map { it.phones })
        assertEquals(2, p.dropped.size)
        assertTrue(p.dropped[0], p.dropped[0].contains("not model phones: EY"))
        assertTrue(p.dropped[1], p.dropped[1].contains("no vowel"))
    }

    @Test fun `drops repeats and caps the count`() {
        val many = (1..12).joinToString(",") { i ->
            val phones = if (i <= 2) "HH EY1" else "HH EY1 " + List(i) { "N" }.joinToString(" ")
            """{"phones":"$phones","note":""}"""
        }
        val p = WakeWordAi.parse("""{"pronunciations":[$many]}""", tokens)!!
        assertEquals(WakeWordAi.MAX_SUGGESTIONS, p.kept.size)
        assertEquals(p.kept.size, p.kept.map { it.phones }.toSet().size)
        assertEquals(12 - 1 - WakeWordAi.MAX_SUGGESTIONS, p.dropped.size)
    }

    @Test fun `accepts plain strings in the array`() {
        val p = WakeWordAi.parse("""{"pronunciations":["HH EY1 JH AA1 R V AH0 S"]}""", tokens)!!
        assertEquals("", p.kept.single().note)
    }

    @Test fun `no json means no answer`() {
        assertNull(WakeWordAi.parse("I cannot help with that.", tokens))
        assertNull(WakeWordAi.parse("""{"other":[]}""", tokens))
    }

    @Test fun `kept phones pass the same checks as hand phones`() {
        val p = WakeWordAi.parse("""{"pronunciations":[{"phones":"OW1 Y EY0 K L AW1 D","note":"Spanish"}]}""", tokens)!!
        val r = WakeWord.fromPhones("oye claude", p.kept.joinToString("\n") { it.phones.joinToString(" ") }, tokens)
        assertTrue(r.toString(), r is WakeWord.Result.Ready)
    }

    @Test fun `instructions leave out the model's pinyin tokens`() {
        val text = WakeWordAi.instructions(tokens + setOf("a", "e", "ai", "ǎi"), WakeWordAi.Language.AUTO)
        val list = text.lines().first { it.contains("2 secondary") }.substringAfter("): ").split(" ")
        assertEquals(tokens.sorted(), list)
    }

    @Test fun `a suggestion that would not fit the phones field is left out whole`() {
        val long = List(200) { "N" }.joinToString(" ") + " IY1" // 403 characters
        val json = (1..4).joinToString(",") { i -> """{"phones":"${"HH ".repeat(i)}$long","note":""}""" }
        val p = WakeWordAi.parse("""{"pronunciations":[$json]}""", tokens)!!
        assertEquals(2, p.kept.size)
        assertTrue(p.text.length <= WakeWord.MAX_PHONES_TEXT)
        assertTrue(p.dropped.all { it.endsWith("(too long to fit)") })
        assertEquals(2, p.dropped.size)
    }

    @Test fun `instructions list the model phones and the language hint`() {
        val text = WakeWordAi.instructions(tokens, WakeWordAi.Language.SPANISH)
        assertTrue(text.contains("AA1 AH0 AO1"))
        assertTrue(text.contains(WakeWordAi.Language.SPANISH.hint))
        assertTrue(text.contains("JSON"))
    }
}
