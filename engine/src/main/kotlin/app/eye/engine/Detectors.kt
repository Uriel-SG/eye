package app.eye.engine

/** Contesto di analisi condiviso dai rilevatori in codice. */
internal class Ctx(
    val rawText: String,
    /** Testo normalizzato per le regole. */
    val text: String,
    val sender: String?,
    val urls: List<ParsedUrl>,
    val phones: List<String>,
    val ibans: List<String>,
    val amounts: List<String>,
    val lists: RuleLists,
    /** Link anomali: frammenti simili a domini non estratti o URL http(s) non validi. >0 => nessuno sconto "ufficiale". */
    val anomalies: Int = 0,
    /** Elenco locale di siti malevoli noti (BLK-01). */
    val reputation: DomainReputation = DomainReputation.NONE,
    /** Link accorciati risolti: (shortener, destinazione). La destinazione e' gia' in [urls] al posto dello shortener. */
    val expansions: List<Pair<ParsedUrl, ParsedUrl>> = emptyList(),
    /** Notifiche: URL (forma [ParsedUrl.raw]) che sono solo un dominio nudo nel testo, non un link reale. Vedi [ActionVector.LINK_BARE]. */
    val weakBare: Set<String> = emptySet(),
    /** Elenco locale di numeri usati per truffe (NUM-01). */
    val numbers: NumberReputation = NumberReputation.NONE,
) {
    /** Testo con budget di tempo per i rilevatori che usano regex (lancia BudgetExceeded). */
    var scanText: CharSequence = text
    private val cl get() = lists.compiled
    val popular: Set<String> get() = cl.popular

    /** Dominio ufficiale o di un sito molto diffuso (per MIS-01/02 e combinazioni di prudenza). */
    fun isKnown(d: String) = isOfficial(d) || d in popular

    private fun any(ps: List<java.util.regex.Pattern>) = ps.any { it.matcher(scanText).find() }
    val hasPressure: Boolean by lazy { any(cl.pressure) }
    val hasValue: Boolean by lazy { any(cl.value) }
    val hasDataRequest: Boolean by lazy { any(cl.data) }

    val riskyTlds = lists.riskyTlds.toHashSet()
    val shorteners = lists.shorteners.toHashSet()
    val officialAll: Set<String> = (lists.brands.flatMap { it.official } + lists.trustedDomains).toHashSet()

    /** Marchi con scheletri dei token precomputati. */
    val brandTokens: List<Pair<Brand, List<String>>> =
        lists.brands.map { b -> b to b.tokens.map { Text.skeleton(it) } }

    fun isOfficial(d: String) = d in officialAll || isInstitutional(d)
    fun isOfficialFor(d: String, b: Brand) = d in b.official || isInstitutional(d)

    /**
     * Domini istituzionali italiani: *.gov.it e comune./regione./provincia.<x>.it. Il dominio registrabile
     * inizia con "comune." solo se il suffisso pubblico e' territoriale (PSL), quindi non registrabile da terzi.
     */
    fun isInstitutional(d: String) = d == "gov.it" || d.endsWith(".gov.it") ||
        (d.endsWith(".it") && d.count { it == '.' } >= 2 && INSTITUTIONAL_PREFIXES.any { d.startsWith(it) })

    /**
     * Il messaggio SI PRESENTA come il marchio (intestazione «Google:», «il tuo account Google», «team X»,
     * «da parte di X», firma), non lo cita soltanto. Il mittente dichiarato e' gestito da MIS-01.
     */
    fun presentsAs(b: Brand): Boolean {
        val names = (b.aliases + b.name.lowercase()).distinct()
        val l = "[\\p{L}\\p{N}]"
        for (n in names) {
            val q = java.util.regex.Pattern.quote(n)
            val ps = presentationPatterns.computeIfAbsent(n) {
                listOf(
                    "^[^\\p{L}\\p{N}]{0,15}$q(?:\\s{0,2}[:\\-|,]|\\s(?:ti |vi |la |le )?(?:informa|avvisa|comunica|ricorda|team|support|security|alert|notice))",
                    "(?<!$l)(?:il tuo|la tua|i tuoi|le tue|il suo|la sua|i suoi|le sue|your|my)\\s(?:\\p{L}{1,15}\\s){0,2}$q(?!$l)",
                    "(?<!$l)(?:account|conto|carta|profilo|abbonamento|membership|password|accesso|servizio)\\s$q(?!$l)",
                    "(?<!$l)$q\\s(?:team|support|security|customer|assistenza|supporto|servizio clienti|sicurezza|staff)(?!$l)",
                    "(?<!$l)(?:da parte di|a nome di|from|on behalf of|team|supporto|assistenza|servizio clienti|ufficio)\\s$q(?!$l)",
                    "(?:saluti|regards|sincerely|cordialmente)[,\\s]{1,3}(?:il |the )?(?:team |staff )?$q(?!$l)",
                ).map { java.util.regex.Pattern.compile(it) }
            }
            if (ps.any { it.matcher(text).find() }) return true
        }
        return false
    }

    /** Marchi citati nel testo (o nel mittente) con confini di parola. */
    fun brandsIn(s: String): List<Brand> = lists.brands.filter { b ->
        (b.aliases + b.name.lowercase()).any { containsWord(s, it) }
    }

    companion object {
        private val presentationPatterns = java.util.concurrent.ConcurrentHashMap<String, List<java.util.regex.Pattern>>()
        private val INSTITUTIONAL_PREFIXES = listOf("comune.", "regione.", "provincia.", "citta-metropolitana.")

        fun containsWord(s: String, w: String): Boolean {
            if (w.isEmpty()) return false
            var from = 0
            while (true) {
                val i = s.indexOf(w, from)
                if (i < 0) return false
                val before = if (i == 0) ' ' else s[i - 1]
                val after = if (i + w.length >= s.length) ' ' else s[i + w.length]
                if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
                from = i + 1
            }
        }
    }
}

