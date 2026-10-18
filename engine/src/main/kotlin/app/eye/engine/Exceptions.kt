package app.eye.engine

/** Impronta di un messaggio: il «modello» senza i dati variabili (numeri, date, orari, importi, codici, contatti, link). */
data class MessageFingerprint(
    val origin: String?,
    val sender: String?,
    val shingles: Set<String>,
    val domains: Set<String>,
    /** Anomalie di link (frammenti simili a domini non estratti, URL non validi). */
    val linkAnomalies: Int = 0,
    /** Anteprima oscurata (cifre sostituite da •), max 80 caratteri. */
    val preview: String = "",
) {
    companion object {
        private val email = Regex("[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+\\.\\p{L}{2,}")
        private const val MONTHS = "gennaio|febbraio|marzo|aprile|maggio|giugno|luglio|agosto|settembre|ottobre|novembre|dicembre|gen|feb|mar|apr|mag|giu|lug|ago|set|ott|nov|dic"
        private val date = Regex("\\d{1,4}[/.\\-]\\d{1,2}[/.\\-]\\d{1,4}|\\d{1,2}\\s+(?:$MONTHS)\\.?(?:\\s+\\d{2,4})?")
        private val weekday = Regex("(?<![\\p{L}])(?:lunedi|lunedì|martedi|martedì|mercoledi|mercoledì|giovedi|giovedì|venerdi|venerdì|sabato|domenica)(?![\\p{L}])")
        private val time = Regex("\\d{1,2}[:.]\\d{2}(?:[:.]\\d{2})?")
        private val amount = Regex("(?:€|eur|euro)\\s?\\d[\\d.,]*|\\d[\\d.,]*\\s?(?:€|eur(?:o)?(?![\\p{L}]))")
        private val code = Regex("(?<![\\p{L}\\p{N}<])(?=[\\p{L}\\p{N}-]*\\d)(?=[\\p{L}\\p{N}-]*\\p{L})[\\p{L}\\p{N}-]{4,}(?![\\p{L}\\p{N}>])")
        private val number = Regex("\\+?\\d[\\d\\s().-]*\\d|\\d")
        private val token = Regex("<[a-z]+>|[\\p{L}']+")
        private val digit = Regex("\\d")
        private val spaces = Regex("\\s+")
        private val digits = Regex("\\d+")

        fun of(input: Input): MessageFingerprint {
            val raw = Text.collapseRepeats(input.text.take(MAX_INPUT_CHARS))
            val scan = Extractors.scan(Text.stripInvisible(raw))
            var t = Text.normalizeForRules(raw)
            for (u in scan.urls.map { it.raw.lowercase() }.distinct().sortedByDescending { it.length }) {
                if (u.isNotEmpty()) t = t.replace(u, " <u> ")
            }
            t = email.replace(t, " <e> ")
            t = date.replace(t, " <d> ")
            t = weekday.replace(t, " <g> ")
            t = time.replace(t, " <t> ")
            t = amount.replace(t, " <i> ")
            t = code.replace(t, " <c> ")
            t = number.replace(t, " <n> ")
            val words = token.findAll(t).map { it.value }.toList()
            val shingles = when {
                words.isEmpty() -> emptySet()
                words.size < 3 -> setOf(words.joinToString(" "))
                else -> words.windowed(3).map { it.joinToString(" ") }.toSet()
            }
            return MessageFingerprint(
                origin = input.origin?.takeIf { it.isNotBlank() },
                sender = input.declaredSender?.let(::normSender)?.takeIf { it.isNotEmpty() },
                shingles = shingles,
                domains = scan.urls.map { it.registrable }.toSet(),
                linkAnomalies = scan.anomalies,
                preview = spaces.replace(digit.replace(raw, "•"), " ").trim().take(80),
            )
        }

        private fun normSender(s: String): String = digits.replace(Text.normalizeForRules(s.take(120)), "#").trim()
    }
}

enum class ExceptionKind { TRUSTED, SUSPICIOUS }

