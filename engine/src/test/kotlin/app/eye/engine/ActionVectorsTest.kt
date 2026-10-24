package app.eye.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Vettori d'azione e livelli di avviso: casi reali segnalati sulla 1.6.1 e truffe che devono continuare ad avvisare. */
class ActionVectorsTest {
    private val e = RuleEngine.default()
    private val gm = "com.google.android.gm"
    private val sms = "com.google.android.apps.messaging"

    private fun bg(text: String, sender: String? = null, origin: String = gm) = e.analyze(Input(text, Source.SHARE, declaredSender = sender, origin = origin))
    private fun fg(text: String, sender: String? = null) = e.analyze(Input(text, Source.PASTE, declaredSender = sender))

    private val plenitude = "Plenitude on the road\nComunicazione importante sulla sicurezza dei tuoi dati\nGentile cliente, ti informiamo che abbiamo rilevato un accesso non autorizzato ai nostri sistemi che ha coinvolto alcuni dati personali (nome, email, numero di telefono). Non sono stati coinvolti dati di pagamento né password. Abbiamo immediatamente bloccato l'accesso, avviato le verifiche con esperti di sicurezza e informato il Garante Privacy. Ti ricordiamo che non ti chiederemo mai password o codici via email, SMS o telefono."
    private val mcd = "McDonald's\nOfferta a tempo limitato ⏰\nSolo oggi nell'app: 2 Big Mac a 5€. Corri a riscattare il coupon prima che scada!"

    @Test fun dataBreachNoticeWithoutRequestsIsQuietAndNeverProbable() {
        for (o in listOf(gm, null)) {
            val v = e.analyze(Input(plenitude, Source.SHARE, declaredSender = "Plenitude on the road", origin = o))
            assertNotEquals(Level.PROBABILE_TRUFFA, v.level)
            assertFalse(AlertPolicy.shouldAlert(v))
            assertTrue(v.vectors.isEmpty(), v.vectors.toString())
        }
    }

    @Test fun commercialUrgencyAloneNeverAlertsNorProbable() {
        val v = bg(mcd, "McDonald's", origin = sms)
        assertFalse(AlertPolicy.shouldAlert(v))
        assertNotEquals(Level.PROBABILE_TRUFFA, fg(mcd, "McDonald's").level)
        // tante frasi di pressione, ancora nessuna azione: mai PROBABILE
        val heavy = "Affrettati! Solo oggi, offerta lampo, ultime ore, scorte limitate: sconto del 50%, corri subito, non perdere l'occasione, scade stasera!"
        assertNotEquals(Level.PROBABILE_TRUFFA, fg(heavy).level)
        assertFalse(AlertPolicy.shouldAlert(bg(heavy)))
    }

    @Test fun loyaltyNewsletterWithProfileUpdateIsNotProbable() {
        val t = "Roadhouse Restaurant\nIl tuo profilo R World\nAggiorna il tuo profilo nell'app per non perdere i punti: conferma i tuoi dati e continua ad accumulare vantaggi. Solo fino a domenica, ultimi giorni per i coupon! https://www.roadhouse.it/app\nLink: https://www.roadhouse.it/app"
        assertNotEquals(Level.PROBABILE_TRUFFA, fg(t, "Roadhouse Restaurant").level)
        assertFalse(AlertPolicy.shouldAlert(bg(t, "Roadhouse Restaurant")))
    }

    @Test fun bareShortenerWordIsNotALink() {
        for (t in listOf("Ho messo il link breve su bit.ly così lo scrivi più facile", "Ho usato tinyurl.com per accorciare", "Hai provato t.ly o is.gd? Io uso is.gd.", "vai su https://bit.ly/")) {
            val v = bg("Marco\n$t", "Marco", "com.whatsapp")
            assertFalse(v.reasons.any { it.ruleId == "URL-04" || it.ruleId == "URL-10" }, t)
            assertFalse(AlertPolicy.shouldAlert(v), t)
            assertEquals(emptyList(), ShortLinks.candidates(t, e.let { RuleEngine.loadBundled().lists.shorteners.toSet() }), t)
        }
        // con percorso resta un link accorciato
        val sh = RuleEngine.loadBundled().lists.shorteners.toSet()
        assertEquals(listOf("bit.ly/3AbCdE"), ShortLinks.candidates("Guarda bit.ly/3AbCdE", sh))
        assertEquals(listOf("https://tinyurl.com/abc"), ShortLinks.candidates("Guarda https://tinyurl.com/abc. Grazie", sh))
        assertTrue(AlertPolicy.shouldAlert(bg("Mario\nGuarda https://bit.ly/3AbCdE", "Mario", "com.whatsapp")))
    }