/** Esito di un rilevatore: parametri per i placeholder e categoria opzionale. */
internal class Hit(val params: Map<String, String> = emptyMap(), val category: String? = null)

/** Rilevatori in codice. Ogni funzione restituisce il primo riscontro o null. */
internal object Detectors {
    private val impl: Map<String, (Ctx) -> Hit?> = mapOf(
        "lookalike" to ::lookalike,
        "deceptive_subdomain" to ::deceptiveSubdomain,
        "risky_tld" to ::riskyTld,
        "shortener" to ::shortener,
        "homograph" to ::homograph,
        "ip_host" to ::ipHost,
        "userinfo" to ::userInfo,
        "suspicious_domain_words" to ::suspiciousWords,
        "apk_link" to ::apkLink,
        "sender_mismatch" to ::senderMismatch,
        "text_link_mismatch" to ::textLinkMismatch,
        "premium_number" to ::premiumNumber,
        "foreign_number" to ::foreignNumber,
        "iban_payment" to ::ibanPayment,
        "iban_foreign" to ::ibanForeign,
        "shouting" to ::shouting,
        "mixed_language" to ::mixedLanguage,
        "pressure_value" to ::pressureValue,
        "pressure_unknown_link" to ::pressureUnknownLink,
        "official_domains" to ::officialDomains,
        "incomplete_analysis" to { _ -> null }, // gestito dal motore (budget scaduto)
        "unresolved_link" to { c -> if (c.anomalies > 0) Hit() else null },
        "shortener_official_context" to ::shortenerOfficialContext,
        "known_malicious_domain" to ::knownMaliciousDomain,
        "known_spam_number" to ::knownSpamNumber,
        "premium_number_intl" to ::premiumNumberIntl,
        "shortener_resolved" to ::shortenerResolved,
        "shortener_resolved_unknown" to ::shortenerResolvedUnknown,
    )

    val names: Set<String> get() = impl.keys

    fun run(name: String, ctx: Ctx): Hit? = impl[name]?.invoke(ctx)

    // ---- URL ----
    /** BLK-01: host (o suo dominio padre) nell'elenco pubblico di siti di truffa/malware. Mai per domini ufficiali o molto diffusi. */
    private fun knownMaliciousDomain(c: Ctx): Hit? {
        for (u in c.urls) {
            if (c.isKnown(u.registrable)) continue
            if (c.reputation.isKnownMalicious(u.host, u.registrable)) return Hit(mapOf("domain" to u.host))
        }
        return null
    }

    /** URL-11 (informativa, peso 0): il link accorciato e' stato risolto. */
    private fun shortenerResolved(c: Ctx): Hit? =
        c.expansions.firstOrNull()?.let { (s, d) -> Hit(mapOf("domain" to s.registrable, "dest" to d.host)) }

