package com.lperezmo.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationLogicTest {

    private fun ok(text: String) = OpenAiClient.Result(text, null)
    private fun err(msg: String) = OpenAiClient.Result(null, msg, 500)

    @Test fun `cleaned text is used when plausible`() {
        val p = DictationPipeline({ ok("um hello there") }, { ok("Hello there.") })
        assertEquals(DictationPipeline.Outcome.Text("Hello there."), p.run(ByteArray(0)))
    }

    @Test fun `no cleaner inserts raw transcript`() {
        val p = DictationPipeline({ ok("raw text") }, null)
        assertEquals(DictationPipeline.Outcome.Text("raw text"), p.run(ByteArray(0)))
    }

    @Test fun `cleanup failure falls back to raw with a note`() {
        val out = DictationPipeline({ ok("raw text") }, { err("timeout") }).run(ByteArray(0))
        assertEquals("raw text", (out as DictationPipeline.Outcome.Text).text)
        assertTrue(out.note!!.contains("Cleanup failed"))
    }

    @Test fun `cleanup that answers the question is rejected`() {
        val answer = "Paris is the capital of France. It has a population of about two million people and is known for many things."
        val out = DictationPipeline({ ok("where is paris") }, { ok(answer) }).run(ByteArray(0))
        assertEquals("where is paris", (out as DictationPipeline.Outcome.Text).text)
    }

    @Test fun `transcription error is a retryable failure`() {
        assertEquals(DictationPipeline.Outcome.Failed("offline"), DictationPipeline({ err("offline") }, null).run(ByteArray(0)))
    }

    @Test fun `empty transcript is no speech`() {
        assertEquals(DictationPipeline.Outcome.NoSpeech, DictationPipeline({ ok("") }, null).run(ByteArray(0)))
    }

    @Test fun `plausibility bounds`() {
        assertTrue(DictationPipeline.plausibleCleanup("command mode ssh into morty at rubicon", "ssh morty@rubicon"))
        assertFalse(DictationPipeline.plausibleCleanup("x", ""))
        val long = "a".repeat(100)
        assertFalse(DictationPipeline.plausibleCleanup(long, "a".repeat(20)))
    }

    @Test fun `insert at cursor adds a separating space`() {
        assertEquals(TextInjector.Insertion("Hi there", 8), TextInjector.insertAtSelection("Hi", 2, 2, "there"))
        assertEquals(TextInjector.Insertion("Hi.", 3), TextInjector.insertAtSelection("Hi", 2, 2, "."))
        assertEquals(TextInjector.Insertion("Hello", 5), TextInjector.insertAtSelection("", -1, -1, "Hello"))
    }

    @Test fun `insert replaces the selection`() {
        assertEquals(TextInjector.Insertion("say hello now", 9), TextInjector.insertAtSelection("say bye now", 4, 7, "hello"))
    }

    @Test fun `invalid selection appends`() {
        assertEquals(TextInjector.Insertion("abc def", 7), TextInjector.insertAtSelection("abc", 99, 99, "def"))
    }

    @Test fun `vocabulary is appended to the cleanup prompt`() {
        assertEquals("P", Prompts.forRequest("P", emptyList()))
        assertTrue(Prompts.forRequest("P", listOf("Galaxy", "sherpa-onnx")).endsWith("Galaxy, sherpa-onnx."))
    }

    @Test fun `list settings split on commas and newlines`() {
        assertEquals(listOf("en", "es"), AppSettings.splitList(" en,es\n\nen "))
    }
}
