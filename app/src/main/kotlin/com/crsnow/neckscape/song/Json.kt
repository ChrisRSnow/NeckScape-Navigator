package com.crsnow.neckscape.song

/**
 * A small JSON reader/writer, so Android and desktop parse payloads with the
 * same code and no dependency. Objects become [LinkedHashMap]s (key order is
 * kept), arrays [List]s, numbers [Double]s.
 */
object Json {
    class ParseError(message: String, val offset: Int) : Exception("$message at character $offset")

    fun parse(text: String): Any? {
        val p = Parser(text)
        val value = p.value()
        p.skipSpace()
        if (p.i != text.length) throw ParseError("Unexpected text after JSON", p.i)
        return value
    }

    fun write(value: Any?, indent: String? = null): String = StringBuilder().also { write(it, value, indent, "") }.toString()

    private fun write(sb: StringBuilder, v: Any?, indent: String?, pad: String) {
        val inner = if (indent != null) pad + indent else ""
        val nl = if (indent != null) "\n" else ""
        when (v) {
            null -> sb.append("null")
            is String -> string(sb, v)
            is Boolean -> sb.append(v)
            is Double -> if (v % 1.0 == 0.0 && kotlin.math.abs(v) < 1e15) sb.append(v.toLong()) else sb.append(v)
            is Float -> write(sb, v.toDouble(), indent, pad)
            is Number -> sb.append(v)
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append('{').append(nl)
                v.entries.forEachIndexed { i, (k, x) ->
                    if (i > 0) sb.append(',').append(nl)
                    sb.append(inner)
                    string(sb, k.toString())
                    sb.append(if (indent != null) ": " else ":")
                    write(sb, x, indent, inner)
                }
                sb.append(nl).append(if (indent != null) pad else "").append('}')
            }
            is Iterable<*> -> {
                val items = v.toList()
                if (items.isEmpty()) { sb.append("[]"); return }
                sb.append('[').append(nl)
                items.forEachIndexed { i, x ->
                    if (i > 0) sb.append(',').append(nl)
                    sb.append(inner)
                    write(sb, x, indent, inner)
                }
                sb.append(nl).append(if (indent != null) pad else "").append(']')
            }
            else -> string(sb, v.toString())
        }
    }

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", c.code)) else sb.append(c)
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0
        var depth = 0

        fun skipSpace() {
            while (i < s.length && s[i] in " \t\r\n") i++
        }

        fun value(): Any? {
            skipSpace()
            if (i >= s.length) throw ParseError("Unexpected end of JSON", i)
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw ParseError("Unexpected '$c'", i)
            }
        }

        private fun nest() {
            if (++depth > 64) throw ParseError("JSON nested too deeply", i)
        }

        private fun obj(): Map<String, Any?> {
            nest()
            val out = LinkedHashMap<String, Any?>()
            i++
            skipSpace()
            if (s.getOrNull(i) == '}') { i++; depth--; return out }
            while (true) {
                skipSpace()
                if (s.getOrNull(i) != '"') throw ParseError("Expected a key", i)
                val k = str()
                skipSpace()
                if (s.getOrNull(i) != ':') throw ParseError("Expected ':'", i)
                i++
                out[k] = value()
                skipSpace()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    '}' -> { i++; depth--; return out }
                    else -> throw ParseError("Expected ',' or '}'", i)
                }
            }
        }

        private fun arr(): List<Any?> {
            nest()
            val out = ArrayList<Any?>()
            i++
            skipSpace()
            if (s.getOrNull(i) == ']') { i++; depth--; return out }
            while (true) {
                out += value()
                skipSpace()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    ']' -> { i++; depth--; return out }
                    else -> throw ParseError("Expected ',' or ']'", i)
                }
            }
        }

        private fun str(): String {
            val sb = StringBuilder()
            i++
            while (true) {
                if (i >= s.length) throw ParseError("Unterminated string", i)
                when (val c = s[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s.getOrNull(i++)) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw ParseError("Bad \\u escape", i)
                                sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: throw ParseError("Bad \\u escape", i))
                                i += 4
                            }
                            else -> throw ParseError("Bad escape", i)
                        }
                    }
                    else -> if (c < ' ') throw ParseError("Control character in string", i) else sb.append(c)
                }
            }
        }

        private fun num(): Double {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            return s.substring(start, i).toDoubleOrNull() ?: throw ParseError("Bad number", start)
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw ParseError("Unexpected '${s[i]}'", i)
            i += word.length
            return v
        }
    }
}
