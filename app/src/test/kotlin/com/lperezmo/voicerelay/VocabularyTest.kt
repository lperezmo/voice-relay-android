package com.lperezmo.voicerelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabularyTest {

    @Test fun `a short list goes to transcription whole`() {
        val terms = listOf("API", "MCP", "bun", "Made2Manage")
        assertEquals(terms, Vocabulary.forTranscription(terms))
    }

    @Test fun `a long list sends only the leading terms that fit, in order`() {
        val terms = (1..300).map { "term$it" }
        val sent = Vocabulary.forTranscription(terms)
        assertTrue(sent.size in 50 until 300)
        assertEquals(terms.take(sent.size), sent)
        assertTrue(sent.joinToString(", ").length <= Vocabulary.TRANSCRIPTION_CHARS)
        assertTrue((sent + terms[sent.size]).joinToString(", ").length > Vocabulary.TRANSCRIPTION_CHARS)
    }

    @Test fun `the transcription request carries the capped list, cleanup the whole one`() {
        val terms = (1..300).map { "term$it" }
        val keywords = OpenAiClient.transcriptionFields("gpt-transcribe", terms, emptyList()).count { it.first == "keywords[]" }
        assertEquals(Vocabulary.forTranscription(terms).size, keywords)
        val prompt = OpenAiClient.transcriptionFields("whisper-1", terms, emptyList()).single { it.first == "prompt" }.second
        assertTrue(prompt.length <= Vocabulary.TRANSCRIPTION_CHARS + "Vocabulary: .".length)
        assertTrue(Prompts.forRequest("P", terms).endsWith("term299, term300."))
    }

    @Test fun `a pasted list loses its list markers and duplicates`() {
        assertEquals(
            listOf("API", "MCP", "Claude Code", "bun", "CLAUDE.md"),
            Vocabulary.fromPaste("- API\n* MCP\n• Claude Code\n\n  bun , CLAUDE.md\nAPI\n"),
        )
    }

    @Test fun `the examples paste back as themselves`() {
        assertEquals(Vocabulary.EXAMPLES.lines(), Vocabulary.fromPaste(Vocabulary.EXAMPLES))
    }
}
