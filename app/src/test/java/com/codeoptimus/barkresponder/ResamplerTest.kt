package com.codeoptimus.barkresponder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {
    private fun sine(hz: Double, n: Int, level: Double = 0.5) =
        ShortArray(n) { i -> (sin(2 * PI * hz * i / 44_100.0) * level * Short.MAX_VALUE).toInt().toShort() }

    private fun rms(x: FloatArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size)

    @Test
    fun `a 1 kHz sine comes out as 15600 samples at 1 kHz`() {
        val out = Resampler.toModelInput(sine(1000.0, 42_998))

        assertEquals(15_600, out.size)
        var crossings = 0
        for (i in 1 until out.size) if ((out[i - 1] < 0f) != (out[i] < 0f)) crossings++
        val hz = crossings / 2.0 / (out.size / 16_000.0)
        assertEquals(1000.0, hz, 10.0)
    }

    @Test
    fun `keeps the level and the timing of a 1 kHz sine`() {
        val input = sine(1000.0, 42_998)
        val out = Resampler.toModelInput(input)

        assertEquals(0.5 / sqrt(2.0), rms(out), 0.01)
        // Output sample i sits at input position i * 44100 / 16000.
        for (i in listOf(100, 5_000, 12_345, 15_000)) {
            val expected = 0.5 * sin(2 * PI * 1000.0 * (i * 2.75625) / 44_100.0)
            assertEquals(expected, out[i].toDouble(), 0.01)
        }
    }

    @Test
    fun `a 10 kHz sine is filtered out before downsampling`() {
        val input = sine(10_000.0, 42_998)
        val inRms = sqrt(input.sumOf { (it / 32768.0) * (it / 32768.0) } / input.size)

        val out = Resampler.toModelInput(input)

        assertTrue("output RMS ${rms(out)} against input $inRms", rms(out) < 0.05 * inRms)
    }

    @Test
    fun `scales full-scale samples into the range -1 to 1`() {
        val out = Resampler.toModelInput(sine(200.0, 42_998, level = 1.0))

        val peak = out.maxOf { kotlin.math.abs(it) }
        assertTrue("peak $peak", peak in 0.9f..1.0f)
    }

    @Test
    fun `pads a short input and trims a long one to the model length`() {
        assertEquals(15_600, Resampler.toModelInput(ShortArray(1_000)).size)
        assertEquals(15_600, Resampler.toModelInput(ShortArray(88_200)).size)
    }
    @Test
    fun `resamples a 2 s clip of 88200 samples to 32000 at 1 kHz`() {
        val out = Resampler.resample(sine(1000.0, 88_200), 32_000)

        assertEquals(32_000, out.size)
        for (i in listOf(100, 16_000, 31_000)) {
            val expected = 0.5 * sin(2 * PI * 1000.0 * (i * 2.75625) / 44_100.0)
            assertEquals(expected, out[i].toDouble(), 0.01)
        }
    }
}
