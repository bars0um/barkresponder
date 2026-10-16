package com.codeoptimus.barkresponder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class DistractSoundTest {
    private val rate = 44100
    private val full = Short.MAX_VALUE.toDouble()

    private fun render(
        seed: Long = 1L, seconds: Int = 4, lowHz: Int = 2000, highHz: Int = 6000, level: Double = 0.7,
    ) = DistractSound.render(seed, seconds, lowHz, highHz, level, rate)

    @Test
    fun `a run lasts the length it is given`() {
        assertEquals(4 * rate, render(seconds = 4).size)
        assertEquals(2 * rate, render(seconds = 2).size)
        assertEquals(10 * rate, render(seconds = 10).size)
    }

    @Test
    fun `a length outside 2 to 10 seconds is held to that range`() {
        assertEquals(2 * rate, render(seconds = 0).size)
        assertEquals(10 * rate, render(seconds = 600).size)
        assertEquals(2, DistractSound.seconds(-5))
        assertEquals(10, DistractSound.seconds(600))
    }

    @Test
    fun `the loudest sample reaches the volume and never passes it`() {
        for (seed in 1L..5L) {
            val peak = render(seed = seed, level = 0.7).maxOf { abs(it.toInt()) }
            assertTrue("seed $seed peak $peak", peak <= 0.7 * full)
            assertTrue("seed $seed peak $peak", peak >= 0.6 * full)
        }
    }

    @Test
    fun `nearly all the sound lies inside the pitch range`() {
        for ((lo, hi) in listOf(2000 to 6000, 1000 to 8000, 3000 to 4000, 1000 to 2000)) {
            for (seed in 1L..3L) {
                val outside = energyOutside(render(seed = seed, lowHz = lo, highHz = hi), lo.toDouble(), hi.toDouble())
                assertTrue("$lo-$hi Hz seed $seed: ${"%.5f".format(outside)} of the energy outside", outside < 0.001)
            }
        }
    }

    @Test
    fun `asking for pitches past 1 to 8 kHz plays nothing outside 1 to 8 kHz`() {
        val outside = energyOutside(render(lowHz = 20, highHz = 20000), 1000.0, 8000.0)
        assertTrue("${"%.5f".format(outside)} of the energy outside 1-8 kHz", outside < 0.001)
        assertEquals(1000, DistractSound.lowHz(20))
        assertEquals(8000, DistractSound.highHz(20, 20000))
    }

    @Test
    fun `a low end above the high end still leaves a playable range`() {
        assertEquals(7000, DistractSound.lowHz(7000))
        assertEquals(8000, DistractSound.highHz(7000, 1500))
        val outside = energyOutside(render(lowHz = 7000, highHz = 1500), 7000.0, 8000.0)
        assertTrue("${"%.5f".format(outside)} of the energy outside 7-8 kHz", outside < 0.001)
    }

    @Test
    fun `each seed plays a different run and the same seed plays the same one`() {
        assertArrayEquals(render(seed = 42L), render(seed = 42L))
        assertFalse(render(seed = 42L).contentEquals(render(seed = 43L)))
    }

    @Test
    fun `each sound fades in and out with no click`() {
        // A sine at the top of the range moves at most this far between samples;
        // a sound that starts or stops on a non-zero sample jumps further.
        for (seed in 1L..5L) {
            val s = render(seed = seed, lowHz = 1000, highHz = 2000, level = 0.7)
            val bound = 0.7 * full * 2 * PI * 2000 / rate * 1.1
            val jump = (1 until s.size).maxOf { abs(s[it] - s[it - 1]) }
            assertTrue("seed $seed jump $jump over $bound", jump <= bound)
            assertEquals(0, s.first().toInt())
            assertTrue("seed $seed ends on ${s.last()}", abs(s.last().toInt()) <= bound)
        }
    }

    @Test
    fun `each sound starts quiet and swells`() {
        // A sine starts at 0 with or without a fade, so look at the first 2 ms:
        // a sound that starts at full level reaches its peak inside a quarter cycle.
        for (seed in 1L..5L) {
            val s = render(seed = seed, level = 0.7)
            val starts = soundStarts(s)
            assertTrue("seed $seed: ${starts.size} sounds", starts.size >= 6)
            for (at in starts) {
                val early = (at until minOf(at + rate / 500, s.size)).maxOf { abs(s[it].toInt()) }
                assertTrue("seed $seed sound at $at reaches $early in its first 2 ms", early <= 0.3 * 0.7 * full)
            }
        }
    }

    // Where each sound starts: the first audible sample after 20 ms or more of quiet.
    private fun soundStarts(s: ShortArray): List<Int> {
        val out = mutableListOf<Int>()
        var quiet = rate
        for ((i, x) in s.withIndex()) {
            if (abs(x.toInt()) > 0) {
                if (quiet >= rate / 50) out += i
                quiet = 0
            } else quiet++
        }
        return out
    }

    @Test
    fun `sounds keep coming until the run ends`() {
        for (seed in 1L..10L) {
            val s = render(seed = seed, seconds = 4)
            val longestQuiet = longestRunBelow(s, 0.01 * full)
            assertTrue("seed $seed: ${longestQuiet * 1000 / rate} ms quiet", longestQuiet <= rate * 3 / 10)
            assertTrue("seed $seed: quiet at the end", tailQuiet(s, 0.01 * full) <= rate * 3 / 10)
        }
    }

    @Test
    fun `every run mixes bursts of quick chirps with long trills`() {
        for (seed in 1L..20L) {
            val sounds = soundLengthsMs(render(seed = seed, seconds = 4))
            assertTrue("seed $seed: $sounds", sounds.size >= 6)
            assertTrue("seed $seed has no burst of chirps: $sounds", sounds.zipWithNext().any { (a, b) -> a <= 130 && b <= 130 })
            assertTrue("seed $seed has no trill: $sounds", sounds.any { it >= 300 })
        }
    }

    // Each stretch of sound between gaps of 20 ms or more, in ms.
    private fun soundLengthsMs(s: ShortArray): List<Int> {
        val floor = 0.01 * full
        val gap = rate / 50
        val out = mutableListOf<Int>()
        var start = -1; var quiet = 0
        for ((i, x) in s.withIndex()) {
            if (abs(x.toInt()) >= floor) {
                if (start < 0) start = i
                quiet = 0
            } else if (start >= 0 && ++quiet >= gap) {
                out += (i - quiet - start + 1) * 1000 / rate
                start = -1; quiet = 0
            }
        }
        if (start >= 0) out += (s.size - quiet - start) * 1000 / rate
        return out
    }

    private fun longestRunBelow(s: ShortArray, floor: Double): Int {
        var best = 0; var run = 0
        for (x in s) { if (abs(x.toInt()) < floor) { run++; if (run > best) best = run } else run = 0 }
        return best
    }

    private fun tailQuiet(s: ShortArray, floor: Double): Int {
        var i = s.size - 1
        while (i >= 0 && abs(s[i].toInt()) < floor) i--
        return s.size - 1 - i
    }

    /** Share of the signal's energy below [lo] or above [hi], from one FFT of the whole run. */
    private fun energyOutside(s: ShortArray, lo: Double, hi: Double): Double {
        var n = 1
        while (n < s.size) n = n shl 1
        val re = DoubleArray(n) { if (it < s.size) s[it].toDouble() else 0.0 }
        val im = DoubleArray(n)
        fft(re, im)
        var total = 0.0; var outside = 0.0
        for (k in 1 until n / 2) {
            val e = re[k] * re[k] + im[k] * im[k]
            val hz = k.toDouble() * rate / n
            total += e
            if (hz < lo || hz > hi) outside += e
        }
        assertTrue("silent run", total > 0)
        return outside / total
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { re[i] = re[j].also { re[j] = re[i] }; im[i] = im[j].also { im[j] = im[i] } }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar; im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar; im[i + k] += ai
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
