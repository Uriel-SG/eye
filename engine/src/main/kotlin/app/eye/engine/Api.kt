package app.eye.engine

/**
 * Contratto pubblico del motore (congelato in F1-T1). :app dipende solo da questi tipi.
 * Modifiche incompatibili richiedono accordo tra DEV-A e DEV-B.
 */
interface Analyzer {
    fun analyze(input: Input): Verdict
}

enum class Source { SHARE, PASTE, QR, OCR }

data class Input(
    val text: String,
    val source: Source,
    /** Mittente dichiarato, se noto (es. "Poste" in un SMS). */
    val declaredSender: String? = null,
    /** Package dell'app che ha generato la notifica; null per condivisioni, incolla e OCR. */
    val origin: String? = null,
    /**
     * Link accorciato (come in [ShortLinks.candidates]) -> destinazione risolta dal servizio di accorciamento.
     * Assente = non risolto. La destinazione si analizza al posto del link accorciato.
     */
    val resolvedLinks: Map<String, String> = emptyMap(),
)

/** Mai «Sicuro»: l'assenza di segnali non è una garanzia. */
enum class Level { PROBABILE_TRUFFA, SOSPETTO, NESSUN_SEGNALE }

data class Reason(
    val ruleId: String,
    /** Spiegazione in italiano semplice, mostrata all'utente. */
    val text: String,
    val weight: Int,
)

data class Extracted(
    val urls: List<String> = emptyList(),
    /** Dominio registrabile di ogni URL (es. "poste-verifica.com"). */
    val domains: List<String> = emptyList(),
    val phones: List<String> = emptyList(),
    val ibans: List<String> = emptyList(),
    val amounts: List<String> = emptyList(),
)

/**
 * Livello di avviso in background, calcolato dal motore (vedi [AlertPolicy.tierFor]). Il livello mostrato ([Level]) non cambia.
 * NONE = nessun segnale; QUIET = verdetto visibile solo se l'utente apre il messaggio, nessuna notifica;
 * ALERT = notifica di avviso (c'e' almeno un'azione da compiere sospetta).
 */
enum class AlertTier { NONE, QUIET, ALERT }

/** Azioni che il messaggio chiede di compiere (o a cui induce): senza almeno una, non c'e' nulla da cui proteggere. */
enum class ActionVector {
    /** Link verso un dominio non ufficiale ne' molto diffuso (sconosciuto, lookalike, TLD a rischio, IP, punycode, shortener non risolto, invito a gruppi). */
    LINK,
    /** Solo link verso domini ufficiali o molto diffusi: vettore debole, da solo non genera avvisi. */
    LINK_OFFICIAL,
    /** Dominio «nudo» scritto nel testo di una notifica (senza schema, www, percorso, e senza link reali verso di esso): di solito e' il nome di un negozio o di un sito citato. Debole. */
    LINK_BARE,
    PHONE, EMAIL, PAYMENT, CREDENTIALS,
    /** Rispondere o spostarsi su un altro canale/numero. */
    REPLY,
    /** Installare app o programmi fuori dagli store. */
    INSTALL,
}

data class Verdict(
    val level: Level,
    val score: Int,
    val reasons: List<Reason>,
    /** Azioni consigliate «Cosa fare ora», in italiano. */
    val actions: List<String>,
    val extracted: Extracted,
    /** Categoria prevalente (es. "pacco", "banca"), se individuata; collega al catalogo. */
    val category: String?,
    val rulesVersion: Long,
    /** Interno: vettori d'azione trovati nel messaggio. */
    val vectors: Set<ActionVector> = emptySet(),
    /** Interno: usato da [AlertPolicy.shouldAlert]. Non salvato in cronologia (l'avviso si decide all'analisi). */
    val alertTier: AlertTier = AlertTier.NONE,
)
