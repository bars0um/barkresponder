package com.codeoptimus.barkresponder

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * The Distract response: a run of high, bird-like sounds (trills, chirps, rising
 * squeaks and warbles) in a random order at random pitches, so a dog doesn't get
 * used to one pattern.
 *
 * Every sound is a pure sine whose pitch stays inside the chosen range, and the
 * range itself stays inside 1 to 8 kHz: what a tablet speaker plays, and nothing
 * ultrasonic.
 */
object DistractSound {
    const val MIN_HZ = 1000
    const val MAX_HZ = 8000
    const val MIN_SPAN_HZ = 1000
    const val MIN_SECONDS = 2
    const val MAX_SECONDS = 10
    const val DEFAULT_SECONDS = 4
    const val DEFAULT_LOW_HZ = 2000
    const val DEFAULT_HIGH_HZ = 6000

    // Pitches stay this far inside the range: a sound's fades and trills spread
    // its energy about this wide around the pitch it plays.
    private const val EDGE_HZ = 250.0
    private const val FADE_SEC = 0.008
    private const val SHORTEST_SEC = 0.04

    fun seconds(setting: Int): Int = setting.coerceIn(MIN_SECONDS, MAX_SECONDS)
    fun lowHz(low: Int): Int = low.coerceIn(MIN_HZ, MAX_HZ - MIN_SPAN_HZ)
    fun highHz(low: Int, high: Int): Int = high.coerceIn(lowHz(low) + MIN_SPAN_HZ, MAX_HZ)

    private enum class Kind { TRILL, CHIRP, SQUEAK, WARBLE }

    /** [level] is the peak, 0 to 1 of full scale, like the other responses' volume. */
    fun render(seed: Long, seconds: Int, lowHz: Int, highHz: Int, level: Double, sampleRate: Int): ShortArray {
        val lo = lowHz(lowHz) + EDGE_HZ
        val hi = highHz(lowHz, highHz) - EDGE_HZ
        val n = seconds(seconds) * sampleRate
        val out = ShortArray(n)
        val r = Random(seed)
        val peak = level.coerceIn(0.0, 1.0) * Short.MAX_VALUE
        var at = 0
        val bag = ArrayDeque<Kind>()
        var last: Kind? = null
        var chirpsLeft = 0
        while (true) {
            // Every four sounds play each kind once, in a new order each time, never the
            // same kind twice running. Chirps come in bursts of 2 to 4.
            if (chirpsLeft == 0 && bag.isEmpty()) {
                do { bag.clear(); bag.addAll(Kind.entries.shuffled(r)) } while (bag.first() == last)
            }
            val kind = if (chirpsLeft > 0) Kind.CHIRP else bag.removeFirst()
            if (kind == Kind.CHIRP) {
                if (chirpsLeft == 0) chirpsLeft = 2 + r.nextInt(3)
                chirpsLeft--
            }
            last = kind
            val len = minOf((duration(kind, r) * sampleRate).toInt(), n - at)
            if (len < SHORTEST_SEC * sampleRate) break
            val pitch = pitchCurve(kind, r, lo, hi, len.toDouble() / sampleRate)
            val gain = 0.75 + 0.25 * r.nextDouble()
            val fade = minOf((FADE_SEC * sampleRate).toInt(), len / 4)
            var phase = 0.0
            for (i in 0 until len) {
                val env = when {
                    i < fade -> 0.5 - 0.5 * cos(PI * i / fade)
                    i >= len - fade -> 0.5 - 0.5 * cos(PI * (len - 1 - i) / fade)
                    else -> 1.0
                }
                out[at + i] = (sin(phase) * env * gain * peak).toInt().toShort()
                phase += 2 * PI * pitch(i.toDouble() / sampleRate) / sampleRate
            }
            val gapSec = if (chirpsLeft > 0) 0.03 + 0.03 * r.nextDouble() else 0.03 + 0.12 * r.nextDouble()
            at += len + (gapSec * sampleRate).toInt()
            if (at >= n) break
        }
        return out
    }

    private fun duration(kind: Kind, r: Random): Double = when (kind) {
        Kind.CHIRP -> 0.05 + 0.07 * r.nextDouble()
        Kind.SQUEAK -> 0.15 + 0.20 * r.nextDouble()
        Kind.TRILL -> 0.30 + 0.40 * r.nextDouble()
        Kind.WARBLE -> 0.30 + 0.30 * r.nextDouble()
    }

    // A pitch picked evenly in octaves between a and b.
    private fun between(r: Random, a: Double, b: Double) = a * (b / a).pow(r.nextDouble())

    /** The sound's pitch in Hz at each moment, seconds from its start. Never leaves lo..hi. */
    private fun pitchCurve(kind: Kind, r: Random, lo: Double, hi: Double, len: Double): (Double) -> Double =
        when (kind) {
            // a quick sweep, up or down
            Kind.CHIRP -> sweep(between(r, lo, hi), between(r, lo, hi), len, curve = 1.0)
            Kind.SQUEAK -> {
                // a rising squeak that speeds up as it climbs
                val f1 = between(r, lo, hi)
                sweep(maxOf(lo, f1 / (1.3 + 0.7 * r.nextDouble())), f1, len, curve = 2.0)
            }
            Kind.TRILL -> wobble(r, lo, hi, rate = 16.0 + 12.0 * r.nextDouble(), depth = 0.05 + 0.07 * r.nextDouble())
            Kind.WARBLE -> wobble(r, lo, hi, rate = 5.0 + 4.0 * r.nextDouble(), depth = 0.10 + 0.15 * r.nextDouble())
        }

    // A glide from f0 to f1, even in octaves when curve is 1, slow then fast above 1.
    private fun sweep(f0: Double, f1: Double, len: Double, curve: Double): (Double) -> Double =
        { t -> f0 * (f1 / f0).pow((t / len).pow(curve)) }

    // A pitch that swings around a centre: fast and narrow is a trill, slow and wide a warble.
    private fun wobble(r: Random, lo: Double, hi: Double, rate: Double, depth: Double): (Double) -> Double {
        val d = minOf(depth, 0.9 * (hi - lo) / (hi + lo))
        val centre = between(r, lo / (1 - d), hi / (1 + d))
        return { t -> centre * (1 + d * sin(2 * PI * rate * t)) }
    }
}