data class UserException(
    val id: String,
    val kind: ExceptionKind,
    val origin: String?,
    val sender: String?,
    val shingles: Set<String>,
    val domains: Set<String>,
    val preview: String,
    val createdAt: Long,
    /** ID delle regole scattate quando l'eccezione è stata creata. */
    val ruleIds: Set<String> = emptySet(),
)

object UserExceptions {
    const val TRUSTED_ID = "USR-01"
    const val SUSPICIOUS_ID = "USR-02"
    const val TRUSTED_TEXT = "Hai indicato come affidabili messaggi simili a questo (eccezione impostata da te)."
    const val SUSPICIOUS_TEXT = "Hai indicato come sospetti messaggi simili a questo."
    const val MIN_SIMILARITY = 0.85

    val DEFAULT_TRUSTED_ACTIONS = listOf("Nessuna azione necessaria: hai indicato tu che messaggi come questo sono affidabili.")
    val DEFAULT_SUSPICIOUS_ACTIONS = listOf("Non fidarti: prima di rispondere o aprire link, verifica il mittente con un altro canale.")

    fun create(kind: ExceptionKind, verdict: Verdict, fp: MessageFingerprint, id: String, now: Long) = UserException(
        id, kind, fp.origin, fp.sender, fp.shingles, fp.domains, fp.preview, now,
        verdict.reasons.map { it.ruleId }.toSet(),
    )

    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        return inter.toDouble() / (a.size + b.size - inter)
    }

    /** Stessa app, stesso mittente (se presenti nell'eccezione) e testo simile. */
    fun similar(e: UserException, fp: MessageFingerprint): Boolean =
        (e.origin == null || e.origin == fp.origin) &&
            (e.sender == null || e.sender == fp.sender) &&
            jaccard(e.shingles, fp.shingles) >= MIN_SIMILARITY

    /** Eccezione TRUSTED applicabile: niente link nuovi o anomali, niente nuove regole «gravi». */
    fun trustedMatches(e: UserException, v: Verdict, fp: MessageFingerprint, hardRuleIds: Set<String>): Boolean =
        e.kind == ExceptionKind.TRUSTED && similar(e, fp) && fp.linkAnomalies == 0 &&
            e.domains.containsAll(fp.domains) &&
            v.reasons.none { it.ruleId in hardRuleIds && it.ruleId !in e.ruleIds }

    /** SOSPETTO dell'utente prevale sempre (la prudenza vince); mai abbassa. */
    fun apply(
        v: Verdict, fp: MessageFingerprint, exceptions: List<UserException>,
        hardRuleIds: Set<String> = AiConfig.DEFAULT_HARD_RULES.toSet(),
    ): Verdict {
        if (exceptions.any { it.kind == ExceptionKind.SUSPICIOUS && similar(it, fp) }) {
            if (v.level != Level.NESSUN_SEGNALE) return v
            return v.copy(
                level = Level.SOSPETTO, score = maxOf(v.score, 1),
                reasons = listOf(Reason(SUSPICIOUS_ID, SUSPICIOUS_TEXT, 0)) + v.reasons, actions = DEFAULT_SUSPICIOUS_ACTIONS,
            )
        }
        if (v.level != Level.NESSUN_SEGNALE && exceptions.any { trustedMatches(it, v, fp, hardRuleIds) }) {
            return v.copy(
                level = Level.NESSUN_SEGNALE, score = 0,
                reasons = listOf(Reason(TRUSTED_ID, TRUSTED_TEXT, 0)) + v.reasons, actions = DEFAULT_TRUSTED_ACTIONS, category = null,
            )
        }
        return v
    }
}

/** Applica le eccezioni dell'utente DOPO regole e AI. */
class ExceptionAwareAnalyzer(
    private val delegate: Analyzer,
    private val exceptions: () -> List<UserException>,
    private val hardRuleIds: Set<String> = AiConfig.DEFAULT_HARD_RULES.toSet(),
) : Analyzer {
    override fun analyze(input: Input): Verdict {
        val v = delegate.analyze(input)
        val list = exceptions()
        if (list.isEmpty()) return v
        return UserExceptions.apply(v, MessageFingerprint.of(input), list, hardRuleIds)
    }
}
