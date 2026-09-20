package com.lperezmo.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptsTest {

    private val mine = "Clean up this text. No final period at the very end"

    @Test fun `a preset resolves to its own text, custom to yours`() {
        assertEquals(Prompts.SIMPLE, Prompts.resolve(Prompts.SIMPLE_KEY, mine))
        assertEquals(Prompts.DEV, Prompts.resolve(Prompts.DEV_KEY, mine))
        assertEquals(mine, Prompts.resolve(Prompts.CUSTOM_KEY, mine))
    }

    @Test fun `an unknown preset falls back to simple`() {
        assertEquals(Prompts.SIMPLE, Prompts.resolve("", mine))
        assertEquals(Prompts.SIMPLE, Prompts.resolve("whatever", mine))
    }

    @Test fun `settings saved before presets were named keep their choice`() {
        assertEquals(Prompts.SIMPLE_KEY, Prompts.presetFor(Prompts.SIMPLE))
        assertEquals(Prompts.DEV_KEY, Prompts.presetFor(Prompts.DEV))
        assertEquals(Prompts.CUSTOM_KEY, Prompts.presetFor(mine))
    }

    @Test fun `editing the custom text cannot change which preset is selected`() {
        // The bug this replaced: the selection was inferred from the prompt text,
        // so saving a custom prompt equal to a preset selected that preset instead.
        assertEquals(Prompts.CUSTOM_KEY, Prompts.presetFor(mine))
        assertEquals(Prompts.SIMPLE, Prompts.resolve(Prompts.SIMPLE_KEY, Prompts.DEV))
    }

    @Test fun `vocabulary is appended once, to whichever prompt is sent`() {
        val withTerms = Prompts.forRequest(mine, listOf("Pendleton", "Luna"))
        assertTrue(withTerms.startsWith(mine))
        assertTrue(withTerms.endsWith("spell them exactly like this: Pendleton, Luna."))
        assertEquals(Prompts.TYPED, Prompts.forRequest(Prompts.TYPED, emptyList()))
    }
}
