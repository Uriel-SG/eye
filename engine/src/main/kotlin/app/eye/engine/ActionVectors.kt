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
            "VEN-01", "CRY-01", "CRY-02", "RIM-01", "SPI-01",
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
    private val NOT_NEGATION = Pattern.compile("\\b(?:per evitare|per evitarlo|per evitarne|al fine di evitare|non esitare|non esitate|non perdere|non perdetevi|senza esitare|don'?t hesitate|non solo|non appena|non sei (?:stato |stata )?(?:tu|lei)|non (?:sono|e'|è) stato (?:io|lei|lui)|non l'ha (?:effettuat|richiest|fatt)\\w*|non l'hai (?:effettuat|richiest|fatt)\\w*|non riconosc\\w+|if (?:this|that) wasn'?t you|wasn'?t you)\\b")

    /** La negazione conta solo se e' vicina (stessa frase, ultimi caratteri prima della richiesta): «non ti chiederemo mai» si', «credito non riscosso. ...» no. */
    private const val NEGATION_REACH = 32

    private fun p(s: String) = Pattern.compile(s)

    /** Dati sensibili veri e propri: chiederli a un lettore e' gia' una richiesta, anche senza un canale esplicito (la chat stessa e' il canale). */
    private const val SENSITIVE = "password|pin|otp|cvv|codice|codici|credenziali|iban|carta|code|credentials|card|passport|passaporto|ssn"
    /** Nomi generici («dati», «documento»): contano solo con un canale (link, numero, email, «rispondi», «clicca»). */
    private const val GENERIC = "documento|documenti|dati|identita|identity|data|login"

    /** Verbi di consegna (dammi/inviami...) + dati, documenti, credenziali. */
    private val credentialsRequest = p(
        "\\b(?:inserisci|inserire|inserite|digita|digitare|invia|inviaci|inviami|mandaci|mandami|fornisci|fornire|comunica|comunicaci|comunicami|dacci|dammi|inoltra|inoltrami|rispondi con|" +
            "enter|provide|send|share|read out)\\b.{0,60}\\b(?:$SENSITIVE)\\b" +
            // verbi di verifica: solo con dati sensibili (non basta «aggiorna il tuo profilo/i tuoi dati nell'app»)
            "|\\b(?:conferma|confermi|confermare|verifica|verifichi|verificare|aggiorna|aggiorni|aggiornare|reimposta|reimposti|reimpostare|ripristina|sblocca|sblocchi|confirm|verify|update|reset)\\b.{0,45}\\b(?:$SENSITIVE)\\b",
    )
    /** Richiesta in prima persona di un operatore al telefono/chat: «mi servirebbe il codice che riceverà via SMS». */
    private val credentialsAsk = p("\\b(?:mi serve|mi servirebbe|mi servono|ci serve|ci servirebbe|ci servono|ho bisogno del|ho bisogno di|mi occorre|mi occorrono|mi dica|mi detta|mi legga|mi comunichi|mi fornisca|mi indichi|mi dia|mi invii)\\b.{0,45}\\b(?:$SENSITIVE)\\b")
    /** Imperativo/congiuntivo di cortesia + «tuo/suo/your» + dato sensibile: e' rivolta al lettore (escluso «i tuoi dati»/«profilo»: newsletter). */
    private val credentialsPossessive = p(
        "\\b(?:confirm|verify|validate|provide|enter|send|share|conferma|confermi|confermate|verifica|verifichi|verificate|convalida|convalidi|fornisci|fornisca|inserisci|inserisca|invia|invii)\\b.{0,20}\\b(?:your|tuo|tuoi|tua|tue|suo|suoi|sua|sue|vostro|vostri|vostra|vostre|l'identita|la sua identita|la tua identita)\\b.{0,30}\\b(?:identity|identita|information|details|address|payment|card|password|email|conto|carta|documento|documenti|passport|passaporto)\\b",
    )
    private val credentialsGenericRequest = p(
        "\\b(?:inserisci|inserire|inserite|digita|digitare|invia|inviaci|inviami|mandaci|mandami|fornisci|fornire|comunica|comunicaci|comunicami|dacci|dammi|inoltra|inoltrami|rispondi con|" +
            "enter|provide|send|share|read out)\\b.{0,45}\\b(?:$GENERIC)\\b",
    )
    /** Canale per rispondere/agire: «rispondi», «clicca», «chiama», «accedi»... (la sola parola «chiamata» o «link» non basta). */
    private val channelWords = p(
        "\\b(?:rispondi|rispondere|rispondendo|rispondete|clicca|cliccare|cliccando|clicchi|clicchiamo|premi|premere|tocca|toccare|chiama|chiamaci|chiamami|chiamate|chiami|chiamare|telefona|telefonaci|" +
            "contattaci|contattami|scrivici|scrivimi|accedi|accedere|collegati|vai su|apri il link|segui il link|invia a|inviare a|mandare a|manda a|reply|click|call)\\b",
    )
    /** Chiamata annunciata: «ti chiamera' un nostro operatore», «verrai contattato»: vettore telefono (il truffatore vuole che tu risponda/parli). */
    private val announcedCall = p(
        "\\b(?:ti|vi|la|le) ?(?:chiamer[aà]|chiameremo|contatter[aà]|contatteremo|telefoner[aà]|telefoneremo)(?![\\p{L}])" +
            "|\\b(?:verrai|verra|verrà|verrete|sarai|sarete|sara|sarà|verr[aà] da noi|verr[aà]) (?:contattat[oaie]|chiamat[oaie]|ricontattat[oaie])\\b" +
            "|\\b(?:un|uno|una) (?:nostro|nostra|nostri) (?:operatore|operatrice|consulente|tecnico|incaricato|incaricata|addetto|addetta|collega) (?:ti|vi|la|le) (?:chiamer|contatter|telefoner)\\w*" +
            "|\\b(?:will|would) (?:call|contact|phone) you\\b|\\byou will be (?:called|contacted)\\b",
    )
    private val paymentRequest = p(
        "\\b(?:fammi|farmi|mandami|inviami|invia|manda|mandare|versa|versare|versami|ricaricami|ricarica|ricaricare|paga|pagami|pagare|pagarla|pagarlo|pagaci|" +
            "effettua|effettuare|esegui|eseguire|trasferisci|anticipa|deposita|depositare|pay|send|transfer|wire|deposit)\\b.{0,50}\\b" +
            "(?:bonifico|bonifici|soldi|denaro|euro|eur|ricarica|postepay|gift ?cards?|bitcoin|usdt|btc|money|cash|iban|commissione|commissioni|tassa|tasse|spese|fattura|importo|caparra|anticipo|fee|fees|fine|invoice|outstanding|balance|tax)\\b" +
            "|\\bpay (?:now|immediately|today|at once)\\b" +
            "|\\b(?:mi serve|mi servono|mi servirebbe|ho bisogno di|mi occorre|mi occorrono|mi mancano)\\b.{0,40}(?:\\d[\\d.,]*\\s?(?:euro|€|eur)|\\bsoldi\\b|\\bdenaro\\b|\\bun prestito\\b|\\bun bonifico\\b|\\buna ricarica\\b)",
    )
    /** «Mi fai un bonifico urgente?», «mi puoi aiutare con una ricarica?»: richiesta di soldi tra persone. */
    private val moneyAsk = p("\\b(?:mi fai|mi faresti|mi puoi fare|puoi farmi|potresti farmi|mi mandi|mi manderesti|mi puoi mandare|mi invii|mi puoi inviare|mi presti|mi puoi prestare|mi puoi aiutare con|mi aiuti con)\\b.{0,25}\\b(?:bonifico|bon ?ifico|bonifici|ricarica|soldi|denaro|euro|prestito|postepay|gift ?cards?)\\b")
    private val replyRequest = p(
        "\\b(?:scrivimi|scrivici|scrivetemi|contattami|contattaci|aggiungimi|aggiungici|rispondimi|rispondi|rispondete|risponda|rispondere con|chiamami|chiamaci|messaggiami|reply|text me|message me|contact me|add me|dm me)\\b" +
            "|\\b(?:scrivi|scrivere|contatta|contattare|aggiungi|unisciti|entra|join|add|message|text|contact|scrivete|contattate)\\b.{0,30}\\b(?:su|via|on|al|a|tramite)\\s+(?:whatsapp|telegram|signal|wechat|viber)\\b" +
            "|\\b(?:scrivi|scrivete|rispondi|rispondete|risponda|scriva|invia|inviaci|invii)\\s+[\"']?(?:ok|si|sì|yes|info|via|stop|ciao|start|1)[\"']?(?![\\p{L}\\p{N}])" +
            "|(?<![\\p{L}\\p{N}._])@[a-z0-9_]{5,32}\\b",
    )
    /** Nomi di pagamento: «serve un piccolo versamento di 29 euro», «contributo di 2 euro». */
    private val paymentNoun = p(
        "\\b(?:serve|servono|necessario|necessaria|richiesto|richiesta|obbligatorio|obbligatoria|previsto|prevista|dovuto|dovuta)\\b.{0,40}\\b(?:versamento|pagamento|bonifico|ricarica|anticipo|deposito|commissione|contributo|cauzione|caparra)\\b" +
            "|\\b(?:versamento|pagamento|contributo|deposito|anticipo|caparra|cauzione|commissione)(?:\\s+\\p{L}+)?\\s+(?:di|da)\\s+(?:€\\s?)?\\d" +
            // finto lavoro a task: «devi completare altri 3 pacchetti ordini da 120€»
            "|\\b(?:completare|completa|effettuare|acquistare)\\b.{0,25}\\b(?:pacchett\\p{L}*|task|ordini|compiti)\\b.{0,25}\\d+\\s?(?:€|euro)",
    )
    /** Nomi di richiesta di dati: «prevede l'inserimento dei codici di accesso». */
    private val credentialsNoun = p("\\b(?:inserimento|invio|comunicazione|conferma|verifica|aggiornamento)\\s+(?:dei|delle|del|della|dello|di)\\s+(?:tuoi |suoi |vostri )?(?:codici|codice|credenziali|password|pin|carta)\\b")
    private val credentialsNounGeneric = p("\\b(?:inserimento|invio|comunicazione|conferma|verifica|aggiornamento)\\s+(?:dei|delle|del|della|dello|di)\\s+(?:tuoi |suoi |vostri )?(?:dati|documento|documenti)\\b")
    /** Invito a premere/riprogrammare/pagare ora, tipico dei pulsanti delle email senza URL visibile: il link vero e' stato tolto dalla notifica. */
    private val callToAction = p(
        "\\b(?:clicca|(?<!prima di )(?<!evitare di )cliccare|tocca|premi|apri il link|segui il link|vai al link|accedi al link|riprogramma|riprogrammare|rinnova ora|rinnova il|paga ora|paga subito|verifica ora|conferma ora|sblocca ora|riattiva|aggiorna ora|accedi ora|verify now|confirm now|update now|log ?in now|sign ?in now|shop now|buy now|order now|acquista ora|acquista subito|ordina ora|ordina subito|compra ora|riscatta ora|riscatta subito|riscuoti ora|act now|agisci ora|agisci subito|claim your|claim now|claim the|redeem your|redeem now|complete the (?:short )?survey|take the survey|open the attach\\w+|apri l'allegato|apri il file allegato|acceda (?:all'area|al link|al portale|subito|per)|accedi all'area riservata|inquadri il (?:codice )?qr|inquadra il (?:codice )?qr|scansiona il (?:codice )?qr|scan the qr)\\b" +
            "|\\[\\s?(?:paga|rinnova|verifica|conferma|accedi|aggiorna|sblocca|riattiva|riprogramma|recupera|ripristina|richiedi|riscuoti|riscatta|reclama|verify|confirm|update|log ?in|sign ?in|pay|annulla|blocca|non sono stato io|sono stato io|proteggi|secure|cancel|block)[^\\]]{0,40}\\]",
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
        // Canale per agire: link, numero, email, «rispondi», «clicca». Senza, «ripristinato i dati dai backup» e' un racconto, non una richiesta.
        val channel = c.urls.isNotEmpty() || c.anomalies > 0 || c.phones.isNotEmpty() || emailAddress.matcher(c.rawText).find() || channelWords.matcher(c.text).find()
        // «unusual/suspicious login attempt» e' anche una riga di report tecnico: richiesta solo con un canale
        if ("ACC-04" in firedIds && channel) out.add(ActionVector.CREDENTIALS)
        // «verifica/aggiorna i dati» e' una richiesta di credenziali solo dentro una minaccia (blocco, accesso anomalo...), non in una newsletter di fedelta'
        if ("BAN-02" in firedIds && firedIds.any { it in THREAT_CONTEXT } && channel) out.add(ActionVector.CREDENTIALS)
        // un negozio dal dominio nudo + prezzi stracciati (SHP-01) e' lo schema dei falsi negozi: li' il dominio conta
        val bareIsWeak = "SHP-01" !in firedIds
        val fullLink = c.anomalies > 0 || c.urls.any { suspiciousLink(c, it) && !(bareIsWeak && it.raw in c.weakBare && !Detectors.technicallySuspicious(c, it, riskyTldCounts = false)) }
        if (fullLink) out.add(ActionVector.LINK)
        else if (c.urls.any { it.raw !in c.weakBare || c.isKnown(it.registrable) }) out.add(ActionVector.LINK_OFFICIAL)
        else if (c.urls.isNotEmpty()) out.add(ActionVector.LINK_BARE)
        if (c.phones.isNotEmpty()) out.add(ActionVector.PHONE)
        if (c.urls.isEmpty() && requested(callToAction, c.text)) out.add(ActionVector.LINK) // il link vero e' nel pulsante, tolto dalla notifica
        if (c.ibans.isNotEmpty() || requested(paymentRequest, c.text) || requested(moneyAsk, c.text) || requested(paymentNoun, c.text)) out.add(ActionVector.PAYMENT)
        if (requested(credentialsRequest, c.text) || requested(credentialsAsk, c.text) || requested(credentialsPossessive, c.text) || requested(credentialsNoun, c.text) ||
            (channel && (requested(credentialsGenericRequest, c.text) || requested(credentialsNounGeneric, c.text)))) out.add(ActionVector.CREDENTIALS)
        if (requested(announcedCall, c.text)) out.add(ActionVector.PHONE)
        if (requested(replyRequest, c.text)) out.add(ActionVector.REPLY)
        if (requested(installRequest, c.text)) out.add(ActionVector.INSTALL)
        if (emailRequested(c.rawText)) out.add(ActionVector.EMAIL)
        // dichiarazione esplicita «non devi fare nulla»: i soli link (deboli) non bastano a fare di una notifica una richiesta
        if (out.all { it == ActionVector.LINK_OFFICIAL || it == ActionVector.LINK_BARE } && noActionStated.matcher(c.text).find()) out.clear()
        return out
    }

    /** Regole che sono segnali tecnici (link, mittente, blocklist, analisi incompleta): bastano da sole a non dichiarare «nessun segnale». */
    fun isTechnical(ruleId: String) = ruleId.startsWith("URL-") || ruleId.startsWith("MIS-") || ruleId == "BLK-01" || ruleId == "INC-01"

    /** Regole di solo contesto commerciale/forma: da sole non portano mai a PROBABILE_TRUFFA. */
    fun isContextOnly(ruleId: String) =
        ruleId == "URG-01" || ruleId == "URG-05" || ruleId == "GEN-01" ||
            ruleId.startsWith("COM-") || ruleId.startsWith("FOR-") || ruleId.startsWith("OFF-") || ruleId.startsWith("AI-")
}
