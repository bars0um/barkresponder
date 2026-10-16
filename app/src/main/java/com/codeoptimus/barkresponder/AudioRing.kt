package com.codeoptimus.barkresponder

/**
 * The last [capacity] mic samples, addressed by an absolute sample counter
 * that starts at 0 when listening starts. It remembers which spans were
 * recorded while our own response played, so a window never scores our own
 * sound as a bark.
 */
class AudioRing(private val capacity: Int = 3 * Resampler.IN_RATE) {
    private val data = ShortArray(capacity)
    private val ownSpans = ArrayDeque<LongRange>()

    /** Samples appended since listening started. */
    @Volatile var written = 0L
        private set

    @Synchronized
    fun append(buf: ShortArray, n: Int, ownSound: Boolean) {
        val start = written
        for (i in 0 until n) data[((start + i) % capacity).toInt()] = buf[i]
        written = start + n
        if (ownSound && n > 0) {
            val last = ownSpans.lastOrNull()
            if (last != null && last.last + 1 == start) {
                ownSpans[ownSpans.size - 1] = last.first until written
            } else {
                ownSpans.addLast(start until written)
            }
        }
        while (ownSpans.isNotEmpty() && ownSpans.first().last < written - capacity) ownSpans.removeFirst()
    }

    /**
     * The samples from [start] to [start] + [length], or null when any of them
     * were overwritten, are not recorded yet, or were recorded while our own
     * response played.
     */
    @Synchronized
    fun window(start: Long, length: Int): ShortArray? {
        val end = start + length
        if (start < 0 || start < written - capacity || end > written) return null
        if (ownSpans.any { it.first < end && it.last >= start }) return null
        return ShortArray(length) { data[((start + it) % capacity).toInt()] }
    }
}
