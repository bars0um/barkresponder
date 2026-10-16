package com.codeoptimus.barkresponder

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Writes mono 16-bit PCM WAV, the format YAMNet's training tools read. */
object WavWriter {
    const val SAMPLE_RATE = Resampler.OUT_RATE

    /** Samples run from -1 to 1; anything outside is clamped. */
    fun write(out: OutputStream, samples: FloatArray, sampleRate: Int = SAMPLE_RATE) {
        val dataBytes = samples.size * 2
        val b = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataBytes)
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        b.putShort(1) // PCM
        b.putShort(1) // one channel
        b.putInt(sampleRate)
        b.putInt(sampleRate * 2) // bytes per second
        b.putShort(2) // bytes per sample frame
        b.putShort(16) // bits per sample
        b.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes)
        for (s in samples) b.putShort((s.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort())
        out.write(b.array())
    }
}
