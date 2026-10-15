package app.eye.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Una regola dichiarativa. `kind` = "regex" (patterns/keywords sul testo normalizzato) oppure "detector". */
@Serializable
data class RuleDef(
    val id: String,
    val category: String,
    /** Peso in punti; negativo per i segnali che riducono il punteggio (domini ufficiali). */
    val weight: Int,
    /** Spiegazione IT mostrata all'utente. Placeholder per i rilevatori: {domain}, {brand}. */
    val explanation: String,
    val example: String = "",
    val kind: String = "regex",
    val patterns: List<String> = emptyList(),
    /** Parole/locuzioni letterali (minuscole), confrontate con confini di parola. */
    val keywords: List<String> = emptyList(),
    /** Regex: se trova corrispondenza nei 40 caratteri precedenti il match, il match è ignorato (negazioni). */
    val unless: String? = null,
    /** Nome del rilevatore in codice (solo kind = "detector"). */
    val detector: String? = null,
)

@Serializable
data class Brand(
    val name: String,
    val category: String,
    /** Token confrontati con le etichette del dominio (minuscoli, senza spazi). */
    val tokens: List<String>,
    /** Nomi con cui il marchio compare nel testo/mittente (minuscoli). */
    val aliases: List<String> = emptyList(),
    /** Domini registrabili ufficiali (eTLD+1). */
    val official: List<String>,
)

@Serializable
data class RuleLists(
    val brands: List<Brand> = emptyList(),
    val riskyTlds: List<String> = emptyList(),
    val shorteners: List<String> = emptyList(),
    /** Parole che nei domini inventati accompagnano il phishing (verifica, sicurezza, rimborso...). */
    val suspiciousDomainWords: List<String> = emptyList(),
    /** Siti noti non legati a un marchio sorvegliato (testate, catene): trattati come ufficiali. */
    val trustedDomains: List<String> = emptyList(),
    /** Categorie di marchi per cui «marchio nel testo ma link non ufficiale» è un segnale. */
    val mismatchCategories: List<String> = emptyList(),
)

@Serializable
data class Thresholds(
    /** Punteggio minimo per PROBABILE_TRUFFA. */
    val probable: Int,
    /** Punteggio minimo per SOSPETTO. */
    val suspect: Int,
)

@Serializable
data class RuleSet(
    val version: Long,
    /** Solo pacchetti firmati: ripete il keyId dell envelope (verificato da RulePackVerifier). */
    val keyId: String? = null,
    /** Solo pacchetti firmati: emissione e scadenza (ISO-8601 UTC) DENTRO il payload firmato. Vedi [isStale]. */
    val issuedAt: String? = null,
    val expiresAt: String? = null,
    val thresholds: Thresholds,
    val lists: RuleLists = RuleLists(),
    /** Azioni «Cosa fare ora» per categoria e chiavi speciali: probabile, sospetto, nessuno. */
    val actions: Map<String, List<String>> = emptyMap(),
    val rules: List<RuleDef>,
)

object RuleLoader {
    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_RULES = 500
    private const val MAX_PATTERN = 600

    /** Analizza e VALIDA un pacchetto regole (schema + lint regex SEC-06). Lancia IllegalArgumentException. */
    fun parse(text: String): RuleSet {
        val rs = try {
            json.decodeFromString<RuleSet>(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("JSON regole non valido: ${e.message}")
        }
        validate(rs)
        return rs
    }

    fun validate(rs: RuleSet) {
        require(rs.version > 0) { "version deve essere > 0" }
        require(rs.rules.size in 1..MAX_RULES) { "numero regole fuori limite" }
        require(rs.thresholds.suspect in 1 until rs.thresholds.probable) { "soglie incoerenti" }
        require(rs.rules.map { it.id }.toSet().size == rs.rules.size) { "id regola duplicati" }
        for (r in rs.rules) {
            require(r.weight in -100..100) { "${r.id}: peso fuori range" }
            require(r.explanation.isNotBlank()) { "${r.id}: spiegazione vuota" }
            when (r.kind) {
                "regex" -> {
                    require(r.patterns.isNotEmpty() || r.keywords.isNotEmpty()) { "${r.id}: nessun pattern" }
                    require(r.patterns.size + r.keywords.size <= 200) { "${r.id}: troppi pattern" }
                    (r.patterns + listOfNotNull(r.unless)).forEach { p ->
                        RegexLint.check(p)?.let { err -> throw IllegalArgumentException("${r.id}: $err") }
                        try {
                            java.util.regex.Pattern.compile(p)
                        } catch (e: Exception) {
                            throw IllegalArgumentException("${r.id}: regex non compilabile")
                        }
                    }
                }
                "detector" -> require(r.detector in Detectors.names) { "${r.id}: rilevatore sconosciuto" }
                else -> throw IllegalArgumentException("${r.id}: kind sconosciuto")
            }
        }
    }

    internal fun maxPatternLength() = MAX_PATTERN
}

/**
 * Linter SEC-06: rifiuta backreference, `.` con quantificatore illimitato, alternanze o quantificatori
 * illimitati dentro un gruppo ripetuto con quantificatore illimitato, e più di 2 quantificatori illimitati.
 */
object RegexLint {
    private class Frame(var unbounded: Boolean = false, var alternation: Boolean = false)

