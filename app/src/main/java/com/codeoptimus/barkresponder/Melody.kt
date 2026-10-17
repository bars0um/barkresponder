package com.codeoptimus.barkresponder

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * The Melody response: "Ode to Joy" anchored on the whistle frequency. Each note
 * sits a whole number of semitones from the centre, so the tune keeps its shape
 * wherever the whistle slider is.
 */
object Melody {
    // (semitones relative to centre, duration in 8th-note units)
    private val notes = intArrayOf(
        4, 4, 5, 7, 7, 5, 4, 2,   // E E F G G F E D
        0, 0, 2, 4, 4, 2, 2        // C C D E E D D
    )
    private val durs = intArrayOf(
        1, 1, 1, 1, 1, 1, 2, 1,   // last E held a beat
        1, 1, 1, 2, 1, 1, 2        // phrases end on 2-beat notes
    )
    private const val UNIT_SEC = 0.22   // one 8th note
    private const val REST_SEC = 0.04   // short gap between notes

    /** The tune's length in seconds, with a short tail after the last note. */
    val seconds: Double = durs.sumOf { it * UNIT_SEC } + (notes.size - 1) * REST_SEC + 0.15

    fun render(centerHz: Double, amp: Double, sampleRate: Int): ShortArray {
        val tau = 2.0 * PI
        val n = (seconds * sampleRate).toInt()
        val s = ShortArray(n)
        var tCursor = 0.0
        for (k in notes.indices) {
            val f = centerHz * 2.0.pow(notes[k] / 12.0)
            val dur = durs[k] * UNIT_SEC
            val attack = 0.01
            val release = 0.08
            for (i in 0 until (dur * sampleRate).toInt()) {
                val gi = (tCursor.toLong() + i).toInt() // global sample index
                if (gi >= n) break
                val t = i.toDouble() / sampleRate
                val env = when {
                    t < attack -> t / attack
                    t > dur - release -> (dur - t) / release
                    else -> 1.0
                }
                // fundamental + a light 2nd harmonic for a whistly timbre
                val wave = sin(tau * f * t) + 0.25 * sin(2.0 * tau * f * t)
                s[gi] = (wave * env * amp * 0.72 * Short.MAX_VALUE / 1.25).toInt().toShort()
            }
            tCursor += (dur * sampleRate).toLong() + (REST_SEC * sampleRate).toLong()
        }
        return s
    }
}
