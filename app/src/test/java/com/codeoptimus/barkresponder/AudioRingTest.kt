package com.codeoptimus.barkresponder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AudioRingTest {
    // Writes `count` samples whose values are their own absolute index (mod 30000).
    private fun AudioRing.feed(count: Int, ownSound: Boolean = false, chunk: Int = 1_792) {
        var left = count
        while (left > 0) {
            val n = minOf(chunk, left)
            val start = written
            append(ShortArray(n) { ((start + it) % 30_000).toShort() }, n, ownSound)
            left -= n
        }
    }

    private fun expected(start: Long, length: Int) =
        ShortArray(length) { ((start + it) % 30_000).toShort() }

    @Test
    fun `returns a window across the wrap point in the order written`() {
        val ring = AudioRing(capacity = 10_000)
        ring.feed(14_000)

        assertArrayEquals(expected(8_000, 4_000), ring.window(8_000, 4_000))
    }

    @Test
    fun `returns null for a window older than the ring holds`() {
        val ring = AudioRing()
        ring.feed(4 * 44_100)

        assertNull(ring.window(44_100 - 1, 1_000))
        assertArrayEquals(expected(44_100, 1_000), ring.window(44_100, 1_000))
    }

    @Test
    fun `returns null for a window not yet recorded`() {
        val ring = AudioRing()
        ring.feed(10_000)

        assertNull(ring.window(9_500, 1_000))
        assertNull(ring.window(-1, 1_000))
    }

    @Test
    fun `returns null for a window that overlaps our own sound`() {
        val ring = AudioRing()
        ring.feed(20_000)
        ring.feed(5_000, ownSound = true)
        ring.feed(20_000)

        assertNull(ring.window(15_000, 6_000))
        assertNull(ring.window(24_999, 100))
        assertNotNull(ring.window(25_000, 10_000))
        assertArrayEquals(expected(25_000, 10_000), ring.window(25_000, 10_000))
        assertArrayEquals(expected(10_000, 10_000), ring.window(10_000, 10_000))
    }

    @Test
    fun `holds three seconds by default`() {
        val ring = AudioRing()
        ring.feed(5 * 44_100)

        assertEquals(3 * 44_100, ring.window(2 * 44_100L, 3 * 44_100)!!.size)
    }
    @Test
    fun `a clip keeps the samples around our own sound and silences that sound`() {
        val ring = AudioRing()
        ring.feed(20_000)
        ring.feed(5_000, ownSound = true)
        ring.feed(20_000)

        val clip = ring.clip(15_000, 20_000)!!
        val want = expected(15_000, 20_000)
        for (i in 5_000 until 10_000) want[i] = 0
        assertArrayEquals(want, clip)
    }

    @Test
    fun `a clip is null when it is older than the ring holds or not yet recorded`() {
        val ring = AudioRing()
        ring.feed(4 * 44_100)

        assertNull(ring.clip(44_100 - 1, 1_000))
        assertNull(ring.clip(4 * 44_100L - 500, 1_000))
        assertArrayEquals(expected(44_100, 1_000), ring.clip(44_100, 1_000))
    }
}
