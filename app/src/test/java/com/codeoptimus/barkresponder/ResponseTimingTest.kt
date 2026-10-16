package com.codeoptimus.barkresponder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseTimingTest {
    private val rate = 44100

    @Test
    fun `impulse, whistle and gunshot hold off for the impulse estimate`() {
        for (mode in listOf(ResponseMode.IMPULSE, ResponseMode.TONE, ResponseMode.GUNSHOT)) {
            assertEquals(mode.name, 1234L, ResponseTiming.lengthMs(mode, impulseMs = 1234L))
        }
    }

    @Test
    fun `the melody holds off new sounds until its last note ends`() {
        val playedMs = Melody.render(centerHz = 2000.0, amp = 0.7, sampleRate = rate).size * 1000L / rate
        val heldMs = ResponseTiming.lengthMs(ResponseMode.MELODY, impulseMs = 0L)
        assertTrue("held $heldMs ms, played $playedMs ms", heldMs >= playedMs)
    }

    @Test
    fun `the melody renders for its stated length`() {
        val s = Melody.render(centerHz = 2000.0, amp = 0.7, sampleRate = rate)
        assertEquals((Melody.seconds * rate).toInt(), s.size)
    }
}