    fun check(p: String): String? {
        if (p.length > 600) return "pattern troppo lungo"
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame())
        var unboundedCount = 0
        var lastClosed: Frame? = null // gruppo appena chiuso (atomo precedente)
        var lastAtomDot = false
        var i = 0
        while (i < p.length) {
            val c = p[i]
            var quant: Char? = null // '*' '+' (illimitato) | 'B' bounded | 'O' ?
            when {
                c == '\\' -> {
                    val n = p.getOrNull(i + 1) ?: return "escape finale"
                    if (n in '1'..'9' || n == 'k') return "backreference vietata"
                    i += 2
                    lastClosed = null; lastAtomDot = false
                    continue
                }
                c == '[' -> {
                    var j = i + 1
                    if (p.getOrNull(j) == '^') j++
                    if (p.getOrNull(j) == ']') j++
                    while (j < p.length && p[j] != ']') { if (p[j] == '\\') j++; j++ }
                    if (j >= p.length) return "classe non chiusa"
                    i = j + 1
                    lastClosed = null; lastAtomDot = false
                    continue
                }
                c == '(' -> {
                    stack.addLast(Frame())
                    i++
                    if (p.getOrNull(i) == '?') {
                        // salta il tipo di gruppo (?: (?= (?! (?<= (?<! (?i)
                        i++
                        if (p.getOrNull(i) == '<' && p.getOrNull(i + 1).let { it == '=' || it == '!' }) i += 2
                        else if (p.getOrNull(i) == ':' || p.getOrNull(i) == '=' || p.getOrNull(i) == '!') i++
                        else if (p.getOrNull(i) == 'i' && p.getOrNull(i + 1) == ')') { i += 2; stack.removeLast() }
                        else return "costrutto di gruppo non ammesso"
                    }
                    lastClosed = null; lastAtomDot = false
                    continue
                }
                c == ')' -> {
                    if (stack.size < 2) return "parentesi non bilanciata"
                    val f = stack.removeLast()
                    lastClosed = f; lastAtomDot = false
                    i++
                    // un quantificatore illimitato nel gruppo sale al livello superiore
                    if (f.unbounded) stack.last().unbounded = true
                    continue
                }
                c == '|' -> { stack.last().alternation = true; lastClosed = null; lastAtomDot = false; i++; continue }
                c == '*' || c == '+' -> quant = c
                c == '?' -> { i++; continue } // opzionale o modificatore lazy: limitato
                c == '{' -> {
                    val end = p.indexOf('}', i)
                    if (end < 0) { i++; lastClosed = null; lastAtomDot = false; continue }
                    val body = p.substring(i + 1, end)
                    if (!body.all { it.isDigit() || it == ',' }) { i++; lastClosed = null; lastAtomDot = false; continue }
                    quant = if (body.endsWith(",")) '+' else 'B'
                    i = end
                }
                c == '.' -> { i++; lastClosed = null; lastAtomDot = true; continue }
            }
            if (quant != null) {
                if (quant == '+' || quant == '*') {
                    if (lastAtomDot) return "'.' con quantificatore illimitato"
                    val g = lastClosed
                    if (g != null && (g.unbounded || g.alternation)) return "quantificatore annidato/alternanza sotto quantificatore illimitato"
                    unboundedCount++
                    if (unboundedCount > 2) return "troppi quantificatori illimitati"
                    stack.last().unbounded = true
                } else if (quant == 'B') {
                    val g = lastClosed
                    if (g != null && g.unbounded) return "quantificatore illimitato annidato"
                }
                lastClosed = null; lastAtomDot = false
                i++
                continue
            }
            // carattere letterale
            lastClosed = null; lastAtomDot = false
            i++
        }
        if (stack.size != 1) return "parentesi non bilanciata"
        return null
    }
}

/** CharSequence con budget di tempo: lancia [BudgetExceeded] se l'elaborazione eccede la scadenza. */
internal class BudgetedChars(private val s: CharSequence, private val deadlineNanos: Long) : CharSequence {
    private var calls = 0
    override val length: Int get() = s.length
    override fun get(index: Int): Char {
        if ((++calls and 0x3FF) == 0 && System.nanoTime() > deadlineNanos) throw BudgetExceeded()
        return s[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        BudgetedChars(s.subSequence(startIndex, endIndex), deadlineNanos)
    override fun toString(): String = s.toString()
}

internal class BudgetExceeded : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}
