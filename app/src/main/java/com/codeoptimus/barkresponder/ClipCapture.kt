package com.codeoptimus.barkresponder

import java.util.concurrent.Executor

/**
 * Saves a 2-second clip around each candidate the bark check takes: 0.5 s
 * before the onset to 1.5 s after, resampled to 16 kHz, with the scores of
 * the window the check scored. [BarkCheck] calls [start] at the onset and
 * settles the [Pending] it gets back; the mic thread calls [afterAppend]
 * after each buffer. The clip is cut once its last sample is recorded and
 * waits on [worker], the classifier's thread, behind any score queued before
 * it. Resampling and the write run on [writer], so a slow disk never holds up
 * the next score. Our own response inside the clip is saved as silence.
 */
class ClipCapture(
    private val ring: AudioRing,
    private val enabled: () -> Boolean,
    private val worker: Executor,
    /** Scores a window when the check is off and never scored one. */
    private val score: (ShortArray) -> FloatArray,
    private val store: SampleStore,
    private val writer: Executor,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onSaved: () -> Unit = {},
) {
    inner class Pending internal constructor(
        val onset: Long,
        private val recordedAt: Long,
        @Volatile private var minScore: Float,
        @Volatile private var responded: Boolean,
    ) {
        @Volatile private var scores: FloatArray? = null
        @Volatile private var dropped = false

        /** The check scored this candidate and decided at [minScore]. */
        fun scored(scores: FloatArray, responded: Boolean, minScore: Float) {
            this.scores = scores
            this.responded = responded
            this.minScore = minScore
        }

        /** Nothing gets saved: the window overlapped our sound, or scoring failed. */
        fun drop() {
            dropped = true
        }

        internal fun finish(clip: ShortArray) {
            if (dropped || !enabled()) return // saving may have been turned off since the onset
            val s = scores ?: try {
                val offset = (maxOf(0L, onset - BarkGate.PRE_ROLL) - clipStart(onset)).toInt()
                score(clip.copyOfRange(offset, offset + BarkGate.WINDOW))
            } catch (_: Throwable) {
                return // Every saved clip carries scores; one we cannot score stays unsaved.
            }
            val meta = ClipMeta(recordedAt, s, responded, minScore)
            writer.execute {
                try {
                    store.save(Resampler.resample(clip, OUT_SAMPLES), meta)
                    onSaved()
                } catch (_: Exception) {
                    // A full disk loses this clip; listening goes on.
                }
            }
        }
    }

    private val pending = ArrayDeque<Pending>()

    /**
     * Called on the mic thread when the check takes a candidate. With
     * [respondedAtOnset] the check is off and responded at once; otherwise
     * the check settles the returned [Pending]. Null when saving is off.
     */
    fun start(onsetSample: Long, minScore: Float, respondedAtOnset: Boolean): Pending? {
        if (!enabled()) return null
        val p = Pending(onsetSample, clock(), minScore, respondedAtOnset)
        synchronized(pending) { pending.addLast(p) }
        return p
    }

    fun afterAppend() {
        while (true) {
            val p = synchronized(pending) {
                val first = pending.firstOrNull() ?: return
                if (ring.written < clipStart(first.onset) + CLIP) return
                pending.removeFirst()
            }
            val clip = ring.clip(clipStart(p.onset), CLIP) ?: continue
            worker.execute { p.finish(clip) }
        }
    }

    private fun clipStart(onset: Long) = maxOf(0L, onset - PRE_ROLL)

    companion object {
        /** 0.5 s at 44.1 kHz. */
        const val PRE_ROLL = 22_050L
        /** 2 s at 44.1 kHz. */
        const val CLIP = 88_200
        /** 2 s at 16 kHz. */
        const val OUT_SAMPLES = 32_000
    }
}
