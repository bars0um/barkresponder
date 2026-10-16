package com.codeoptimus.barkresponder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MiniJsonTest {
    @Test
    fun `reads objects, arrays, strings, numbers and literals`() {
        val v = MiniJson.parse(""" {"a": [1, -2.5e-3, true, false, null], "b": "x\"y\\zé", "c": {}} """)

        assertEquals(
            mapOf("a" to listOf(1.0, -0.0025, true, false, null), "b" to "x\"y\\zé", "c" to emptyMap<String, Any?>()),
            v,
        )
    }

    @Test
    fun `reads back what quote and number write`() {
        val text = "{" + MiniJson.quote("k\n\"") + ":[" + MiniJson.number(1.0E-5f) + "," + MiniJson.number(Float.NaN) + "]}"

        assertEquals(mapOf("k\n\"" to listOf(1.0E-5, 0.0)), MiniJson.parse(text))
    }

    @Test
    fun `reads nesting up to 8 deep`() {
        var expected: List<Any?> = emptyList()
        repeat(7) { expected = listOf(expected) }

        assertEquals(expected, MiniJson.parse("[".repeat(8) + "]".repeat(8)))
    }

    @Test
    fun `leaves out a skipped key and still refuses a cut value under it`() {
        assertEquals(mapOf("a" to 1.0, "c" to "]"), MiniJson.parse("""{"a":1,"s":[1,[2,"],"],{"x":3}],"c":"]"}""", skip = setOf("s")))
        assertThrows(IllegalArgumentException::class.java) { MiniJson.parse("""{"s":[1,2""", skip = setOf("s")) }
    }

    @Test
    fun `refuses cut, trailing and deeply nested text`() {
        for (bad in listOf("{\"a\":1", "[1,2", "\"abc", "{} x", "[".repeat(9) + "]".repeat(9), "nul", "{\"a\" 1}")) {
            assertThrows(bad, IllegalArgumentException::class.java) { MiniJson.parse(bad) }
        }
    }
}
