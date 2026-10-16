package com.codeoptimus.barkresponder

import java.util.concurrent.Executor

/**
 * Runs one listening session's bark check between the mic loop and the
 * classifier. The mic thread calls [onset] for each loud candidate and
 * [afterAppend] after each buffer goes into [ring]; [score] runs on
 * [scorer] and returns all of YAMNet's class scores. Every candidate ends in
 * exactly one [onResult] call, unless the session is no longer [active] when
 * its score lands. Each candidate it takes also goes to [clips], when given.
 */
class BarkCheck(
    private val ring: AudioRing,
    private val minScore: () -> Float,
    private val scorer: Executor,
    private val score: (ShortArray) -> FloatArray,
    private val active: () -> Boolean,
    private val clips: ClipCapture? = null,
    private val onResult: (respond: Boolean, lastSound: String) -> Unit,
) {
    private val gate = BarkGate()
    // Touched only on the mic thread: the clip of the candidate the gate holds.
    private var pendingClip: ClipCapture.Pending? = null

    fun onset(onsetSample: Long) {
        val min = minScore()
        when (gate.onset(onsetSample, min)) {
            BarkGate.Onset.RESPOND -> {
                clips?.start(onsetSample, min, respondedAtOnset = true)
                onResult(true, LastSound.CHECK_OFF)
            }
            BarkGate.Onset.WAIT -> pendingClip = clips?.start(onsetSample, min, respondedAtOnset = false)
            BarkGate.Onset.BUSY -> {}
        }
    }

    fun afterAppend() {
        val start = gate.dueWindow(ring.written) ?: return
        val window = ring.window(start, BarkGate.WINDOW)
        val clip = pendingClip
        pendingClip = null
        if (window == null) {
            clip?.drop()
            gate.drop()
            onResult(false, LastSound.OVERLAPPED)
            return
        }
        scorer.execute {
            // Throwable: a missing native library throws an Error, and an Error
            // that leaves the executor's thread kills the app.
            val scores = try { score(window) } catch (_: Throwable) { null }
            val bark = scores?.getOrNull(YamnetClassifier.BARK)
            if (scores == null || bark == null) {
                clip?.drop()
                gate.drop()
                onResult(false, LastSound.FAILED)
            } else {
                val min = minScore()
                val respond = gate.decide(bark, min)
                val live = active()
                clip?.scored(scores, respond && live, min)
                if (live) onResult(respond, LastSound.scored(bark, respond))
            }
        }
    }
}
