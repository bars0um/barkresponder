package com.codeoptimus.barkresponder

/**
 * Just enough JSON for the clip sidecars, in plain Kotlin so JVM tests run it
 * (Android's org.json is a stub there). Objects become maps, arrays lists,
 * numbers doubles. [parse] throws [IllegalArgumentException] on bad input.
 */
object MiniJson {
    /** Values under a key in [skip] are checked for shape but left out of the result. */
    fun parse(text: String, skip: Set<String> = emptySet()): Any? {
        val p = Parser(text, skip)
        val v = p.value()
        p.skipSpace()
        require(p.i == text.length) { "trailing text at ${p.i}" }
        return v
    }

    fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    /** JSON has no NaN or infinity; a score never holds one, so write 0 if it did. */
    fun number(f: Float): String = if (f.isFinite()) f.toString() else "0.0"

    private const val MAX_DEPTH = 8

    private class Parser(val s: String, val skipKeys: Set<String>) {
        var i = 0
        private var depth = 0

        fun skipSpace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skipSpace()
            require(i < s.length) { "unexpected end" }
            return when (s[i]) {
                '{' -> nested { obj() }
                '[' -> nested { arr() }
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> num()
            }
        }

        private fun <T> nested(read: () -> T): T {
            require(++depth <= MAX_DEPTH) { "nested deeper than $MAX_DEPTH" }
            return read().also { depth-- }
        }

        // Steps over one value without building it; numbers are not converted.
        fun skipValue() {
            skipSpace()
            require(i < s.length) { "unexpected end" }
            when (s[i]) {
                '{', '[' -> {
                    var depth = 0
                    while (true) {
                        require(i < s.length) { "unexpected end" }
                        when (s[i]) {
                            '"' -> { str(); continue }
                            '{', '[' -> depth++
                            '}', ']' -> depth--
                        }
                        i++
                        if (depth == 0) return
                    }
                }
                '"' -> str()
                else -> {
                    val start = i
                    while (i < s.length && s[i] !in ",}] \t\r\n") i++
                    require(i > start) { "expected a value at $start" }
                }
            }
        }

        private fun word(w: String, v: Any?): Any? {
            require(s.startsWith(w, i)) { "bad literal at $i" }
            i += w.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            skipSpace()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                skipSpace()
                require(i < s.length && s[i] == '"') { "expected a key at $i" }
                val k = str()
                skipSpace()
                require(i < s.length && s[i] == ':') { "expected : at $i" }
                i++
                if (k in skipKeys) skipValue() else m[k] = value()
                skipSpace()
                require(i < s.length) { "unexpected end" }
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> throw IllegalArgumentException("expected , or } at ${i - 1}")
                }
            }
        }

        private fun arr(): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            skipSpace()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                l.add(value())
                skipSpace()
                require(i < s.length) { "unexpected end" }
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> throw IllegalArgumentException("expected , or ] at ${i - 1}")
                }
            }
        }

        private fun str(): String {
            i++
            val b = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated string" }
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        require(i < s.length) { "unterminated escape" }
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> b.append(e)
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000C')
                            'n' -> b.append('\n')
                            'r' -> b.append('\r')
                            't' -> b.append('\t')
                            'u' -> {
                                require(i + 4 <= s.length) { "short \\u escape" }
                                b.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("bad escape at ${i - 1}")
                        }
                    }
                    else -> b.append(c)
                }
            }
        }

        private fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDoubleOrNull() ?: throw IllegalArgumentException("bad number at $start")
        }
    }
}
