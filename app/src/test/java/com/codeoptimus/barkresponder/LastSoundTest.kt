package com.codeoptimus.barkresponder

import org.junit.Assert.assertEquals
import org.junit.Test

class LastSoundTest {
    @Test
    fun `names the bark score and whether the app responded`() {
        assertEquals("Last sound: bark 82%, responded", LastSound.scored(0.82f, responded = true))
        assertEquals("Last sound: bark 3%, ignored", LastSound.scored(0.03f, responded = false))
    }

    @Test
    fun `never rounds an ignored score up to the setting`() {
        assertEquals("Last sound: bark 39%, ignored", LastSound.scored(0.399f, responded = false))
    }
}
