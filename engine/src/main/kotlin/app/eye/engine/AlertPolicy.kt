package app.eye.engine

/**
 * Politica degli avvisi in background (notifiche, origin != null). Criterio: avvisi rari e seri, mai uno per ogni
 * messaggio che arriva; ma nessuna truffa importante persa.
 *  - Un messaggio senza azioni da compiere (nessun «vettore d'azione», vedi [ActionVector]) non avvisa mai.
 *  - PROBABILE_TRUFFA (con vettore): avviso.
 *  - SOSPETTO: avviso solo con un segnale «forte/tecnico» ([STRONG_RULE_IDS]) e un vettore d'azione sospetto;
 *    altrimenti il verdetto resta calcolato (visibile se l'utente apre il messaggio) ma non suona.
 *  - NESSUN_SEGNALE: mai.
 * Il modello AI (AI-01) non alza mai il verdetto per le notifiche (vedi RuleEngine): non e' affidabile sui loro testi.
 */
object AlertPolicy {
    /** Segnali tecnici sui link: TLD a rischio, abbreviatori, parole sospette nel dominio. Gli altri link tecnici sono in [REQUESTS_AND_HARD]. */
    private val LINK_SIGNALS = setOf("URL-03", "URL-04", "URL-07")

    /**
     * Link anomali (lookalike URL-01, sottodominio ingannevole URL-02, omografi URL-05, IP URL-06, APK URL-09,
     * link non risolvibile URL-10, mittente≠dominio MIS-01, testo≠destinazione MIS-02), richieste di codici/PIN/
     * credenziali/pagamento (BAN-03/04/10/11/12, WAP-01, CRD-02, PAY-01/02, IBN-01, ...) e le altre regole «gravi»:
     * stesso elenco di `ai.hardRuleIds` di rules.json (test di coerenza in AlertPolicyTest).
     */
    private val REQUESTS_AND_HARD = setOf(
        "URL-01", "URL-02", "URL-05", "URL-06", "URL-09", "URL-10", "MIS-01", "MIS-02",
        "BAN-03", "BAN-04", "BAN-10", "BAN-11", "BAN-12", "WAP-01", "DOC-01", "DAT-01", "VEN-01", "RDP-01",
        "PAY-01", "PAY-02", "IBN-01", "PHN-01", "ANT-01", "REC-01", "CRY-01", "CRY-02", "SEX-01", "FAM-01",
        "SUP-01", "INC-01", "CRD-02", "MBX-01", "BLK-01",
    )

    /**
     * Schemi di truffa «di contenuto» (lavoro facile, investimenti, premi, multe/cartelle, finto familiare, falsi
     * avvisi di banca/account/pacco): regole che nei corpora legittimi (legit, regression, blind, notif_legit) non
     * scattano mai. Restano FUORI le regole di contesto, che in chat e promo vere compaiono spesso: urgenza (URG-*),
     * pressione/valore (COM-*), forma (FOR-*), generiche (GEN-*, RIS-*), shopping (SHP-01), bollette/pedaggi
     * (ENE-01, PED-01), pacco generico (PAC-01), fattura/dati (FAT-01, ACC-05), sconti OFF-*.
     */
    private val SCAM_PATTERNS = setOf(
        "LAV-01", "LAV-02", "INV-01", "INV-02", "INV-03", "INV-04", "PRE-01", "PRE-02", "PRE-03", "MUL-01", "MUL-02",
        "FIS-01", "FIS-03", "FAM-02", "FAM-03", "CALL-01", "MAI-01", "BAN-01", "ACC-01", "ACC-03", "ACC-04", "PAC-02", "PAC-03", "PAC-05", "RIM-01", "TEL-01",
        "ROM-01", "INV-05", "SHP-02", "AFF-01", "LAV-03",
    )

    val STRONG_RULE_IDS: Set<String> = LINK_SIGNALS + REQUESTS_AND_HARD + SCAM_PATTERNS