    @Test fun bareDomainInNotificationIsWeakButStaysStrongWithSchemeOrRealLink() {
        val ebay = "eBay\nScopri le novità dei tuoi venditori preferiti\nDai un'occhiata alle ultime inserzioni carpe.diem.shop 98.5% di feedback positivi Vedi tutti gli oggetti EUR 67,00"
        val v = bg(ebay, "eBay")
        assertFalse(AlertPolicy.shouldAlert(v))
        assertTrue(ActionVector.LINK !in v.vectors)
        // primo piano: il segnale resta visibile
        assertEquals(Level.SOSPETTO, fg(ebay, "eBay").level)
        // con schema o con link reale (riga «Link:») torna forte
        assertTrue(AlertPolicy.shouldAlert(bg("eBay\nGuarda carpe.diem.shop\nLink: https://carpe.diem.shop/x", "eBay")))
        assertTrue(AlertPolicy.shouldAlert(bg("eBay\nGuarda https://carpe.diem.shop/x", "eBay")))
        // dominio nudo ma con indizi tecnici (lookalike, parole sospette) resta forte
        assertTrue(AlertPolicy.shouldAlert(bg("Poste Italiane\nIl tuo pacco: poste-verifica-pacco.top", "Poste Italiane", sms)))
        assertTrue(AlertPolicy.shouldAlert(bg("Poste Italiane\nIl tuo conto e' bloccato, vai su postepay-sicurezza.com", "Poste Italiane", sms)))
    }

    @Test fun senderAndTextMismatchOnSameDomainCountOnce() {
        val t = "Google\nHai condiviso alcuni dati dell'Account Google con cloudsek.com\nAbbiamo inviato questa email perché hai usato Accedi con Google per accedere a cloudsek.com. Al momento non devi fare nulla."
        val v = bg(t, "Google")
        assertNotEquals(Level.PROBABILE_TRUFFA, v.level)
        assertFalse(AlertPolicy.shouldAlert(v))
        assertTrue(v.reasons.count { it.ruleId == "MIS-01" || it.ruleId == "MIS-02" } <= 1)
        assertNotEquals(Level.PROBABILE_TRUFFA, fg(t, "Google").level)
    }

    @Test fun ownEmailAddressIsNotAContactRequest() {
        val v = bg("Google\nMonitora i dati del tuo Account Google utente@example.com. Nessuna azione richiesta.", "Google")
        assertTrue(ActionVector.EMAIL !in v.vectors)
        assertTrue(ActionVector.EMAIL in bg("Ufficio\nScriva a rimborsi-esempio-eye@outlook.com con IBAN e documento", "Ufficio", sms).vectors)
    }

    @Test fun warningsAreNotRequests() {
        val v = bg("Banca\nNon comunicare mai a nessuno PIN, password o codici ricevuti via SMS: la banca non te li chiederà mai.", "Banca", sms)
        assertTrue(ActionVector.CREDENTIALS !in v.vectors)
        assertTrue(ActionVector.CREDENTIALS in bg("Banca\nInviaci subito la password e il codice che hai ricevuto via SMS", "Banca", sms).vectors)
    }

    @Test fun realScamsStillAlert() {
        val scams = listOf(
            "Poste Italiane\nIl tuo pacco è in giacenza. Costi di spedizione 1,99 EUR non pagati. Riprogramma la consegna: https://poste-consegna-it.com/t/8841",
            "Plenitude\nAbbiamo rilevato un accesso non autorizzato al tuo account. Per evitare la sospensione verifica subito i tuoi dati qui: https://plenitude-sicurezza-account.info/verifica",
            "Amazon\nHai vinto un buono Amazon da 500€! Solo oggi: ritira il premio su https://amazon-premi-italia.shop/ritira",
            "Mamma\nCiao mamma, ho rotto il telefono e questo è il mio nuovo numero. Puoi farmi un bonifico urgente di 480 euro? Ti spiego dopo",
        )
        for (t in scams) assertTrue(bg(t, t.substringBefore('\n')).level != Level.NESSUN_SEGNALE, t)
        for (t in scams.take(3)) assertTrue(AlertPolicy.shouldAlert(bg(t, t.substringBefore('\n'), sms)), t)
    }

