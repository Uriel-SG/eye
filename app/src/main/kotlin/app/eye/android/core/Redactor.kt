package app.eye.android.core

/**
 * Oscura dati personali prima di salvare/condividere (GDPR-09, GDPR-14). Puro Kotlin.
 * Scansione lineare per token: nessuna regex con gruppi ripetuti non limitati (niente StackOverflow, audit M-1).
 */
object Redactor {
    const val LINK = "[link nascosto]"
    const val PHONE = "[numero nascosto]"
    const val EMAIL = "[email nascosta]"
    const val HIDDEN = "[contenuto nascosto]"
    private const val MAX_TOKEN = 2048

    // Gruppi ripetuti solo con limite superiore piccolo e senza ambiguità.
    private val iban = Regex("""(?i)\b[a-z]{2}\d{2}(?: ?[a-z0-9]){11,30}\b""")
    private val fiscalCode = Regex("""(?i)\b[a-z]{6}\d{2}[a-z]\d{2}[a-z]\d{3}[a-z]\b""")

    fun redact(text: String): String = try {
        redactDigits(redactTokens(text.replace(iban, PHONE).replace(fiscalCode, PHONE)))
    } catch (_: Throwable) {
        HIDDEN
    }

    /**
     * Versione per l'invio all'AI: come [redact] (telefoni, IBAN, email, CF, OTP/numeri lunghi) ma gli URL restano
     * visibili, perché servono all'analisi. Gli URL sono messi al riparo con segnaposto di sole lettere.
     */
    fun redactKeepLinks(text: String): String = try {
        val urls = ArrayList<String>()
        val sb = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            if (text[i].isWhitespace()) { sb.append(text[i]); i++; continue }
            var j = i
            while (j < n && !text[j].isWhitespace()) j++
            val token = text.substring(i, j)
            var s = 0
            var e = token.length
            while (s < e && token[s] in LEAD) s++
            while (e > s && token[e - 1] in TRAIL) e--
            val core = token.substring(s, e)
            if (token.length <= MAX_TOKEN && s < e && !isEmail(core) && isUrlLike(core)) {
                urls += core.substringBefore('?').substringBefore('#')
                sb.append(token, 0, s).append(PH_OPEN).append(letters(urls.size - 1)).append(PH_CLOSE).append(token, e, token.length)
            } else sb.append(token)
            i = j
        }
        var out = redact(sb.toString())
        for (k in urls.indices.reversed()) out = out.replace(PH_OPEN + letters(k) + PH_CLOSE, urls[k])
        out
    } catch (_: Throwable) {
        HIDDEN
    }

    private const val PH_OPEN = ""
    private const val PH_CLOSE = ""
    private fun letters(n: Int): String = n.toString().map { 'a' + (it - '0') }.joinToString("")

    /** Anteprima oscurata, su una riga, con al massimo [max] caratteri. */
    fun preview(text: String, max: Int = 120): String {
        val one = redact(text).split(Regex("""\s+""")).filter { it.isNotEmpty() }.joinToString(" ")
        return if (one.length <= max) one else one.take(max - 1).trimEnd() + "…"
    }

    /** Dominio in forma «defanged» (non cliccabile): poste-verifica[.]com. */
    fun defang(domain: String): String = domain.replace(".", "[.]")

    private const val LEAD = "([<\"'«“"
    private const val TRAIL = ".,;:!?)]>\"'»”"

    private fun redactTokens(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            if (text[i].isWhitespace()) { sb.append(text[i]); i++; continue }
            var j = i
            while (j < n && !text[j].isWhitespace()) j++
            val token = text.substring(i, j)
            sb.append(if (token.length > MAX_TOKEN) HIDDEN else redactToken(token))
            i = j
        }
        return sb.toString()
    }

    private fun redactToken(token: String): String {
        var s = 0
        var e = token.length
        while (s < e && token[s] in LEAD) s++
        while (e > s && token[e - 1] in TRAIL) e--
        if (s >= e) return token
        val core = token.substring(s, e)
        val replacement = when {
            isEmail(core) -> EMAIL
            isUrlLike(core) -> LINK
            else -> return token
        }
        return token.substring(0, s) + replacement + token.substring(e)
    }

    private fun isEmail(t: String): Boolean {
        val at = t.indexOf('@')
        return at > 0 && at < t.length - 1 && t.indexOf('@', at + 1) < 0
    }

    private fun isUrlLike(t: String): Boolean {
        val lower = t.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("www.") || lower.contains("://")) return true
        val end = t.indexOfFirst { it == '/' || it == '?' || it == '#' || it == '\\' }.let { if (it < 0) t.length else it }
        var host = t.substring(0, end)
        val colon = host.lastIndexOf(':')
        if (colon > 0 && host.substring(colon + 1).all { it.isDigit() }) host = host.substring(0, colon)
        return isDomain(host)
    }

    /** Qualsiasi host con almeno due etichette e ultima etichetta alfabetica (non solo TLD in lista). */
    private fun isDomain(h: String): Boolean {
        if (h.length < 4 || h.length > 253) return false
        val labels = h.split('.')
        if (labels.size < 2) return false
        if (labels.any { it.isEmpty() || it.length > 63 || !it.all { c -> c.isLetterOrDigit() || c == '-' } }) return false
        val tld = labels.last()
        return tld.length in 2..24 && tld.all { it.isLetter() }
    }

    /** Sequenze di almeno 4 cifre (anche con separatori singoli: spazio . - ( ) +): OTP, PIN, telefoni, carte. */
    private fun redactDigits(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c.isDigit()) {
                var j = i
                var digits = 0
                var lastDigit = i
                while (j < n) {
                    val d = text[j]
                    if (d.isDigit()) { digits++; lastDigit = j; j++ }
                    else if ((d == ' ' || d == '.' || d == '-' || d == '(' || d == ')') && j + 1 < n && text[j + 1].isDigit()) j++
                    else break
                }
                if (digits >= 4) {
                    if (sb.isNotEmpty() && sb.last() == '+') sb.setLength(sb.length - 1)
                    sb.append(PHONE)
                } else sb.append(text, i, lastDigit + 1)
                i = lastDigit + 1
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
}
