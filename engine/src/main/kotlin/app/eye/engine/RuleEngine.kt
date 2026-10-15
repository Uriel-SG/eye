package app.eye.engine

import java.util.regex.Pattern

/**
 * Motore a regole: estrazione → normalizzazione → regole dati + rilevatori in codice → punteggio.
 * Puro Kotlin/JDK. Thread-safe dopo la costruzione (Pattern è immutabile).
 */
class RuleEngine(private val rules: RuleSet, private val budgetMillis: Long = 800) : Analyzer {

    private class Compiled(val def: RuleDef, val patterns: List<Pattern>, val unless: Pattern?)

    private val compiled: List<Compiled> = rules.rules.sortedWith(
        // detector prima delle regex (economici e discriminanti); lookalike e mittente in testa.
        compareBy<RuleDef> { if (it.kind != "detector") 2 else if (it.id == "URL-01" || it.id == "MIS-01") 0 else 1 },
    ).map { r ->
        if (r.kind == "detector") {
            Compiled(r, emptyList(), null)
        } else {
            val ps = ArrayList<Pattern>()
            r.patterns.forEach { ps.add(Pattern.compile(it)) }
            if (r.keywords.isNotEmpty()) {
                val alt = r.keywords.joinToString("|") { Pattern.quote(it.lowercase()) }
                ps.add(Pattern.compile("(?<![\\p{L}\\p{N}])(?:$alt)(?![\\p{L}\\p{N}])"))
            }
            Compiled(r, ps, r.unless?.let { Pattern.compile(it) })
        }
    }

    val rulesVersion: Long get() = rules.version
    val ruleCount: Int get() = rules.rules.size

    override fun analyze(input: Input): Verdict {
        val deadline = System.nanoTime() + budgetMillis * 1_000_000
        val raw = Text.collapseRepeats(input.text.take(MAX_INPUT_CHARS))
        val sender = input.declaredSender?.take(120)

        val ibanHits = Extractors.ibansWithRanges(raw)
        val scan = Extractors.scan(Text.stripInvisible(raw))
        val urls = scan.urls
        val phones = Extractors.phones(raw, ibanHits.map { it.second })
        val amounts = Extractors.amounts(raw)
        val extracted = Extracted(
            urls = urls.map { it.raw },
            domains = urls.map { it.registrable }.distinct(),
            phones = phones,
            ibans = ibanHits.map { it.first },
            amounts = amounts,
        )

        val text = Text.normalizeForRules(raw)
        val ctx = Ctx(raw, text, sender, urls, phones, ibanHits.map { it.first }, amounts, rules.lists, scan.anomalies)
        val budgeted = BudgetedChars(text, deadline)

        class Fired(val def: RuleDef, val reasonText: String, val category: String)
        val fired = ArrayList<Fired>()

        var incomplete = false // budget scaduto: fail-safe (mai NESSUN_SEGNALE, mai sconti)
        for (c in compiled) {
            if (System.nanoTime() > deadline) { incomplete = true; break }
            val def = c.def
            if (def.kind == "detector") {
                val hit = try {
                    Detectors.run(def.detector!!, ctx)
                } catch (_: BudgetExceeded) {
                    incomplete = true
                    null
                } ?: continue
                fired.add(Fired(def, fill(def.explanation, hit.params), hit.category ?: def.category))
            } else {
                val matched = try {
                    matches(c, text, budgeted)
                } catch (_: BudgetExceeded) {
                    incomplete = true
                    false
                }
                if (matched) fired.add(Fired(def, def.explanation, def.category))
            }
        }

        if (incomplete) {
            fired.removeAll { it.def.weight < 0 } // nessuno sconto OFF-* su analisi incompleta
            val inc = rules.rules.firstOrNull { it.id == "INC-01" } ?: RuleDef(
                id = "INC-01", category = "generico", weight = 0, kind = "detector", detector = "incomplete_analysis",
                explanation = "Il messaggio è molto lungo o insolito e non sono riuscito ad analizzarlo tutto: trattalo con prudenza.",
            )
            fired.add(Fired(inc, inc.explanation, inc.category))
        }
        val rawScore = fired.sumOf { it.def.weight }.coerceIn(0, 100)
        val score = if (incomplete) maxOf(rawScore, rules.thresholds.suspect) else rawScore
        val level = when {
            score >= rules.thresholds.probable -> Level.PROBABILE_TRUFFA
            score >= rules.thresholds.suspect -> Level.SOSPETTO
            else -> Level.NESSUN_SEGNALE
        }
        val reasons = fired.sortedByDescending { it.def.weight }
            .map { Reason(it.def.id, it.reasonText, it.def.weight) }

        val category = fired.filter { it.def.weight > 0 && it.category !in NEUTRAL_CATEGORIES }
            .groupBy { it.category }
            .maxByOrNull { (_, v) -> v.sumOf { it.def.weight } }?.key
            .takeIf { level != Level.NESSUN_SEGNALE }

        return Verdict(level, score, reasons, actionsFor(level, category), extracted, category, rules.version)
    }

    private fun matches(c: Compiled, text: String, budgeted: CharSequence): Boolean {
        for (p in c.patterns) {
            val m = p.matcher(budgeted)
            while (m.find()) {
                val u = c.unless
                if (u == null) return true
                val from = maxOf(0, m.start() - 40)
                if (!u.matcher(text.substring(from, m.start())).find()) return true
            }
        }
        return false
    }

    private fun fill(t: String, params: Map<String, String>): String {
        var s = t
        for ((k, v) in params) s = s.replace("{$k}", v)
        return s
    }

    private fun actionsFor(level: Level, category: String?): List<String> {
        val key = when (level) {
            Level.PROBABILE_TRUFFA -> "probabile"
            Level.SOSPETTO -> "sospetto"
            Level.NESSUN_SEGNALE -> "nessuno"
        }
        val out = LinkedHashSet<String>()
        out.addAll(rules.actions[key].orEmpty())
        if (level != Level.NESSUN_SEGNALE && category != null) out.addAll(rules.actions[category].orEmpty())
        return out.toList()
    }

    companion object {
        private val NEUTRAL_CATEGORIES = setOf("generico", "link", "forma")

        /** Pacchetto regole incluso nelle risorse (fallback). */
        fun default(): RuleEngine = RuleEngine(loadBundled())

        fun fromJson(json: String): RuleEngine = RuleEngine(RuleLoader.parse(json))

        fun loadBundled(): RuleSet {
            val s = RuleEngine::class.java.getResourceAsStream("/rules/rules.json")
                ?: throw IllegalStateException("rules.json mancante nelle risorse")
            return RuleLoader.parse(s.use { it.readBytes().toString(Charsets.UTF_8) })
        }
    }
}
