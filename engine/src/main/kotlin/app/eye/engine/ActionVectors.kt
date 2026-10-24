package app.eye.engine

import java.util.regex.Pattern

/**
 * Vettori d'azione: che cosa il messaggio chiede di FARE. Un messaggio che non chiede nulla (nessun link sospetto,
 * numero, contatto, pagamento, dato, risposta, installazione) non puo' fare danni da solo: non supera SOSPETTO e non
 * genera avvisi in background. Le frasi in negazione/avvertimento («non ti chiederemo mai la password») non contano.
 *
 * Due fonti, indipendenti dal pacchetto regole scaricato (che puo' essere piu' vecchio): le regole scattate
 * ([BY_RULE]) e un piccolo insieme di schemi di «richiesta» sul testo normalizzato. Puro Kotlin.
 */
internal object ActionVectors {
    /** Regole che, se scattate, sono per definizione una richiesta d'azione. */
    private val BY_RULE: Map<ActionVector, Set<String>> = mapOf(
        ActionVector.CREDENTIALS to setOf(
            "BAN-03", "BAN-04", "BAN-10", "BAN-11", "BAN-12", "WAP-01", "DOC-01", "DAT-01", "CRD-02", "MBX-01",
            "VEN-01", "CRY-01", "CRY-02", "ACC-04", "RIM-01", "SPI-01",
        ),
        ActionVector.PAYMENT to setOf("PAY-01", "PAY-02", "IBN-01", "ANT-01", "REC-01", "SEX-01", "VEN-02", "GEN-04", "PAC-02", "ROM-01", "INV-05", "SHP-02", "AFF-01", "LAV-03"),
        ActionVector.PHONE to setOf("BAN-07", "SUP-01", "ACC-03", "CALL-01", "PHN-01", "PHN-02"),
        ActionVector.REPLY to setOf("FAM-01", "FAM-02", "FAM-03", "INV-03"),
        ActionVector.EMAIL to setOf("MAI-01"),
        ActionVector.INSTALL to setOf("URL-09", "RDP-01"),
        // link anomali: sono link sospetti per definizione
        ActionVector.LINK to setOf("URL-01", "URL-02", "URL-05", "URL-06", "URL-10", "BLK-01"),
    )

    private val THREAT_CONTEXT = setOf("URG-02", "URG-03", "URG-04", "BAN-01", "BAN-11", "ACC-01", "ACC-04", "ACC-05", "SPI-01", "TEL-01", "MBX-01")

    private val NEGATION = Pattern.compile("\\b(?:non|mai|senza|never|do not|don'?t|nessuno|nessuna|evita|evitare|mai e poi mai|not)\\b")
    private val NOT_NEGATION = Pattern.compile("\\b(?:non esitare|non esitate|non perdere|non perdetevi|senza esitare|don'?t hesitate|non solo|non appena)\\b")

    /** La negazione conta solo se e' vicina (stessa frase, ultimi caratteri prima della richiesta): «non ti chiederemo mai» si', «credito non riscosso. ...» no. */
    private const val NEGATION_REACH = 32

    private fun p(s: String) = Pattern.compile(s)