    /** URL-12: la destinazione risolta non e' un sito noto e pulito (peso ridotto, non e' un segnale forte). */
    private fun shortenerResolvedUnknown(c: Ctx): Hit? =
        c.expansions.firstOrNull { (_, d) ->
            !c.isKnown(d.registrable) || d.registrable in RuleEngine.GROUP_INVITE_DOMAINS || d.hasUserInfo || d.isPunycode
        }?.let { (s, d) -> Hit(mapOf("domain" to s.registrable, "dest" to d.host)) }

    private fun suspectUrls(c: Ctx) = c.urls.filter { !c.isOfficial(it.registrable) }

    private fun labelMatches(labelSk: String, tokSk: String): Boolean {
        if (tokSk.length < 3) return false
        if (labelSk == tokSk) return true
        val parts = labelSk.split('-', '_', '.')
        if (parts.size > 1 && tokSk in parts) return true
        if (tokSk.length >= 5 && labelSk.contains(tokSk)) return true
        if (tokSk.length >= 6) {
            if (Text.editDistance(labelSk, tokSk, 1) <= 1) return true
            if (parts.size > 1 && parts.any { it.length >= 6 && Text.editDistance(it, tokSk, 1) <= 1 }) return true
        }
        return false
    }

    private fun lookalikeBrand(c: Ctx, u: ParsedUrl): Brand? {
        if (c.isOfficial(u.registrable) || u.isIp || u.registrable in c.shorteners) return null
        val sk = Text.skeleton(u.mainLabelUnicode)
        for ((b, toks) in c.brandTokens) {
            if (c.isOfficialFor(u.registrable, b)) continue
            if (toks.any { labelMatches(sk, it) }) return b
        }
        return null
    }

    private fun lookalike(c: Ctx): Hit? {
        for (u in suspectUrls(c)) {
            val b = lookalikeBrand(c, u) ?: continue
            return Hit(mapOf("domain" to u.registrable, "brand" to b.name), b.category)
        }
        return null
    }

    private fun deceptiveBrand(c: Ctx, u: ParsedUrl): Brand? {
        if (c.isOfficial(u.registrable) || u.isIp) return null
        val subs = u.subdomains.map { Text.skeleton(Text.toUnicode(it)) }
        val path = u.path.lowercase()
        for ((b, toks) in c.brandTokens) {
            if (c.isOfficialFor(u.registrable, b)) continue
            val inSub = subs.isNotEmpty() && toks.any { t -> t.length >= 3 && subs.any { it == t || (t.length >= 5 && it.contains(t)) } }
            val inPath = b.official.any { path.contains(it) }
            if (inSub || inPath) return b
        }
        return null
    }

    private fun deceptiveSubdomain(c: Ctx): Hit? {
        for (u in suspectUrls(c)) {
            val b = deceptiveBrand(c, u) ?: continue
            return Hit(mapOf("domain" to u.registrable, "brand" to b.name), b.category)
        }
        return null
    }

    private fun hasRiskyTld(c: Ctx, u: ParsedUrl) =
        !u.isIp && PublicSuffix.suffixOf(u.registrable).substringAfterLast('.') in c.riskyTlds

    private fun hasSuspiciousWords(c: Ctx, u: ParsedUrl): Boolean {
        if (u.isIp || u.registrable in c.shorteners) return false
        val label = u.mainLabelUnicode
        val found = c.lists.suspiciousDomainWords.count { label.contains(it) }
        val hyphens = label.count { it == '-' }
        return found >= 2 || (found >= 1 && hyphens >= 1) || (hyphens >= 2 && PublicSuffix.suffixOf(u.registrable).substringAfterLast('.') in c.riskyTlds)
    }

    /**
     * Indizi tecnici di sito ingannevole sul dominio stesso (lookalike, sottodominio ingannevole, TLD a rischio, parole
     * sospette, punycode/omografo, IP, abbreviatore, elenco siti malevoli): un dominio cosi' non e' mai «debole».
     */
    fun technicallySuspicious(c: Ctx, u: ParsedUrl, riskyTldCounts: Boolean = true): Boolean =
        u.isIp || u.isPunycode || u.hasUserInfo || Text.hasNonAsciiLetters(u.hostUnicode) || u.registrable in c.shorteners ||
            c.reputation.isKnownMalicious(u.host, u.registrable) ||
            lookalikeBrand(c, u) != null || deceptiveBrand(c, u) != null || (riskyTldCounts && hasRiskyTld(c, u)) || hasSuspiciousWords(c, u)

