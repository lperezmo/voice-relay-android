package com.lperezmo.voicerelay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PendingRecordingTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store() = PendingRecording(File(tmp.root, "pending"))

    @Test fun `a saved recording comes back after a restart`() {
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        store().save(OpenAiClient.Audio.m4a(bytes, 582.4))

        val back = store().load()!!
        assertArrayEquals(bytes, back.bytes)
        assertEquals("audio.m4a", back.filename)
        assertEquals("audio/mp4", back.mime)
        assertEquals(582.4, back.seconds, 1e-9)
    }

    @Test fun `nothing is pending until something is saved, and after it is cleared`() {
        val s = store()
        assertNull(s.load())
        s.save(OpenAiClient.Audio.wav(ByteArray(10), 1.0))
        s.clear()
        assertNull(s.load())
    }

    @Test fun `a newer recording replaces the older one`() {
        val s = store()
        s.save(OpenAiClient.Audio.wav(ByteArray(10) { 1 }, 1.0))
        s.save(OpenAiClient.Audio.m4a(ByteArray(4) { 2 }, 2.0))
        val back = s.load()!!
        assertArrayEquals(ByteArray(4) { 2 }, back.bytes)
        assertEquals("audio.m4a", back.filename)
    }

    @Test fun `audio without its metadata is never offered`() {
        // What a crash between the two writes leaves behind.
        val dir = File(tmp.root, "pending").apply { mkdirs() }
        File(dir, "recording.bin").writeBytes(ByteArray(10))
        assertNull(PendingRecording(dir).load())
    }
}
