package com.lperezmo.voicerelay

import org.json.JSONObject
import java.io.File

/**
 * The one recording waiting to be transcribed, kept on disk so a failed upload
 * survives the service being killed, an app update, or a reboot. It is written
 * before the upload starts and removed once the recording is transcribed or
 * the user discards it, so only a recording that never made it is ever found here.
 */
class PendingRecording(private val dir: File) {

    private val audioFile get() = File(dir, "recording.bin")
    private val metaFile get() = File(dir, "recording.json")

    fun save(audio: OpenAiClient.Audio) {
        dir.mkdirs()
        // Audio first, then the metadata that marks it complete: a crash between the
        // two leaves no metadata, so a half-written file is never offered for retry.
        metaFile.delete()
        writeAtomically(audioFile, audio.bytes)
        val meta = JSONObject()
            .put("filename", audio.filename)
            .put("mime", audio.mime)
            .put("seconds", audio.seconds)
        writeAtomically(metaFile, meta.toString().toByteArray())
    }

    fun load(): OpenAiClient.Audio? = try {
        val meta = JSONObject(metaFile.readText())
        OpenAiClient.Audio(
            audioFile.readBytes(),
            meta.getString("filename"),
            meta.getString("mime"),
            meta.optDouble("seconds", 0.0),
        )
    } catch (_: Exception) {
        null
    }

    fun clear() {
        metaFile.delete()
        audioFile.delete()
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}
