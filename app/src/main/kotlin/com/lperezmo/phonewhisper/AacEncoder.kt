package com.lperezmo.phonewhisper

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File

/**
 * PCM16 mono -> AAC-LC in an .m4a container, using the platform encoder.
 * About 4x smaller than WAV at 64 kbps, which shortens uploads on a weak signal.
 * Returns null on any encoder failure so the caller can send WAV instead.
 */
object AacEncoder {
    private const val TAG = "PhoneWhisper"
    private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val BIT_RATE = 64_000
    private const val TIMEOUT_US = 10_000L

    fun encode(pcm: ByteArray, sampleRate: Int, cacheDir: File): ByteArray? {
        val out = File.createTempFile("dictation", ".m4a", cacheDir)
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            val format = MediaFormat.createAudioFormat(MIME, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            codec = MediaCodec.createEncoderByType(MIME).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            drain(codec, muxer, pcm, sampleRate)
            muxer.stop()
            return out.readBytes()
        } catch (e: Exception) {
            Log.w(TAG, "AAC encode failed, falling back to WAV: ${e.javaClass.simpleName}")
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release()
            try { muxer?.release() } catch (_: Exception) {}
            out.delete()
        }
    }

    private fun drain(codec: MediaCodec, muxer: MediaMuxer, pcm: ByteArray, sampleRate: Int) {
        val info = MediaCodec.BufferInfo()
        var track = -1
        var offset = 0
        var inputDone = false
        while (true) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buf = codec.getInputBuffer(inIndex)!!
                    buf.clear()
                    val n = minOf(buf.remaining(), pcm.size - offset) and 1.inv()
                    val ptsUs = offset / 2L * 1_000_000L / sampleRate
                    if (n <= 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        buf.put(pcm, offset, n)
                        codec.queueInputBuffer(inIndex, 0, n, ptsUs, 0)
                        offset += n
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
                outIndex >= 0 -> {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0 && track >= 0) {
                        muxer.writeSampleData(track, codec.getOutputBuffer(outIndex)!!, info)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}