    /**
     * Livello di avviso (vedi [AlertTier]). Calcolato dal motore a fine analisi:
     *  - NESSUN_SEGNALE: NONE.
     *  - PROBABILE_TRUFFA: ALERT (il motore garantisce che ci sia un vettore d'azione; senza, il livello e' limitato a SOSPETTO).
     *  - SOSPETTO: ALERT solo con un segnale forte ([STRONG_RULE_IDS]) E un vettore d'azione pieno (non basta un link
     *    verso un sito ufficiale/molto diffuso); altrimenti QUIET. Analisi incompleta (INC-01): ALERT, per prudenza.
     */
    fun tierFor(level: Level, ruleIds: Collection<String>, vectors: Set<ActionVector>, trustedRequest: Boolean = false): AlertTier = when {
        level == Level.NESSUN_SEGNALE -> AlertTier.NONE
        // richiesta di soldi tra persone che si conoscono, senza inganni: vettore si', segnale forte no
        trustedRequest && ruleIds.none { it != "PAY-01" && it in STRONG_RULE_IDS } &&
            vectors.all { it == ActionVector.PAYMENT || it == ActionVector.REPLY } -> AlertTier.QUIET
        else -> tierByLevel(level, ruleIds, vectors)
    }

    /**
     * Richiesta di soldi da un contatto con NOME (non un numero ne' un'etichetta di servizio), senza elementi di
     * inganno: nessun IBAN, link, codice, cripto/gift card, cambio numero (FAM-*) ne' urgenza estrema (URG-02/03/04).
     */
    fun isTrustedRequest(sender: String?, hasIban: Boolean, rawLower: String, ruleIds: Collection<String>): Boolean {
        if (sender == null || hasIban) return false
        val words = sender.trim().split(Regex("\\s+"))
        if (words.size !in 1..2 || words.any { w -> w.length !in 2..20 || !w[0].isUpperCase() || !w.all { it.isLetter() || it == '\'' } }) return false
        if (words.any { it.lowercase() in GENERIC_SENDER_WORDS }) return false
        if (UNTRUSTED_REQUEST.containsMatchIn(rawLower)) return false
        return ruleIds.none { it.startsWith("FAM-") || it == "URG-02" || it == "URG-03" || it == "URG-04" }
    }

    private val GENERIC_SENDER_WORDS = setOf(
        "servizio", "clienti", "cliente", "supporto", "account", "area", "riservata", "notifica", "sicurezza", "info", "informa", "servizi",
        "ufficio", "posta", "sconosciuto", "alert", "avviso", "assistenza", "banca", "team", "support", "customer", "service", "security",
        "giacenza", "offerta", "opportunita", "opportunità", "esclusiva", "amazon", "poste", "inps", "spid", "enel", "nexi", "bnl", "ing", "brt", "gls",
    )
    private val UNTRUSTED_REQUEST = Regex("gift ?card|carta regalo|buoni regalo|paysafecard|cripto|bitcoin|usdt|btc|codici? |whatsapp|telegram|https?:|www\\.|\\.(?:com|net|org|top|shop|info|xyz|online|site|click)\\b")

    private fun tierByLevel(level: Level, ruleIds: Collection<String>, vectors: Set<ActionVector>): AlertTier = when (level) {
        Level.NESSUN_SEGNALE -> AlertTier.NONE
        Level.PROBABILE_TRUFFA -> if (vectors.any { it != ActionVector.LINK_BARE }) AlertTier.ALERT else AlertTier.QUIET
        Level.SOSPETTO -> when {
            "INC-01" in ruleIds -> AlertTier.ALERT
            ruleIds.any { it in STRONG_RULE_IDS } && vectors.any { it != ActionVector.LINK_OFFICIAL && it != ActionVector.LINK_BARE } -> AlertTier.ALERT
            else -> AlertTier.QUIET
        }
    }

    /** Avviso in background: decide il livello di avviso gia' calcolato dal motore ([Verdict.alertTier]). */
    fun shouldAlert(v: Verdict): Boolean = v.level != Level.NESSUN_SEGNALE && v.alertTier == AlertTier.ALERT
}
