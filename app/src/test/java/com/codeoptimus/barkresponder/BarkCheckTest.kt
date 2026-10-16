package com.codeoptimus.barkresponder

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executor

class BarkCheckTest {
    private val results = mutableListOf<Pair<Boolean, String>>()
    private val queued = mutableListOf<Runnable>()
    private val ring = AudioRing()
    private var minScore = 0.4f
    private var active = true
    private var scoreFn: (ShortArray) -> Float = { 0.9f }

    private fun scores(bark: Float) = FloatArray(521) { if (it == YamnetClassifier.BARK) bark else 0f }

    private fun check(executor: Executor = Executor { it.run() }) = BarkCheck(
        ring, { minScore }, executor, { scores(scoreFn(it)) }, { active },
    ) { respond, text -> results += respond to text }

    // Appends `count` samples, then lets the check look for a due window.
    private fun BarkCheck.feed(count: Int, ownSound: Boolean = false) {
        var left = count
        while (left > 0) {
            val n = minOf(1_792, left)
            ring.append(ShortArray(n) { 1_000 }, n, ownSound)
            afterAppend()
            left -= n
        }
    }

    @Test
    fun `responds when the score reaches the setting`() {
        val c = check()
        c.feed(20_000)
        c.onset(20_000)
        c.feed(40_000)

        assertEquals(listOf(true to "Last sound: bark 90%, responded"), results)
    }

    @Test
    fun `ignores a score below the setting`() {
        scoreFn = { 0.2f }
        val c = check()
        c.feed(20_000)
        c.onset(20_000)
        c.feed(40_000)

        assertEquals(listOf(false to "Last sound: bark 20%, ignored"), results)
    }

    @Test
    fun `scores the window that starts 0_25 s before the onset`() {
        var scored: ShortArray? = null
        scoreFn = { scored = it; 0.9f }
        val c = check()
        c.feed(20_000)
        ring.append(ShortArray(1) { 7 }, 1, false)
        c.onset(20_000 + 11_025)
        c.feed(50_000)

        assertEquals(42_998, scored!!.size)
        assertEquals(7.toShort(), scored!![0])
    }

    @Test
    fun `responds at the onset with the check off`() {
        minScore = 0f
        val c = check()
        c.feed(20_000)
        c.onset(20_000)

        assertEquals(listOf(true to LastSound.CHECK_OFF), results)
    }

    @Test
    fun `drops a window that overlaps our own sound and takes the next onset`() {
        val c = check()
        c.feed(20_000)
        c.onset(20_000)
        c.feed(5_000, ownSound = true)
        c.feed(40_000)
        c.onset(70_000)
        c.feed(40_000)

        assertEquals(listOf(false to LastSound.OVERLAPPED, true to "Last sound: bark 90%, responded"), results)
    }

    @Test
    fun `a failing classifier ignores the sound and takes the next onset`() {
        scoreFn = { throw UnsatisfiedLinkError("no liblitert_jni.so for this ABI") }
        val c = check()
        c.feed(20_000)
        c.onset(20_000)
        c.feed(40_000)
        scoreFn = { 0.9f }
        c.onset(65_000)
        c.feed(40_000)

        assertEquals(listOf(false to LastSound.FAILED, true to "Last sound: bark 90%, responded"), results)
    }

    @Test
    fun `a score that lands after listening stopped never responds`() {
        val c = check(Executor { queued += it })
        c.feed(20_000)
        c.onset(20_000)
        c.feed(40_000)
        active = false
        queued.forEach { it.run() }

        assertEquals(emptyList<Pair<Boolean, String>>(), results)
    }

    @Test
    fun `an onset in the first 0_25 s scores the first 0_975 s`() {
        var scored: ShortArray? = null
        scoreFn = { scored = it; 0.9f }
        val c = check()
        c.feed(5_000)
        c.onset(5_000)
        c.feed(40_000)

        assertEquals(listOf(true to "Last sound: bark 90%, responded"), results)
        assertEquals(42_998, scored!!.size)
    }
}
