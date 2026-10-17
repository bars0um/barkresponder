package com.codeoptimus.barkresponder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseTimingTest {
    private val rate = 44100

    @Test
    fun `impulse, whistle and gunshot hold off for the impulse estimate`() {
        for (mode in listOf(ResponseMode.IMPULSE, ResponseMode.TONE, ResponseMode.GUNSHOT)) {
            assertEquals(mode.name, 1234L, ResponseTiming.lengthMs(mode, impulseMs = 1234L, distractSeconds = 9))
        }
    }

    @Test
    fun `the melody holds off new sounds until its last note ends`() {
        val playedMs = Melody.render(centerHz = 2000.0, amp = 0.7, sampleRate = rate).size * 1000L / rate
        val heldMs = ResponseTiming.lengthMs(ResponseMode.MELODY, impulseMs = 0L, distractSeconds = 0)
        assertTrue("held $heldMs ms, played $playedMs ms", heldMs >= playedMs)
    }

    @Test
    fun `a distract run holds off new sounds until it ends`() {
        for (setting in listOf(2, 4, 10, 600)) {
            val played = DistractSound.render(1L, setting, 2000, 6000, 0.7, rate).size * 1000L / rate
            val held = ResponseTiming.lengthMs(ResponseMode.DISTRACT, impulseMs = 0L, distractSeconds = setting)
            assertTrue("setting $setting: held $held ms, played $played ms", held >= played)
        }
    }

    @Test
    fun `the melody renders for its stated length`() {
        val s = Melody.render(centerHz = 2000.0, amp = 0.7, sampleRate = rate)
        assertEquals((Melody.seconds * rate).toInt(), s.size)
    }
}