    /** Verbi di consegna (dammi/inviami...) + dati, documenti, credenziali. */
    private val credentialsRequest = p(
        "\\b(?:inserisci|inserire|inserite|digita|digitare|invia|inviaci|inviami|mandaci|mandami|fornisci|fornire|comunica|comunicaci|comunicami|dacci|dammi|inoltra|inoltrami|rispondi con|" +
            "enter|provide|send|share|read out)\\b.{0,45}\\b" +
            "(?:password|pin|otp|cvv|codice|codici|credenziali|iban|carta|documento|documenti|dati|identita|code|credentials|card|data|login)\\b" +
            // verbi di verifica: solo con dati sensibili (non basta «aggiorna il tuo profilo/i tuoi dati nell'app»)
            "|\\b(?:conferma|confermare|verifica|verificare|aggiorna|aggiornare|reimposta|reimpostare|ripristina|sblocca|confirm|verify|update|reset)\\b.{0,45}\\b" +
            "(?:password|pin|otp|cvv|codice|codici|credenziali|iban|carta|code|credentials|card)\\b",
    )
    private val paymentRequest = p(
        "\\b(?:fammi|farmi|mandami|inviami|invia|manda|mandare|versa|versare|versami|ricaricami|ricarica|ricaricare|paga|pagami|pagare|pagarla|pagarlo|pagaci|" +
            "effettua|effettuare|esegui|eseguire|trasferisci|anticipa|deposita|depositare|pay|send|transfer|wire|deposit)\\b.{0,50}\\b" +
            "(?:bonifico|bonifici|soldi|denaro|euro|eur|ricarica|postepay|gift ?cards?|bitcoin|usdt|btc|money|cash|iban|commissione|commissioni|tassa|tasse|spese|fattura|importo|caparra|anticipo)\\b" +
            "|\\b(?:mi serve|mi servono|mi servirebbe|ho bisogno di|mi occorre|mi occorrono|mi mancano)\\b.{0,40}(?:\\d[\\d.,]*\\s?(?:euro|€|eur)|\\bsoldi\\b|\\bdenaro\\b|\\bun prestito\\b|\\bun bonifico\\b|\\buna ricarica\\b)",
    )
    private val replyRequest = p(
        "\\b(?:scrivimi|scrivici|scrivetemi|contattami|contattaci|aggiungimi|aggiungici|rispondimi|rispondi|rispondete|chiamami|chiamaci|messaggiami|reply|text me|message me|contact me|add me|dm me)\\b" +
            "|\\b(?:scrivi|scrivere|contatta|contattare|aggiungi|unisciti|entra|join|add|message|text|contact|scrivete|contattate)\\b.{0,30}\\b(?:su|via|on|al|a|tramite)\\s+(?:whatsapp|telegram|signal|wechat|viber)\\b" +
            "|\\b(?:scrivi|scrivete|rispondi|rispondete|invia|inviaci)\\s+[\"']?(?:ok|si|sì|yes|info|via|stop|ciao|start|1)[\"']?(?![\\p{L}\\p{N}])" +
            "|(?<![\\p{L}\\p{N}._])@[a-z0-9_]{5,32}\\b",
    )
    /** Nomi di pagamento: «serve un piccolo versamento di 29 euro», «contributo di 2 euro». */
    private val paymentNoun = p(
        "\\b(?:serve|servono|necessario|necessaria|richiesto|richiesta|obbligatorio|obbligatoria|previsto|prevista|dovuto|dovuta)\\b.{0,40}\\b(?:versamento|pagamento|bonifico|ricarica|anticipo|deposito|commissione|contributo|cauzione|caparra)\\b" +
            "|\\b(?:versamento|pagamento|contributo|deposito|anticipo|caparra|cauzione|commissione)\\s+(?:di|da)\\s+(?:€\\s?)?\\d",
    )
    /** Nomi di richiesta di dati: «prevede l'inserimento dei codici di accesso». */
    private val credentialsNoun = p("\\b(?:inserimento|invio|comunicazione|conferma|verifica|aggiornamento)\\s+(?:dei|delle|del|della|dello|di)\\s+(?:tuoi |suoi |vostri )?(?:codici|codice|credenziali|dati|password|pin|carta|documento|documenti)\\b")
    /** Invito a premere/riprogrammare/pagare ora, tipico dei pulsanti delle email senza URL visibile: il link vero e' stato tolto dalla notifica. */
    private val callToAction = p(
        "\\b(?:clicca|cliccare|tocca|premi|apri il link|segui il link|vai al link|accedi al link|riprogramma|riprogrammare|rinnova ora|rinnova il|paga ora|paga subito|verifica ora|conferma ora|sblocca ora|riattiva|aggiorna ora|accedi ora)\\b" +
            "|\\[\\s?(?:paga|rinnova|verifica|conferma|accedi|aggiorna|sblocca|riattiva|riprogramma|recupera|ripristina)[^\\]]{0,40}\\]",
    )
    private val installRequest = p("\\b(?:scarica|scaricare|installa|installare|download|install)\\b.{0,40}\\b(?:apk|anydesk|teamviewer|rustdesk|quicksupport|aggiornamento di sicurezza|programma di)\\b")
    private val emailAddress = Pattern.compile("[\\p{L}\\p{N}._%+-]{1,64}@[\\p{L}\\p{N}-]{1,63}(?:\\.[\\p{L}\\p{N}-]{1,63}){1,5}")

    /** Un indirizzo email e' un vettore solo se il testo invita a scrivere/inviare/contattare (non se e' il destinatario della notifica). */
    private val emailContact = p("\\b(?:scrivi|scriva|scrivere|scrivete|invia|inviare|inviaci|inviate|inoltra|inoltrare|contatta|contattare|contattaci|contattate|mandaci|manda|rispondi|write|send|contact|email|e-mail|mail|pec)\\b[^@\\n]{0,40}$")

    /** «Al momento non devi fare nulla», «nessuna azione richiesta»: dichiarazione esplicita di assenza di azioni. */
    private val noActionStated = p(
        "\\bnon (?:devi|dovete|deve|serve|occorre|bisogna) (?:fare|intraprendere|compiere) (?:nulla|niente|alcuna azione)\\b" +
            "|\\bnon (?:e'|è) (?:necessario|richiesto) (?:fare|intraprendere|compiere) (?:nulla|niente|alcuna azione)\\b" +
            "|\\bnon (?:e'|è) (?:necessaria|richiesta) alcuna azione\\b" +
            "|\\bnessuna azione (?:e' |è )?(?:richiesta|necessaria|obbligatoria)\\b" +
            "|\\bno action (?:is )?(?:required|needed)\\b|\\byou don'?t need to do anything\\b",
    )

