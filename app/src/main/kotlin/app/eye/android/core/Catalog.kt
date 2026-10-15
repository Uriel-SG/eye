package app.eye.android.core

enum class ScamCategory { SMS, BANCA, PACCHI, PEDAGGI, ENTI, QR, WHATSAPP, MALWARE, INVESTIMENTI, ACQUISTI, ALTRO }

data class ReportLink(val label: String, val url: String)

data class ScamCard(
    val id: String,
    val category: ScamCategory,
    val name: String,
    val summary: String,
    /** «In corso»: truffa del momento. */
    val ongoing: Boolean,
    val recognize: List<String>,
    /** Testo anonimizzato: nessun marchio reale, nessun numero reale. */
    val example: String,
    val actions: List<String>,
    val reportLinks: List<ReportLink>,
    /** Solo campagne dal feed firmato: ultimo aggiornamento (yyyy-MM-dd). */
    val updatedAt: String? = null,
)

/** Catalogo offline (F1-T13). Solo nomi descrittivi, niente loghi o marchi. */
object Catalog {
    const val UPDATED = "11/10/2026"

    private val polizia = ReportLink("Polizia Postale: denuncia online", "https://www.commissariatodips.it")
    private val cert = ReportLink("CERT-AgID: segnalazioni", "https://cert-agid.gov.it")

    private val commonActions = listOf(
        "Non cliccare sul link e non rispondere.",
        "Non dare codici, PIN, password o dati della carta.",
        "Cerca il sito o il numero ufficiale per conto tuo.",
    )

    val cards: List<ScamCard> = listOf(
        ScamCard(
            "pacco-sms", ScamCategory.PACCHI, "Finto SMS del corriere",
            "Dice che il pacco è fermo e chiede una piccola somma.", true,
            listOf("Dice che il pacco non è stato consegnato.", "Chiede di pagare pochi euro per la riconsegna.", "Il link non è il sito del corriere."),
            "Il tuo pacco è in giacenza. Conferma l'indirizzo entro 24 ore: [link nascosto]",
            commonActions + "Se hai pagato, avvisa subito la tua banca.", listOf(polizia, cert),
        ),
        ScamCard(
            "banca-conto", ScamCategory.BANCA, "Finto avviso della banca",
            "Ti dice che il conto è bloccato o che c'è un accesso strano.", true,
            listOf("Messaggio urgente su conto o carta bloccati.", "Ti chiede di accedere da un link.", "Chiede codici ricevuti per SMS."),
            "Attività sospetta sul tuo conto. Accedi subito per evitare il blocco: [link nascosto]",
            listOf("Non cliccare sul link.", "Non comunicare mai codici a nessuno, nemmeno a un finto operatore.", "Chiama la banca al numero ufficiale sulla tua carta.", "Se hai inserito dati, chiedi subito il blocco."),
            listOf(polizia, cert),
        ),
        ScamCard(
            "pedaggio", ScamCategory.PEDAGGI, "Finto pedaggio non pagato",
            "Dice che hai un pedaggio da saldare per evitare una multa.", true,
            listOf("Importo piccolo e scadenza ravvicinata.", "Minaccia sanzioni o aumento dell'importo.", "Il sito di pagamento non è quello della società autostradale."),
            "Pedaggio non saldato. Paga entro oggi per evitare maggiorazioni: [link nascosto]",
            commonActions, listOf(polizia, cert),
        ),
        ScamCard(
            "figlio-nuovo-numero", ScamCategory.SMS, "«Ciao mamma, ho cambiato numero»",
            "Finto figlio o figlia che chiede soldi con urgenza.", false,
            listOf("Messaggio da un numero sconosciuto.", "Dice di aver perso il telefono.", "Chiede subito un bonifico."),
            "Ciao mamma, ho cambiato numero. Puoi farmi un bonifico urgente? Ti spiego dopo.",
            listOf("Non fare bonifici.", "Chiama il tuo familiare al numero che già conosci.", "Chiedi una domanda a cui solo lui sa rispondere."),
            listOf(polizia),
        ),
        ScamCard(
            "rimborso-fisco", ScamCategory.ENTI, "Finto rimborso delle tasse",
            "Ti promette un rimborso se inserisci i dati della carta.", false,
            listOf("Promette soldi da ricevere.", "Chiede dati della carta per accreditare.", "Il link non è un sito ufficiale dello Stato."),
            "Hai diritto a un rimborso. Inserisci i dati della carta per riceverlo: [link nascosto]",
            commonActions, listOf(polizia, cert),
        ),
        ScamCard(
            "previdenza", ScamCategory.ENTI, "Finto messaggio dell'ente previdenziale",
            "Dice che devi aggiornare i dati per non perdere un beneficio.", false,
            listOf("Minaccia di perdere un beneficio.", "Chiede di accedere da un link nel messaggio.", "Usa un tono molto urgente."),
            "Aggiorna i tuoi dati entro 48 ore per non perdere il beneficio: [link nascosto]",
            commonActions, listOf(polizia, cert),
        ),
        ScamCard(
            "qr-parcheggio", ScamCategory.QR, "QR falso su parchimetri e colonnine",
            "Un adesivo con QR coperto sopra quello vero porta a un sito falso.", false,
            listOf("L'adesivo è incollato sopra un altro.", "Il link non è quello del gestore.", "Chiede i dati della carta per pagare."),
            "Paga qui la sosta: [link nascosto]",
            listOf("Non pagare con quel QR.", "Usa l'app o la cassa ufficiale.", "Segnala l'adesivo al gestore."),
            listOf(polizia, cert),
        ),
        ScamCard(
            "qr-ristorante", ScamCategory.QR, "QR falso sul menù",
            "Un QR sul tavolo ti porta a un sito che copia quello vero.", false,
            listOf("Il QR sembra attaccato dopo.", "Il sito chiede dati personali o di pagamento.", "L'indirizzo è strano o troppo lungo."),
            "Scansiona e ordina: [link nascosto]",
            listOf("Chiedi il menù al personale.", "Non inserire dati della carta da un QR.", "Controlla il dominio prima di aprirlo."),
            listOf(polizia),
        ),
        ScamCard(
            "codice-sms", ScamCategory.SMS, "Richiesta di un codice ricevuto per SMS",
            "Ti chiedono il codice appena ricevuto, dicendo che è stato un errore.", false,
            listOf("Ti scrive qualcuno che non conosci.", "Dice di aver sbagliato numero.", "Ti chiede di inviargli il codice."),
            "Scusa, ti ho mandato per sbaglio un codice. Me lo rimandi?",
            listOf("Non inviare il codice.", "Non rispondere.", "Blocca il numero e segnalalo."),
            listOf(polizia),
        ),
        ScamCard(
            "assistenza-finta", ScamCategory.BANCA, "Finto operatore antifrode al telefono",
            "Ti chiamano dicendo di essere la banca e ti fanno spostare i soldi.", false,
            listOf("Ti mette fretta al telefono.", "Ti chiede di spostare soldi su un conto «sicuro».", "Chiede codici o di installare un'app."),
            "Sono dell'ufficio antifrode: devi spostare subito i soldi su un conto protetto.",
            listOf("Chiudi la chiamata.", "Richiama la banca dal numero ufficiale.", "Non installare app su richiesta."),
            listOf(polizia),
        ),
        ScamCard(
            "premio", ScamCategory.SMS, "Finto premio o vincita",
            "Dice che hai vinto un premio e devi pagare una piccola spesa.", false,
            listOf("Non hai partecipato a nessun concorso.", "Chiede una spesa o i dati della carta.", "Il premio è troppo bello per essere vero."),
            "Hai vinto un buono! Ritiralo ora: [link nascosto]",
            commonActions, listOf(cert),
        ),
        ScamCard(
            "multa-finta", ScamCategory.ENTI, "Finta notifica di multa",
            "Ti dice che hai una multa da pagare subito con uno sconto.", false,
            listOf("Chiede di pagare con un link.", "Offre uno sconto se paghi in fretta.", "Non indica un verbale che riconosci."),
            "Hai una multa non pagata. Paga entro oggi con lo sconto: [link nascosto]",
            commonActions, listOf(polizia, cert),
        ),
    )

