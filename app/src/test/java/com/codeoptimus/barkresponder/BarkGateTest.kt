package com.codeoptimus.barkresponder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BarkGateTest {
    private val onset = 100_000L
    private val windowStart = onset - 11_025
    private val windowDueAt = windowStart + 42_998

    @Test
    fun `waits until 0_725 s after the onset, then hands out the window that starts 0_25 s before it`() {
        val gate = BarkGate()
        assertEquals(BarkGate.Onset.WAIT, gate.onset(onset, minScore = 0.4f))

        assertNull(gate.dueWindow(windowDueAt - 1))
        assertEquals(windowStart, gate.dueWindow(windowDueAt))
        assertNull("the window is handed out once", gate.dueWindow(windowDueAt + 10_000))
    }

    @Test
    fun `an onset in the first 0_25 s waits for the window that starts at 0`() {
        val gate = BarkGate()
        gate.onset(5_000, minScore = 0.4f)

        assertNull(gate.dueWindow(42_997))
        assertEquals(0L, gate.dueWindow(42_998))
    }

    @Test
    fun `at 0_40 ignores a score of 0_39 and responds to 0_40`() {
        val gate = BarkGate()
        gate.onset(onset, minScore = 0.4f)
        assertFalse(gate.decide(0.39f, minScore = 0.4f))

        gate.onset(onset + 50_000, minScore = 0.4f)
        assertTrue(gate.decide(0.40f, minScore = 0.4f))
    }

    @Test
    fun `at 0 responds at the onset without a score`() {
        val gate = BarkGate()

        assertEquals(BarkGate.Onset.RESPOND, gate.onset(onset, minScore = 0f))
        assertNull(gate.dueWindow(windowDueAt))
    }

    @Test
    fun `at 1_0 ignores a score of 0_99`() {
        val gate = BarkGate()
        gate.onset(onset, minScore = 1f)

        assertFalse(gate.decide(0.99f, minScore = 1f))
    }

    @Test
    fun `ignores a second onset while one is pending and takes the next after a decision`() {
        val gate = BarkGate()
        gate.onset(onset, minScore = 0.4f)

        assertEquals(BarkGate.Onset.BUSY, gate.onset(onset + 5_000, minScore = 0.4f))
        assertEquals(windowStart, gate.dueWindow(windowDueAt))
        assertEquals(BarkGate.Onset.BUSY, gate.onset(onset + 40_000, minScore = 0.4f))

        gate.decide(0.9f, minScore = 0.4f)
        assertEquals(BarkGate.Onset.WAIT, gate.onset(onset + 60_000, minScore = 0.4f))
    }

    @Test
    fun `takes the next onset after a dropped candidate`() {
        val gate = BarkGate()
        gate.onset(onset, minScore = 0.4f)
        gate.drop()

        assertEquals(BarkGate.Onset.WAIT, gate.onset(onset + 5_000, minScore = 0.4f))
    }

    @Test
    fun `a decision with nothing pending never responds`() {
        assertFalse(BarkGate().decide(1f, minScore = 0.4f))
    }
}
