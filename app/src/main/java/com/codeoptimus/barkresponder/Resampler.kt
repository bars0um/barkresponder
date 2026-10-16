package com.codeoptimus.barkresponder

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns 44.1 kHz mic samples into YAMNet's input: 15,600 floats at 16 kHz in
 * the range -1 to 1. A 63-tap Hamming windowed-sinc low-pass at 7.2 kHz
 * removes what 16 kHz cannot carry, then linear interpolation picks the new
 * sample points. On ESC-50 this stayed within 0.086 of an FFT resample's Bark
 * score (issue 2).
 */
object Resampler {
    const val IN_RATE = 44_100
    const val OUT_RATE = 16_000
    const val MODEL_SAMPLES = 15_600

    private const val TAPS = 63
    private const val CUTOFF_HZ = 7_200.0

    private val kernel: DoubleArray = run {
        val fc = CUTOFF_HZ / IN_RATE
        val mid = (TAPS - 1) / 2.0
        val h = DoubleArray(TAPS) { i ->
            val m = i - mid
            val sinc = if (m == 0.0) 2 * fc else sin(2 * PI * fc * m) / (PI * m)
            sinc * (0.54 - 0.46 * cos(2 * PI * i / (TAPS - 1)))
        }
        val sum = h.sum()
        DoubleArray(TAPS) { h[it] / sum }
    }

    fun toModelInput(samples: ShortArray): FloatArray {
        val half = TAPS / 2
        val filtered = DoubleArray(samples.size) { i ->
            var acc = 0.0
            for (k in 0 until TAPS) {
                val j = i + k - half
                if (j in samples.indices) acc += kernel[k] * samples[j]
            }
            acc / 32768.0
        }
        val step = IN_RATE.toDouble() / OUT_RATE
        return FloatArray(MODEL_SAMPLES) { i ->
            val pos = i * step
            val j = pos.toInt()
            val y = when {
                j + 1 < filtered.size -> {
                    val frac = pos - j
                    filtered[j] * (1 - frac) + filtered[j + 1] * frac
                }
                j < filtered.size -> filtered[j]
                else -> 0.0
            }
            // The filter's passband ripple can lift a full-scale peak just past 1.
            y.coerceIn(-1.0, 1.0).toFloat()
        }
    }
}