    @Test fun probableNeverWithoutVector() {
        val v = fg("Gentile cliente, ti informiamo che il tuo account è stato sospeso. Ultimo avviso: agisci subito, entro 24 ore, per evitare la sospensione.")
        if (v.level == Level.PROBABILE_TRUFFA) assertTrue(v.vectors.isNotEmpty())
        assertEquals(AlertTier.NONE, e.analyze(Input("Ciao, ci vediamo alle 8?", Source.SHARE, origin = "com.whatsapp")).alertTier)
    }

    // --- 1.8.0: nessuna azione = nessun segnale, citazioni, canale, OCR ---

    @Test fun noActionMeansNoSignalWithInformativeReason() {
        val v = fg("Accesso anomalo rilevato sul tuo conto. Il team di sicurezza ha bloccato il tentativo di bonifico non autorizzato.")
        assertEquals(Level.NESSUN_SEGNALE, v.level)
        assertEquals(AlertTier.NONE, v.alertTier)
        val r = v.reasons.single()
        assertEquals("NOA-01", r.ruleId)
        assertEquals(0, r.weight)
        assertEquals("Il messaggio parla di sicurezza, accessi o pagamenti, ma non chiede di fare nulla.", r.text)
    }

    @Test fun technicalSignalKeepsTheLevelEvenWithoutAction() {
        val v = fg("Accesso anomalo al conto, vedi http://192.168.4.7/verifica")
        assertNotEquals(Level.NESSUN_SEGNALE, v.level)
    }

    @Test fun quotedScamPhrasesAreNotRequests() {
        val v = fg("Se ti arriva un messaggio che dice 'aggiorna i dati' o \"verifica il tuo conto\", ignoralo: è un tentativo di phishing.")
        assertEquals(Level.NESSUN_SEGNALE, v.level)
        assertTrue(v.vectors.isEmpty())
        // fuori dalle virgolette resta una richiesta
        assertNotEquals(Level.NESSUN_SEGNALE, fg("Gentile cliente, il conto è bloccato: aggiorna i dati su https://poste-verifica.top/login").level)
    }

    @Test fun announcedCallIsAPhoneVector() {
        val v = fg("Ti chiamerà un nostro operatore per sbloccare il tuo accesso anomalo: tieni pronto il codice.")
        assertTrue(ActionVector.PHONE in v.vectors, v.vectors.toString())
        assertTrue(ActionVector.PHONE in fg("Sarai contattato da un consulente della banca per il blocco della carta.").vectors)
    }

    @Test fun credentialsRequestNeedsTheReaderAndAChannel() {
        // racconto in terza persona/passato: nessuna richiesta
        val story = fg("Dopo l'attacco abbiamo isolato la rete, bonificato i sistemi e ripristinato i dati dai backup.")
        assertTrue(ActionVector.CREDENTIALS !in story.vectors && ActionVector.PAYMENT !in story.vectors)
        assertEquals(Level.NESSUN_SEGNALE, story.level)
        // rivolta al lettore, con un canale
        assertTrue(ActionVector.CREDENTIALS in fg("Account sospeso: aggiorna i dati cliccando sul pulsante qui sotto entro 24 ore.").vectors)
    }

    @Test fun ocrTextWithGluedWordsHasNoLinkAnomaly() {
        val t = "Lunedì mattina tutto procede come sempre in azienda..finché non arriva una chiamata. Abbiamo isolato linfezione."
        val ocr = e.analyze(Input(t, Source.OCR))
        assertTrue(ActionVector.LINK !in ocr.vectors)
        assertEquals(Level.NESSUN_SEGNALE, ocr.level)
    }

    @Test fun quotedScamWithRealLinkStillAlerts() {
        val v = bg("Gentile cliente, per sbloccare il conto clicchi su \"Verifica ora\": https://poste-verifica-conto.top/login. In caso contrario l'accesso verrà sospeso entro 24 ore.", origin = sms)
        assertNotEquals(Level.NESSUN_SEGNALE, v.level)
        assertTrue(AlertPolicy.shouldAlert(v))
    }
}
