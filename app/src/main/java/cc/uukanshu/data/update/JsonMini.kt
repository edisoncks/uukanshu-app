package cc.uukanshu.data.update

/**
 * Minimal JSON reader for the GitHub Releases payload.
 *
 * `org.json` ships with Android but is stubbed ("not mocked") in local JVM
 * unit tests, and adding kotlinx.serialization for three fields is overkill —
 * so this covers exactly objects/arrays/strings/numbers/literals with
 * proper escape handling. Returns maps/lists/strings/numbers/booleans/null.
 */
internal object JsonMini {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        // Strict: trailing garbage (truncated/corrupt payload + junk) must fail
        // instead of silently returning the prefix. GitHub payloads are exact;
        // ignoring the tail would mask corruption as a valid release.
        if (p.i != text.length) throw IllegalArgumentException("trailing data at ${p.i}")
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun value(): Any? {
            ws()
            if (i >= s.length) throw IllegalArgumentException("unexpected end of JSON")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("bad literal at $i")
            i += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            i++ // {
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') {
                i++
                return m
            }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw IllegalArgumentException("bad key at $i")
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("bad object at $i")
                i++
                m[k] = value()
                ws()
                if (i >= s.length) throw IllegalArgumentException("unterminated object")
                if (s[i] == '}') {
                    i++
                    return m
                }
                if (s[i] != ',') throw IllegalArgumentException("bad object at $i")
                i++
            }
        }

        private fun arr(): List<Any?> {
            i++ // [
            val l = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') {
                i++
                return l
            }
            while (true) {
                l.add(value())
                ws()
                if (i >= s.length) throw IllegalArgumentException("unterminated array")
                if (s[i] == ']') {
                    i++
                    return l
                }
                if (s[i] != ',') throw IllegalArgumentException("bad array at $i")
                i++
            }
        }

        private fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw IllegalArgumentException("unterminated string")
                val c = s[i++]
                if (c == '"') return sb.toString()
                if (c != '\\') {
                    sb.append(c)
                    continue
                }
                if (i >= s.length) throw IllegalArgumentException("bad escape")
                when (val e = s[i++]) {
                    '"', '\\', '/' -> sb.append(e)
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 4 > s.length) throw IllegalArgumentException("bad unicode escape")
                        sb.append(s.substring(i, i + 4).toInt(16).toChar())
                        i += 4
                    }
                    else -> throw IllegalArgumentException("bad escape \\$e")
                }
            }
        }

        private fun num(): Number {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val raw = s.substring(start, i)
            return raw.toLongOrNull() ?: raw.toDoubleOrNull()
                ?: throw IllegalArgumentException("bad number $raw")
        }
    }
}