    fun byId(id: String, list: List<ScamCard> = cards): ScamCard? = list.firstOrNull { it.id == id }
    fun ongoing(limit: Int = 3, list: List<ScamCard> = cards): List<ScamCard> = list.filter { it.ongoing }.take(limit)

    fun search(query: String, category: ScamCategory?, list: List<ScamCard> = cards): List<ScamCard> {
        val q = query.trim().lowercase()
        return list.filter { c ->
            (category == null || c.category == category) &&
                (q.isEmpty() || c.name.lowercase().contains(q) || c.summary.lowercase().contains(q))
        }.sortedByDescending { it.ongoing }
    }

    /** Campagna del feed firmato -> scheda. */
    fun fromCampaign(c: app.eye.engine.Campaign): ScamCard = ScamCard(
        id = c.id, category = categoryOf(c.category), name = c.title, summary = c.summary, ongoing = c.status == "in_corso",
        recognize = c.signals, example = c.example, actions = c.actions,
        reportLinks = listOf(ReportLink(c.source.name, c.source.url)), updatedAt = c.updatedAt,
    )

    fun categoryOf(feedCategory: String): ScamCategory = when (feedCategory) {
        "smishing" -> ScamCategory.SMS
        "phishing" -> ScamCategory.BANCA
        "whatsapp" -> ScamCategory.WHATSAPP
        "malware" -> ScamCategory.MALWARE
        "investimenti" -> ScamCategory.INVESTIMENTI
        "acquisti" -> ScamCategory.ACQUISTI
        else -> ScamCategory.ALTRO
    }

    /**
     * Unisce le schede statiche e le campagne del feed: a parità di id vince la campagna (più recente);
     * le schede statiche non vengono mai perse. Le campagne in corso più recenti vengono prima.
     */
    fun merge(campaigns: List<app.eye.engine.Campaign>, base: List<ScamCard> = cards): List<ScamCard> {
        val fromFeed = campaigns.sortedByDescending { it.updatedAt }.map(::fromCampaign)
        val ids = fromFeed.map { it.id }.toSet()
        return fromFeed + base.filter { it.id !in ids }
    }
}