    private fun emailRequested(raw: String): Boolean {
        val low = raw.lowercase()
        val m = emailAddress.matcher(low)
        while (m.find()) {
            val before = low.substring(maxOf(0, m.start() - 55), m.start())
            if (emailContact.matcher(before).find() && !NEGATION.matcher(before.takeLast(NEGATION_REACH)).find()) return true
        }
        return false
    }

    /** Vero se esiste almeno un riscontro NON preceduto (entro 45 caratteri) da una negazione/avvertimento. */
    private fun requested(pat: Pattern, text: String): Boolean {
        val m = pat.matcher(text)
        while (m.find()) {
            var before = text.substring(maxOf(0, m.start() - 60), m.start())
            before = before.substring(before.indexOfLast { it == '.' || it == '!' || it == '?' || it == ';' || it == '\n' } + 1).takeLast(NEGATION_REACH)
            before = NOT_NEGATION.matcher(before).replaceAll(" ")
            if (!NEGATION.matcher(before).find()) return true
        }
        return false
    }

    /** Link che fa da vettore pieno: non ufficiale ne' molto diffuso, oppure ufficiale ma ingannevole (userinfo, punycode, invito a gruppi). */
    private fun suspiciousLink(c: Ctx, u: ParsedUrl) =
        !c.isKnown(u.registrable) || u.registrable in c.shorteners || u.registrable in RuleEngine.GROUP_INVITE_DOMAINS ||
            u.hasUserInfo || u.isPunycode || u.isIp

    fun detect(c: Ctx, firedIds: Set<String>): Set<ActionVector> {
        val out = LinkedHashSet<ActionVector>()
        for ((v, ids) in BY_RULE) if (firedIds.any { it in ids }) out.add(v)
        // «verifica/aggiorna i dati» e' una richiesta di credenziali solo dentro una minaccia (blocco, accesso anomalo...), non in una newsletter di fedelta'
        if ("BAN-02" in firedIds && firedIds.any { it in THREAT_CONTEXT }) out.add(ActionVector.CREDENTIALS)
        // un negozio dal dominio nudo + prezzi stracciati (SHP-01) e' lo schema dei falsi negozi: li' il dominio conta
        val bareIsWeak = "SHP-01" !in firedIds
        val fullLink = c.anomalies > 0 || c.urls.any { suspiciousLink(c, it) && !(bareIsWeak && it.raw in c.weakBare && !Detectors.technicallySuspicious(c, it, riskyTldCounts = false)) }
        if (fullLink) out.add(ActionVector.LINK)
        else if (c.urls.any { it.raw !in c.weakBare || c.isKnown(it.registrable) }) out.add(ActionVector.LINK_OFFICIAL)
        else if (c.urls.isNotEmpty()) out.add(ActionVector.LINK_BARE)
        if (c.phones.isNotEmpty()) out.add(ActionVector.PHONE)
        if (c.urls.isEmpty() && requested(callToAction, c.text)) out.add(ActionVector.LINK) // il link vero e' nel pulsante, tolto dalla notifica
        if (c.ibans.isNotEmpty() || requested(paymentRequest, c.text) || requested(paymentNoun, c.text)) out.add(ActionVector.PAYMENT)
        if (requested(credentialsRequest, c.text) || requested(credentialsNoun, c.text)) out.add(ActionVector.CREDENTIALS)
        if (requested(replyRequest, c.text)) out.add(ActionVector.REPLY)
        if (requested(installRequest, c.text)) out.add(ActionVector.INSTALL)
        if (emailRequested(c.rawText)) out.add(ActionVector.EMAIL)
        // dichiarazione esplicita «non devi fare nulla»: i soli link (deboli) non bastano a fare di una notifica una richiesta
        if (out.all { it == ActionVector.LINK_OFFICIAL || it == ActionVector.LINK_BARE } && noActionStated.matcher(c.text).find()) out.clear()
        return out
    }

    /** Regole di solo contesto commerciale/forma: da sole non portano mai a PROBABILE_TRUFFA. */
    fun isContextOnly(ruleId: String) =
        ruleId == "URG-01" || ruleId == "URG-05" || ruleId == "GEN-01" ||
            ruleId.startsWith("COM-") || ruleId.startsWith("FOR-") || ruleId.startsWith("OFF-") || ruleId.startsWith("AI-")
}
