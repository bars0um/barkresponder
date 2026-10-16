package com.codeoptimus.barkresponder

import org.junit.Assert.assertEquals
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
    fun `the melody renders for its stated length`() {
        val s = Melody.render(centerHz = 2000.0, amp = 0.7, sampleRate = rate)
        assertEquals((Melody.seconds * rate).toInt(), s.size)
    }
}
