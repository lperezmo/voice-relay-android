package com.lperezmo.voicerelay

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

    @Test fun `built-in prompts ask for no em dashes, and the fix for no final period`() {
        for (prompt in listOf(Prompts.SIMPLE, Prompts.DEV, Prompts.TYPED)) {
            assertTrue(prompt.contains("Never use em dashes"))
            assertTrue(!prompt.contains('—'))
        }
        assertTrue(Prompts.TYPED.contains("Do not put a period at the very end"))
    }

    @Test fun `a fix prompt stored with the old default wording reads as the new default`() {
        val old = Prompts.TYPED.replace(" Never use em dashes; use a comma, colon, or period instead." +
            " Do not put a period at the very end of the text.", "")
        assertEquals(Prompts.TYPED, Prompts.typedOrDefault(old))
        assertEquals(Prompts.TYPED, Prompts.typedOrDefault(null))
        assertEquals(mine, Prompts.typedOrDefault(mine))
    }

    @Test fun `a cleanup prompt stored with the old simple wording stays simple`() {
        val old = Prompts.SIMPLE.replace(" Never use em dashes; use a comma, colon, or period instead.", "")
        assertEquals(Prompts.SIMPLE_KEY, Prompts.presetFor(old))
    }

    @Test fun `vocabulary is appended once, to whichever prompt is sent`() {
        val withTerms = Prompts.forRequest(mine, listOf("Pendleton", "Luna"))
        assertTrue(withTerms.startsWith(mine))
        assertTrue(withTerms.endsWith("write the term: Pendleton, Luna."))
        assertTrue(withTerms.contains("never replace one with a common word that sounds like it"))
        assertEquals(1, Regex("These terms may appear").findAll(withTerms).count())
        assertEquals(Prompts.TYPED, Prompts.forRequest(Prompts.TYPED, emptyList()))
    }
}
