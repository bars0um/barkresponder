package com.codeoptimus.barkresponder

import java.util.concurrent.Executor

/**
 * Runs one listening session's bark check between the mic loop and the
 * classifier. The mic thread calls [onset] for each loud candidate and
 * [afterAppend] after each buffer goes into [ring]; [score] runs on
 * [scorer]. Every candidate ends in exactly one [onResult] call, unless the
 * session is no longer [active] when its score lands.
 */
class BarkCheck(
    private val ring: AudioRing,
    private val minScore: () -> Float,
    private val scorer: Executor,
    private val score: (ShortArray) -> Float,
    private val active: () -> Boolean,
    private val onResult: (respond: Boolean, lastSound: String) -> Unit,
) {
    private val gate = BarkGate()

    fun onset(onsetSample: Long) {
        if (gate.onset(onsetSample, minScore()) == BarkGate.Onset.RESPOND) onResult(true, LastSound.CHECK_OFF)
    }

    fun afterAppend() {
        val start = gate.dueWindow(ring.written) ?: return
        val window = ring.window(start, BarkGate.WINDOW)
        if (window == null) {
            gate.drop()
            onResult(false, LastSound.OVERLAPPED)
            return
        }
        scorer.execute {
            // Throwable: a missing native library throws an Error, and an Error
            // that leaves the executor's thread kills the app.
            val bark = try { score(window) } catch (_: Throwable) { null }
            if (bark == null) {
                gate.drop()
                onResult(false, LastSound.FAILED)
            } else {
                val respond = gate.decide(bark, minScore())
                if (active()) onResult(respond, LastSound.scored(bark, respond))
            }
        }
    }
}
