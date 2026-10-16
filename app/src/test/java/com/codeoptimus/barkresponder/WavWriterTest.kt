package com.codeoptimus.barkresponder

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class WavWriterTest {
    private fun write(samples: FloatArray): ByteBuffer {
        val out = ByteArrayOutputStream()
        WavWriter.write(out, samples)
        return ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun ByteBuffer.tag(at: Int) = String(ByteArray(4) { get(at + it) }, Charsets.US_ASCII)

    @Test
    fun `writes a 16 kHz mono 16-bit header for 32000 samples`() {
        val samples = FloatArray(32_000) { (0.5 * sin(2 * PI * 440 * it / 16_000.0)).toFloat() }
        val wav = write(samples)

        assertEquals("RIFF", wav.tag(0))
        assertEquals(36 + 64_000, wav.getInt(4))
        assertEquals("WAVE", wav.tag(8))
        assertEquals("fmt ", wav.tag(12))
        assertEquals(16, wav.getInt(16))
        assertEquals("PCM", 1, wav.getShort(20).toInt())
        assertEquals("channels", 1, wav.getShort(22).toInt())
        assertEquals("sample rate", 16_000, wav.getInt(24))
        assertEquals("byte rate", 32_000, wav.getInt(28))
        assertEquals("block align", 2, wav.getShort(32).toInt())
        assertEquals("bits", 16, wav.getShort(34).toInt())
        assertEquals("data", wav.tag(36))
        assertEquals("data size", 64_000, wav.getInt(40))
        assertEquals(44 + 64_000, wav.limit())

        for (i in samples.indices) {
            val back = wav.getShort(44 + 2 * i) / 32_767f
            assertEquals("sample $i", samples[i], back, 1f / 32_767f)
        }
    }

    @Test
    fun `clamps samples outside -1 to 1`() {
        val wav = write(floatArrayOf(1.5f, -1.5f, 1f, 0f))

        assertEquals(32_767, wav.getShort(44).toInt())
        assertEquals(-32_767, wav.getShort(46).toInt())
        assertEquals(32_767, wav.getShort(48).toInt())
        assertEquals(0, abs(wav.getShort(50).toInt()))
    }
}