    private fun riskyTld(c: Ctx): Hit? =
        suspectUrls(c).firstOrNull { hasRiskyTld(c, it) }?.let { Hit(mapOf("domain" to it.registrable)) }

    private fun shortener(c: Ctx): Hit? =
        c.urls.firstOrNull { it.registrable in c.shorteners && !it.isGoogleShareShortLink }?.let { Hit(mapOf("domain" to it.registrable)) }

    private fun homograph(c: Ctx): Hit? =
        c.urls.firstOrNull { it.isPunycode || Text.hasNonAsciiLetters(it.hostUnicode) }
            ?.let { Hit(mapOf("domain" to it.registrable)) }

    private fun ipHost(c: Ctx): Hit? =
        c.urls.firstOrNull { it.isIp }?.let { Hit(mapOf("domain" to it.host)) }

    private fun userInfo(c: Ctx): Hit? =
        c.urls.firstOrNull { it.hasUserInfo }?.let { Hit(mapOf("domain" to it.registrable)) }

    private fun suspiciousWords(c: Ctx): Hit? =
        suspectUrls(c).firstOrNull { hasSuspiciousWords(c, it) }?.let { Hit(mapOf("domain" to it.registrable)) }

    private fun apkLink(c: Ctx): Hit? =
        c.urls.firstOrNull { it.path.substringBefore('?').substringBefore('#').lowercase().endsWith(".apk") }
            ?.let { Hit(mapOf("domain" to it.registrable)) }

    // ---- Coerenza mittente / testo ↔ link ----
    private fun senderMismatch(c: Ctx): Hit? {
        val sender = c.sender?.let { Text.normalizeForRules(it) } ?: return null
        if (c.urls.isEmpty()) return null
        val brands = c.brandsIn(sender)
        // siti molto diffusi (reddit, github, workos...) non sono mai incoerenza di mittente
        val urls = c.urls.filter { it.registrable !in c.popular }
        if (urls.isEmpty()) return null
        for (b in brands) {
            if (urls.none { c.isOfficialFor(it.registrable, b) }) {
                val d = urls.first { !c.isOfficialFor(it.registrable, b) }.registrable
                return Hit(mapOf("domain" to d, "brand" to b.name), b.category)
            }
        }
        return null
    }

    private fun textLinkMismatch(c: Ctx): Hit? {
        if (c.urls.isEmpty()) return null
        val urls = c.urls.filter { it.registrable !in c.popular }
        if (urls.isEmpty()) return null
        // solo se il messaggio si presenta COME il marchio: una semplice citazione non basta
        val brands = c.brandsIn(c.text).filter { it.category in c.lists.mismatchCategories && c.presentsAs(it) }
        if (brands.isEmpty()) return null
        // Se un qualunque link è ufficiale per un marchio citato, nessuna incoerenza.
        if (c.anomalies == 0 && urls.any { u -> brands.any { c.isOfficialFor(u.registrable, it) } }) return null
        val b = brands.first()
        val d = urls.first().registrable
        return Hit(mapOf("domain" to d, "brand" to b.name), b.category)
    }

    // ---- Numeri / IBAN ----
    private fun premiumNumber(c: Ctx): Hit? =
        c.phones.firstOrNull { p ->
            !p.startsWith("+") && (p.startsWith("89") && p.length in 6..10 || p.startsWith("144") || p.startsWith("166"))
        }?.let { Hit() }

    /** NUM-01: numero nell'elenco pubblico dei numeri usati per truffe. Mai numeri verdi, brevi o a sovrapprezzo (non sono nell'elenco). */
    private fun knownSpamNumber(c: Ctx): Hit? {
        for (p in c.phones) {
            val n = PhoneNumbers.parse(p) ?: continue
            if (n.isListable && c.numbers.isKnownSpam(n.e164)) return Hit(mapOf("number" to n.e164))
        }
        return null
    }

