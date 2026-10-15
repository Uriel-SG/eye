package app.eye.engine

internal object Hosts {
    private val ipv4 = Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$")
    private val numeric = Regex("^(?:0x[0-9a-f]{6,8}|\\d{8,10})$")

    fun isIp(host: String): Boolean = ipv4.matches(host) || host.contains(':') || numeric.matches(host)
}

internal data class ParsedUrl(
    val raw: String,
    val hasScheme: Boolean,
    val scheme: String?,
    val hasUserInfo: Boolean,
    /** Host in ASCII (punycode). */
    val host: String,
    /** Host in forma Unicode (per rilevare omografi). */
    val hostUnicode: String,
    val path: String,
    /** eTLD+1 in ASCII. */
    val registrable: String,
    val isIp: Boolean,
) {
    val isPunycode: Boolean get() = host.split('.').any { it.startsWith("xn--") }

    /** Etichette a sinistra dell'eTLD+1. */
    val subdomains: List<String>
        get() {
            if (isIp || host == registrable) return emptyList()
            return host.removeSuffix(".$registrable").split('.')
        }

    /** Etichetta principale (senza suffisso), in Unicode. */
    val mainLabelUnicode: String
        get() {
            if (isIp) return host
            val u = Text.toUnicode(registrable)
            val suffix = Text.toUnicode(PublicSuffix.suffixOf(registrable))
            return if (u == suffix) u else u.removeSuffix(".$suffix")
        }
}

internal object Extractors {
    private const val MAX_URLS = 30
    private const val MAX_TOKENS = 5_000

    private val token = Regex("\\S+")

    private val webNoSlash = Regex("(?<![\\p{L}\\p{N}])(?i:(https?|hxxps?|ftp)):/?(?=[\\p{L}\\p{N}])")
    // Il lookbehind fa partire il match solo a inizio sequenza di lettere: stessi risultati, niente O(n²) su token lunghi.
    private val schemeSep = Regex("(?<![a-zA-Z])([a-zA-Z]+):[/\\\\]{2,}")
    private val hostLabel = Regex("^[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?$")

    private fun validPort(p: String): Boolean = // p inizia con ':'
        p.length in 1..6 && p[0] == ':' && p.drop(1).all { it in '0'..'9' } && (p.length == 1 || p.drop(1).toInt() <= 65535)

