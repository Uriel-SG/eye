package app.eye.engine

import java.net.IDN
import java.text.Normalizer
import java.util.Locale

/** Limite di sicurezza (SEC-05): l'input è troncato prima di qualsiasi elaborazione. */
const val MAX_INPUT_CHARS = 10_000

/** Normalizzazione e utilità su testo/domini. Solo Kotlin/JDK puro. */
internal object Text {
    private val invisible = Regex("[\\u00AD\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\uFEFF]")
    /** Rimuove caratteri invisibili (soft hyphen, zero-width, bidi): i browser li ignorano negli URL (H-3). */
    fun stripInvisible(s: String): String = invisible.replace(s, "")

    private val spaces = Regex("[ \\t\\u00A0]{2,}")

    /** Confusables minimali (UTS #39 ristretto): cirillico/greco → latino. */
    private val confusables: Map<Char, Char> = buildMap {
        val pairs = "а=a е=e о=o р=p с=c х=x у=y і=i ј=j ѕ=s һ=h ԁ=d ԛ=q ԝ=w ӏ=l к=k м=m т=t н=h в=b " +
            "ɑ=a ο=o ρ=p ν=v υ=u α=a ε=e ι=i κ=k τ=t χ=x ү=y ǀ=l ı=i ⅼ=l ⅰ=i"
        for (p in pairs.split(' ')) put(p[0], p[2])
    }

    fun foldConfusables(s: String): String {
        var out: StringBuilder? = null
        for (i in s.indices) {
            val m = confusables[s[i]]
            if (m != null) {
                if (out == null) out = StringBuilder(s)
                out.setCharAt(i, m)
            }
        }
        return out?.toString() ?: s
    }

    /** Testo per le regole: NFKC, senza caratteri invisibili, minuscolo, confusables piegati. */
    fun normalizeForRules(raw: String): String {
        var s = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        s = invisible.replace(s, "")
        s = s.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
        s = s.lowercase(Locale.ROOT)
        s = foldConfusables(s)
        return spaces.replace(s, " ")
    }

    /**
     * Scheletro per confronto di marchi: piega confusables e leetspeak.
     * l/1/i/| → i, 0 → o, 3 → e, 4 → a, 5 → s, rn → m, vv → w.
     */
    fun skeleton(label: String): String {
        val s = foldConfusables(Normalizer.normalize(label, Normalizer.Form.NFKC).lowercase(Locale.ROOT))
            .replace("rn", "m").replace("vv", "w")
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when (c) {
                    'l', '1', '|', 'í', 'ì' -> 'i'
                    '0' -> 'o'
                    '3' -> 'e'
                    '4' -> 'a'
                    '5' -> 's'
                    else -> c
                },
            )
        }
        return sb.toString()
    }

    fun editDistance(a: String, b: String, max: Int = 3): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > max) return max + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    fun toAscii(host: String): String? = try {
        IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT)
    } catch (_: Exception) {
        null
    }

    fun toUnicode(host: String): String = try {
        IDN.toUnicode(host, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT)
    } catch (_: Exception) {
        host
    }

    /** True se contiene lettere non ASCII (potenziale omografo). */
    fun hasNonAsciiLetters(s: String): Boolean = s.any { it.isLetter() && it.code > 0x7F }

    fun mixedScripts(s: String): Boolean {
        val scripts = HashSet<Character.UnicodeScript>()
        for (c in s) if (c.isLetter()) scripts.add(Character.UnicodeScript.of(c.code))
        return scripts.size > 1
    }

    /**
     * Anti-ripetizione: nei testi lunghi (> 1500 caratteri) elimina le finestre di 6 parole gia' viste,
     * cosi' un messaggio ripetuto N volte costa come una sola copia. Non aggiunge ne' sposta contenuto:
     * resta sempre la prima occorrenza (con i suoi separatori originali).
     */
    fun collapseRepeats(s: String): String {
        if (s.length <= 1500) return s
        val toks = Regex("\\S+").findAll(s).toList()
        val k = 6
        if (toks.size < 2 * k) return s
        val seen = HashSet<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < toks.size) {
            val key = if (i + k <= toks.size) (i until i + k).joinToString(" ") { toks[it].value } else null
            if (key != null && !seen.add(key)) {
                i += k
                continue
            }
            if (sb.isNotEmpty()) sb.append(s, toks[i - 1].range.last + 1, toks[i].range.first) // separatore originale
            sb.append(toks[i].value)
            i++
        }
        return sb.toString()
    }
}
