package com.codeoptimus.barkresponder

/**
 * Decides whether a loud onset gets a response. It holds one candidate at a
 * time: new onsets are ignored while one waits for its window or its score.
 * The scored window starts 0.25 s before the onset and lasts 0.975 s, so it
 * is complete 0.725 s after the onset. At a setting of 0 the check is off and
 * the onset responds at once, as before the classifier.
 */
class BarkGate {
    enum class Onset { RESPOND, WAIT, BUSY }

    private var pendingOnset: Long? = null
    private var windowTaken = false

    @Synchronized
    fun onset(onsetSample: Long, minScore: Float): Onset {
        if (pendingOnset != null) return Onset.BUSY
        if (minScore <= 0f) return Onset.RESPOND
        pendingOnset = onsetSample
        windowTaken = false
        return Onset.WAIT
    }

    /** The pending window's start once [written] covers it; handed out once. */
    @Synchronized
    fun dueWindow(written: Long): Long? {
        val onset = pendingOnset ?: return null
        if (windowTaken) return null
        // An onset in the first 0.25 s of listening scores the first 0.975 s.
        val start = maxOf(0L, onset - PRE_ROLL)
        if (written < start + WINDOW) return null
        windowTaken = true
        return start
    }

    /** Settles the pending candidate; true when the app should respond. */
    @Synchronized
    fun decide(score: Float, minScore: Float): Boolean {
        if (pendingOnset == null) return false
        pendingOnset = null
        return score >= minScore
    }

    /** Settles the pending candidate without a response. */
    @Synchronized
    fun drop() {
        pendingOnset = null
    }

    companion object {
        /** 0.25 s at 44.1 kHz. */
        const val PRE_ROLL = 11_025L
        /** 0.975 s at 44.1 kHz, which resamples to the model's 15,600. */
        const val WINDOW = 42_998
    }
}

/** The main screen's line about the last sound the loudness check passed on. */
object LastSound {
    fun scored(score: Float, responded: Boolean): String =
        "Last sound: bark ${(score * 100).toInt()}%, ${if (responded) "responded" else "ignored"}"

    const val CHECK_OFF = "Last sound: responded, bark check off"
    const val OVERLAPPED = "Last sound: overlapped our response, ignored"
    const val FAILED = "Last sound: bark check failed, ignored"
}