    /** Decodifica %XX (UTF-8) come i browser; null se la sequenza e' invalida. */
    private fun percentDecode(h: String): String? {
        if ('%' !in h) return h
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < h.length) {
            val c = h[i]
            if (c == '%') {
                val v = h.substring(i + 1, minOf(i + 3, h.length)).takeIf { it.length == 2 }?.toIntOrNull(16) ?: return null
                out.write(v); i += 3
            } else { out.write(c.toString().toByteArray(Charsets.UTF_8)); i++ }
        }
        return out.toString(Charsets.UTF_8)
    }

    /** Host ASCII valido: etichette [a-z0-9_-] non vuote e <= 63 (rifiuta spazi, @, %, backslash...). */
    private fun validHost(h: String): Boolean = h.split('.').all { it.length in 1..63 && hostLabel.matches(it) }
    private val noScheme = Regex("^(?:www\\.)?[\\p{L}\\p{N}-]{1,63}(?:\\.[\\p{L}\\p{N}-]{1,63}){1,6}(?:[:/\\\\?#]\\S*)?$")
    private val obf = Regex("\\[\\.]|\\(\\.\\)|\\[dot]|\\(dot\\)", RegexOption.IGNORE_CASE)
    private const val LEAD = "([{<\"'«“‘*"
    private const val TRAIL = ".,;:!?)]}>\"'»”’…*"

    /** Risultato della scansione: URL estratti + numero di anomalie (link non risolti/malformati). */
    class UrlScan(val urls: List<ParsedUrl>, val anomalies: Int)

    fun urls(text: String): List<ParsedUrl> = scan(text).urls

    /** Separatori dopo i quali, se il token intero non e' un URL, si cerca un URL (es. `accedi:evil.top`, `->evil.top`). */
    private const val BOUNDARY = ":*=>(<\"'«“‘»”’|,;![{)]}&^~`"
    private val allowedSchemes = setOf("http", "https", "ftp")
    // frammento "simile a un dominio": label.tld; non preceduto da @ (email)
    private val domainLike = Regex("(?<![\\p{L}\\p{N}@._-])(?:[\\p{L}\\p{N}-]{1,63}\\.){1,6}\\p{L}{2,24}(?![\\p{L}\\p{N}-])")

    private fun schemeMatch(t: String): MatchResult? = schemeSep.find(t) ?: webNoSlash.find(t)

    /** Come parse(), ma tollera punteggiatura attaccata: prova il token, poi i suffissi dopo ogni separatore. */
    private fun parseLoose(t: String): ParsedUrl? {
        parse(t)?.let { return it }
        var tries = 0
        for (i in t.indices) {
            if (t[i] in BOUNDARY && i + 1 < t.length) {
                if (++tries > 40) break
                val s = t.substring(i + 1).trimStart { it in LEAD }.trimEnd { it in TRAIL }
                if (s.length >= 4) parse(s)?.let { return it }
            }
        }
        return null
    }

    fun scan(textIn: String): UrlScan {
        // Normalizza offuscamenti: hxxp / [.] ecc.
        val text = obf.replace(textIn, ".")
        val out = ArrayList<ParsedUrl>()
        var anomalies = 0
        val seen = HashSet<String>()
        val toks = token.findAll(text).take(MAX_TOKENS).toList()
        for ((idx, m) in toks.withIndex()) {
            if (out.size >= MAX_URLS) { anomalies++; break } // oltre ~30 URL distinti: segnale di anomalia
            val t = m.value.trimStart { it in LEAD }.trimEnd { it in TRAIL }
            if (t.length < 4) continue
            val sm = schemeMatch(t)
            val u = parseLoose(t)
            if (u != null) { if (seen.add(u.raw)) out.add(u) }
            else if (sm != null && sm.groupValues[1].lowercase().replace("hxxp", "http") in allowedSchemes) anomalies++
            // WHATWG: TAB/CR/LF dentro l'URL vengono eliminati dal browser -> candidato unito al token successivo.
            if (sm != null && idx + 1 < toks.size) {
                val nx = toks[idx + 1]
                val gap = text.substring(m.range.last + 1, nx.range.first)
                if (gap.isNotEmpty() && gap.all { it == '\t' || it == '\r' || it == '\n' } && schemeMatch(nx.value) == null) {
                    val merged = (t + nx.value).trimEnd { it in TRAIL }
                    parse(merged)?.let { if (it.host != u?.host && seen.add(it.raw)) out.add(it) }
                }
            }
        }
        val urls = out.distinctBy { it.raw }
        return UrlScan(urls, anomalies + unresolvedFragments(text, urls))
    }

    /** Conta i frammenti simili a un dominio (TLD plausibile) che non risultano estratti come URL. */
    private fun unresolvedFragments(text: String, urls: List<ParsedUrl>): Int {
        val covered = ArrayList<IntRange>()
        for (u in urls) {
            var from = 0
            while (true) {
                val i = text.indexOf(u.raw, from)
                if (i < 0) break
                covered.add(i until i + u.raw.length)
                from = i + u.raw.length
                if (covered.size > 200) break
            }
        }
        var n = 0
        var seen = 0
        for (m in domainLike.findAll(text)) {
            if (++seen > 300) break
            if (covered.any { m.range.first in it }) continue
            val frag = m.value.lowercase()
            val ascii = Text.toAscii(frag) ?: frag
            if (!PublicSuffix.isKnownTld(ascii.substringAfterLast('.'))) continue
            if (urls.any { it.host == ascii || it.host.endsWith(".$ascii") || it.hostUnicode == frag }) continue
            n++
        }
        return n
    }

    fun parse(tokenIn: String): ParsedUrl? {
        var t = tokenIn
        if (t.length > 2048) t = t.substring(0, 2048)
        var scheme: String? = null
        val sm = schemeMatch(t)
        val rest: String
        if (sm != null) {
            val st = sm.groups[1]!!.range.first
            scheme = sm.groupValues[1].lowercase()
            if (scheme == "hxxp") scheme = "http"
            if (scheme == "hxxps") scheme = "https"
            rest = t.substring(sm.range.last + 1)
            t = t.substring(st)
        } else {
            val slash = t.indexOfFirst { it == '/' || it == '\\' }.let { if (it < 0) Int.MAX_VALUE else it }
            if (t.indexOf('@') in 0 until slash) return null // email
            if (!noScheme.matches(t)) return null
            rest = t
        }
        if (scheme != null && scheme !in setOf("http", "https", "ftp")) return null
        // Come i browser (WHATWG): per gli schemi speciali backslash equivale a '/'.
        val authEnd = rest.indexOfFirst { it == '/' || it == '\\' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        var authority = rest.substring(0, authEnd)
        val path = rest.substring(authEnd)
        val at = authority.lastIndexOf('@')
        val hasUser = at >= 0
        if (hasUser) authority = authority.substring(at + 1)
        var host = authority
        if (host.startsWith("[")) {
            val close = host.indexOf(']')
            if (close < 0) return null
            val port = host.substring(close + 1)
            if (port.isNotEmpty() && !validPort(port)) return null
            host = host.substring(1, close)
            if (!host.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) return null
        } else {
            val colon = host.indexOf(':')
            if (colon >= 0) {
                if (!validPort(host.substring(colon))) return null
                host = host.substring(0, colon)
            }
            host = percentDecode(host) ?: return null
        }
        host = host.lowercase()
        if (host.endsWith(".")) host = host.dropLast(1) // un solo punto finale (FQDN)
        if (host.isEmpty() || host.length > 253) return null
        val ascii = if (authority.startsWith("[")) host else (Text.toAscii(host) ?: return null)
        if (!authority.startsWith("[") && !validHost(ascii)) return null
        val isIp = Hosts.isIp(ascii)
        if (scheme == null) {
            if (isIp) return null
            val tld = ascii.substringAfterLast('.')
            if (!PublicSuffix.isKnownTld(tld)) return null
            if (!ascii.contains('.')) return null
        }
        val noWww = ascii
        return ParsedUrl(
            raw = t,
            hasScheme = scheme != null,
            scheme = scheme,
            hasUserInfo = hasUser,
            host = noWww,
            hostUnicode = Text.toUnicode(noWww),
            path = path,
            registrable = PublicSuffix.registrable(noWww),
            isIp = isIp,
        )
    }

    // ---------- Telefono ----------
    private val phone = Regex(
        "(?<![\\w+])(?:(?:\\+|00)\\d{1,3}[ .-]?\\d{2,4}[ .-]?\\d{3,4}[ .-]?\\d{0,4}" +
            "|(?:3\\d{2}|0\\d{1,3}|8\\d{2}|1[46]\\d)[ .-]?\\d{3,4}[ .-]?\\d{2,4})(?!\\d)",
    )

    fun phones(text: String, mask: List<IntRange> = emptyList()): List<String> {
        val out = LinkedHashSet<String>()
        for (m in phone.findAll(text)) {
            if (mask.any { m.range.first in it }) continue
            val s = m.value.trim()
            val intl = s.startsWith("+") || s.startsWith("00")
            val digits = s.filter { it.isDigit() }
            val national = if (intl) digits.removePrefix("00") else digits
            if (national.length !in 6..14) continue
            // Scarta sequenze che sembrano date (es. 12.10.2026) o importi.
            if (!intl && Regex("^\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}$").matches(s)) continue
            out.add(if (intl) "+$national" else digits)
            if (out.size >= 20) break
        }
        return out.toList()
    }

    // ---------- IBAN ----------
    private val iban = Regex("(?<![A-Za-z0-9])[A-Za-z]{2}\\d{2}(?: ?[A-Za-z0-9]){11,30}(?![A-Za-z0-9])")
    private val ibanLen = mapOf(
        "IT" to 27, "SM" to 27, "VA" to 22, "DE" to 22, "FR" to 27, "ES" to 24, "GB" to 22, "NL" to 18,
        "BE" to 16, "CH" to 21, "LU" to 20, "AT" to 20, "PT" to 25, "IE" to 22, "MT" to 31, "LT" to 20,
        "LV" to 21, "EE" to 20, "FI" to 18, "SE" to 24, "DK" to 18, "NO" to 15, "PL" to 28, "CZ" to 24,
        "SK" to 24, "SI" to 19, "HR" to 21, "HU" to 28, "RO" to 24, "BG" to 22, "GR" to 27, "CY" to 28,
        "LI" to 21, "MC" to 27, "AD" to 24, "IS" to 26, "TR" to 26, "UA" to 29, "AL" to 28, "RS" to 22,
    )

    fun ibanValid(candidate: String): Boolean {
        val s = candidate.uppercase()
        if (s.length < 15 || s.length > 34) return false
        val expected = ibanLen[s.take(2)] ?: return false
        if (s.length != expected) return false
        val rearr = s.substring(4) + s.substring(0, 4)
        val sb = StringBuilder()
        for (c in rearr) {
            when {
                c in '0'..'9' -> sb.append(c)
                c in 'A'..'Z' -> sb.append(c - 'A' + 10)
                else -> return false
            }
        }
        // mod 97 a blocchi (evita BigInteger per l'hot path, ma è comunque O(n))
        var rem = 0
        for (c in sb) rem = (rem * 10 + (c - '0')) % 97
        return rem == 1
    }

    /** Restituisce (IBAN normalizzato senza spazi, range nel testo). */
    fun ibansWithRanges(text: String): List<Pair<String, IntRange>> {
        val out = ArrayList<Pair<String, IntRange>>()
        for (m in iban.findAll(text)) {
            val compact = m.value.filter { it != ' ' }.uppercase()
            val len = ibanLen[compact.take(2)] ?: continue
            if (compact.length < len) continue
            val cand = compact.substring(0, len)
            if (ibanValid(cand)) out.add(cand to m.range)
            if (out.size >= 10) break
        }
        return out
    }

    // ---------- Importi ----------
    private val amount = Regex(
        "(?:(?:€|eur(?:o)?)\\s?\\d{1,3}(?:\\.\\d{3}){0,3}(?:,\\d{1,2})?|\\d{1,3}(?:\\.\\d{3}){0,3}(?:[,.]\\d{1,2})?\\s?(?:€|euro\\b|eur\\b))",
        RegexOption.IGNORE_CASE,
    )

    fun amounts(text: String): List<String> =
        amount.findAll(text).map { it.value.trim() }.distinct().take(20).toList()

    /** Valore numerico di un importo estratto (euro), o null. */
    fun amountValue(a: String): Double? {
        val digits = a.filter { it.isDigit() || it == ',' || it == '.' }
        if (digits.isEmpty()) return null
        val norm = if (digits.contains(',')) digits.replace(".", "").replace(',', '.')
        else if (Regex("\\.\\d{1,2}$").containsMatchIn(digits)) digits else digits.replace(".", "")
        return norm.toDoubleOrNull()
    }
}
