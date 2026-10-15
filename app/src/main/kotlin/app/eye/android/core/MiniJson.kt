package app.eye.android.core

/**
 * JSON minimale in puro Kotlin (org.json è stubbato nei test JVM e non c'è altra libreria nell'app).
 * Valori: Map<String, Any?>, List<Any?>, String, Double/Long, Boolean, null. Profondità limitata: input non fidato.
 */
object MiniJson {
    private const val MAX_DEPTH = 32

    /** Restituisce null se [s] non è JSON valido. */
    fun parse(s: String): Any? = try { parseOrThrow(s) } catch (_: IllegalArgumentException) { null }

    fun parseOrThrow(s: String): Any? {
        val p = Parser(s)
        p.ws()
        val v = p.value(0)
        p.ws()
        require(p.i == s.length) { "dati dopo il valore" }
        return v
    }

    /** Estrae e interpreta il primo oggetto `{...}` bilanciato del testo (tollera prosa o ```json attorno). */
    fun firstObject(text: String): Map<String, Any?>? {
        var start = text.indexOf('{')
        var tries = 0
        while (start >= 0 && tries++ < 8) {
            val end = balancedEnd(text, start)
            if (end > 0) {
                @Suppress("UNCHECKED_CAST")
                (parse(text.substring(start, end)) as? Map<String, Any?>)?.let { return it }
            }
            start = text.indexOf('{', start + 1)
        }
        return null
    }

    private fun balancedEnd(t: String, start: Int): Int {
        var depth = 0
        var inStr = false
        var i = start
        while (i < t.length) {
            val c = t[i]
            if (inStr) {
                if (c == '\\') i++ else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i + 1 }
            }
            i++
        }
        return -1
    }

    fun stringify(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean, is Int, is Long -> sb.append(v.toString())
            is Double -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString()); sb.append(':'); write(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) { if (!first) sb.append(','); first = false; write(sb, x) }
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(depth: Int): Any? {
            require(depth <= MAX_DEPTH) { "troppo annidato" }
            require(i < s.length) { "fine inattesa" }
            val c = s[i]
            return when {
                c == '{' -> obj(depth)
                c == '[' -> arr(depth)
                c == '"' -> str()
                c == 't' -> lit("true", true)
                c == 'f' -> lit("false", false)
                c == 'n' -> lit("null", null)
                c == '-' || c.isDigit() -> num()
                else -> throw IllegalArgumentException("carattere inatteso")
            }
        }
        fun lit(w: String, v: Any?): Any? { require(s.startsWith(w, i)); i += w.length; return v }
        fun peek(): Char { require(i < s.length) { "fine inattesa" }; return s[i] }
        fun obj(depth: Int): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; ws()
            if (peek() == '}') { i++; return m }
            while (true) {
                ws(); require(peek() == '"'); val k = str()
                ws(); require(peek() == ':'); i++; ws()
                m[k] = value(depth + 1)
                ws()
                val c = peek()
                if (c == ',') i++ else if (c == '}') { i++; return m } else throw IllegalArgumentException("oggetto")
            }
        }
        fun arr(depth: Int): List<Any?> {
            val l = ArrayList<Any?>()
            i++; ws()
            if (peek() == ']') { i++; return l }
            while (true) {
                ws(); l += value(depth + 1); ws()
                val c = peek()
                if (c == ',') i++ else if (c == ']') { i++; return l } else throw IllegalArgumentException("array")
            }
        }
        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "stringa non chiusa" }
                val c = s[i++]
                if (c == '"') return sb.toString()
                if (c != '\\') { sb.append(c); continue }
                require(i < s.length)
                when (val e = s[i++]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000c')
                    '"', '\\', '/' -> sb.append(e)
                    'u' -> {
                        require(i + 4 <= s.length)
                        val hex = s.substring(i, i + 4)
                        require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "unicode" }
                        sb.append(hex.toInt(16).toChar())
                        i += 4
                    }
                    else -> throw IllegalArgumentException("escape")
                }
            }
        }
        fun num(): Any {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(st, i)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: throw IllegalArgumentException("numero")
        }
    }
}
