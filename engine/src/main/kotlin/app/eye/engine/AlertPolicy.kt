package app.eye.engine

/**
 * Politica degli avvisi in background (notifiche, origin != null). Criterio: avvisi rari e seri, mai uno per ogni
 * messaggio che arriva; ma nessuna truffa importante persa.
 *  - PROBABILE_TRUFFA: sempre avviso.
 *  - SOSPETTO: avviso solo se almeno uno dei motivi e' un segnale «forte/tecnico» ([STRONG_RULE_IDS]); altrimenti il
 *    verdetto resta calcolato (visibile se l'utente apre il messaggio) ma non suona.
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
        "FIS-01", "FIS-03", "FAM-02", "BAN-01", "ACC-01", "ACC-03", "ACC-04", "PAC-02", "PAC-03", "PAC-05", "RIM-01", "TEL-01",
    )

    val STRONG_RULE_IDS: Set<String> = LINK_SIGNALS + REQUESTS_AND_HARD + SCAM_PATTERNS

    fun shouldAlert(v: Verdict): Boolean = when (v.level) {
        Level.PROBABILE_TRUFFA -> true
        Level.SOSPETTO -> v.reasons.any { it.ruleId in STRONG_RULE_IDS }
        Level.NESSUN_SEGNALE -> false
    }
}