    /** NUM-02: numero a sovrapprezzo scritto con prefisso (+39 89x, 0039 144...) o internazionale non tracciabile (+979, +88x). Le forme nazionali sono di PHN-01. */
    private fun premiumNumberIntl(c: Ctx): Hit? =
        c.phones.firstOrNull { p -> p.startsWith("+") && PhoneNumbers.parse(p)?.kind == NumberKind.PREMIUM }?.let { Hit() }

    private fun foreignNumber(c: Ctx): Hit? {
        val foreign = c.phones.any { it.startsWith("+") && !it.startsWith("+39") }
        if (!foreign) return null
        val claims = c.brandsIn(c.text).isNotEmpty() || c.sender != null
        return if (claims) Hit() else null
    }

    private val payWords = Regex("bonifico|versa|accredit|pagament|ricaric|intestat|coordinate|paga(?:re|ci|mi)?\\b")

    private fun ibanPayment(c: Ctx): Hit? =
        if (c.ibans.isNotEmpty() && payWords.containsMatchIn(c.text)) Hit() else null

    private fun ibanForeign(c: Ctx): Hit? =
        if (c.ibans.any { !it.startsWith("IT") } && payWords.containsMatchIn(c.text)) Hit() else null

    // ---- Forma ----
    private fun shouting(c: Ctx): Hit? {
        val letters = c.rawText.count { it.isLetter() }
        if (letters < 40) return null
        val upper = c.rawText.count { it.isUpperCase() }
        return if (upper * 100 / letters >= 60) Hit() else null
    }

    private val enWords = setOf("your", "the", "you", "to", "and", "is", "has", "been", "will", "please", "now", "or", "this", "with", "for", "are")
    private val itWords = setOf("il", "la", "di", "che", "per", "non", "un", "una", "sono", "con", "del", "della", "tuo", "tua", "gentile", "cliente", "hai", "stato")
    private val wordRe = Regex("[a-z]+")

    /** Mescola italiano e inglese in modo marcato: tipico dei testi truffa tradotti. */
    private fun mixedLanguage(c: Ctx): Hit? {
        var en = 0
        var it = 0
        for (m in wordRe.findAll(c.text)) {
            val w = m.value
            if (w in enWords) en++ else if (w in itWords) it++
        }
        return if (en >= 4 && it >= 3) Hit() else null
    }

    // ---- Prudenza: pressione + soldi/dati senza un sito noto ----
    private fun unknownLinkPresent(c: Ctx) = c.urls.any { !c.isKnown(it.registrable) } || c.anomalies > 0

    private fun pressureValue(c: Ctx): Hit? {
        if (c.urls.isNotEmpty() && !unknownLinkPresent(c)) return null
        return if (c.hasPressure && (c.hasValue || c.hasDataRequest)) Hit() else null
    }

    private fun pressureUnknownLink(c: Ctx): Hit? =
        if (unknownLinkPresent(c) && c.hasPressure) Hit(mapOf("domain" to (c.urls.firstOrNull { !c.isKnown(it.registrable) }?.registrable ?: "sconosciuto"))) else null

    // ---- Segnale negativo ----
    /** Link accorciato ma il testo cita un marchio e il suo dominio ufficiale compare tra gli altri link. */
    private fun shortenerOfficialContext(c: Ctx): Hit? {
        if (c.anomalies > 0 || c.urls.none { it.registrable in c.shorteners }) return null
        // ogni altro link deve essere ufficiale: un dominio non ufficiale presente => mai sconto
        if (c.urls.any { it.registrable !in c.shorteners && !c.isOfficial(it.registrable) }) return null
        for (b in c.brandsIn(c.text)) {
            val u = c.urls.firstOrNull { it.registrable !in c.shorteners && !it.hasUserInfo && !it.isPunycode && it.registrable in b.official }
            if (u != null) return Hit(mapOf("domain" to u.registrable, "brand" to b.name))
        }
        return null
    }

    private fun officialDomains(c: Ctx): Hit? {
        if (c.urls.isEmpty() || c.anomalies > 0) return null
        val cited = c.brandsIn(c.text)
        if (cited.isNotEmpty() && c.urls.none { u -> cited.any { c.isOfficialFor(u.registrable, it) } }) return null
        if (c.urls.all { c.isOfficial(it.registrable) && it.registrable !in c.shorteners && !it.hasUserInfo && !it.isPunycode && it.registrable !in RuleEngine.GROUP_INVITE_DOMAINS })
            return Hit(mapOf("domain" to c.urls.first().registrable))
        return null
    }
}
