package com.prts.client.audio

import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Edge ReadAloud returns mp3 (it rejects every PCM/WAV output format), while the speech-API
 * backend feeds the player complete WAV. Decode once here instead of running ffmpeg on the VPS.
 */
internal object Mp3Wav {
    fun convert(mp3: ByteArray): ByteArray? {
        if (mp3.size < 4) return null
        return try {
            val stream = Bitstream(ByteArrayInputStream(mp3))
            val decoder = Decoder()
            val pcm = ByteArrayOutputStream(mp3.size * 4)
            var rate = 24_000
            var channels = 1
            while (true) {
                val header = stream.readFrame() ?: break
                val frame = decoder.decodeFrame(header, stream) as? SampleBuffer
                if (frame != null) {
                    rate = frame.sampleFrequency
                    channels = frame.channelCount.coerceIn(1, 2)
                    val buffer = frame.buffer ?: ShortArray(0)
                    val length = frame.bufferLength.coerceAtMost(buffer.size)
                    for (i in 0 until length) {
                        val value = buffer[i].toInt().coerceIn(-32768, 32767)
                        pcm.write(value and 0xff)
                        pcm.write((value shr 8) and 0xff)
                    }
                }
                stream.closeFrame()
            }
            val data = pcm.toByteArray()
            if (data.isEmpty()) null else wav(data, rate, channels)
        } catch (_: Throwable) {
            null
        }
    }

    private fun wav(pcm: ByteArray, rate: Int, channels: Int): ByteArray {
        val safeRate = rate.coerceIn(8_000, 48_000)
        val blockAlign = channels * 2
        val header = ByteArray(44)
        fun putAscii(at: Int, text: String) {
            for (i in text.indices) header[at + i] = text[i].code.toByte()
        }
        fun put32(at: Int, value: Int) {
            header[at] = (value and 0xff).toByte()
            header[at + 1] = ((value shr 8) and 0xff).toByte()
            header[at + 2] = ((value shr 16) and 0xff).toByte()
            header[at + 3] = ((value shr 24) and 0xff).toByte()
        }
        fun put16(at: Int, value: Int) {
            header[at] = (value and 0xff).toByte()
            header[at + 1] = ((value shr 8) and 0xff).toByte()
        }
        putAscii(0, "RIFF")
        put32(4, 36 + pcm.size)
        putAscii(8, "WAVE")
        putAscii(12, "fmt ")
        put32(16, 16)
        put16(20, 1)
        put16(22, channels)
        put32(24, safeRate)
        put32(28, safeRate * blockAlign)
        put16(32, blockAlign)
        put16(34, 16)
        putAscii(36, "data")
        put32(40, pcm.size)
        return header + pcm
    }
}
