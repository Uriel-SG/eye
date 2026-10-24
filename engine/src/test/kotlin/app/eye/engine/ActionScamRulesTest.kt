package app.eye.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Regole FAM-03 (finto familiare), CALL-01 (richiamata), MAI-01 (risposta via email), PAC-05 (consegna): scatti e non scatti. */
class ActionScamRulesTest {
    private val engine = RuleEngine.default()
    private fun v(sender: String, body: String) =
        engine.analyze(Input("$sender\n$body", Source.SHARE, declaredSender = sender, origin = "com.google.android.apps.messaging"))
    private fun fired(sender: String, body: String, id: String) = v(sender, body).reasons.any { it.ruleId == id }

    @Test fun fakeRelativeAlerts() {
        for ((s, t) in listOf(
            "+39 328 774 0912" to "Ciao papà sono io, ho perso il telefono e il portafoglio. Mi serve che mi mandi subito 350 euro",
            "+39 389 661 4075" to "Mamma sono Luca, ho il telefono nuovo. Ho una fattura da pagare oggi, puoi pagarla tu? Ti mando l'IBAN",
            "Numero sconosciuto" to "Ciao nonno, sono Giulia. Ho avuto un incidente e mi servono 900 euro subito. Non dirlo ai miei genitori",
            "+39 364 159 8989" to "Hi mum, this is my new number, my phone fell in the water. Can you pay a bill for me?",
        )) assertTrue(AlertPolicy.shouldAlert(v(s, t)), t)
    }

    @Test fun realFamilyChatsNeverAlert() {
        for ((s, t) in listOf(
            "Luca" to "Mamma mi fai un bonifico per la spesa? Poi ti rendo i soldi domenica",
            "Alessio" to "Papà il telefono mi si è rotto, domani lo porto al centro assistenza. Mi anticipi la riparazione?",
            "Chiara" to "Ciao nonna, ho rotto il telefono ma lo aggiusto domani. Intanto scrivimi qui",
            "Nonno" to "Ciao Luca, grazie per aver pagato tu la bolletta, domani ti do i soldi",
        )) {
            assertFalse(AlertPolicy.shouldAlert(v(s, t)), t)
            assertFalse(fired(s, t, "FAM-03"), t)
        }
    }

    @Test fun callbackNeedsAlarmAndPressure() {
        for ((s, t) in listOf(
            "Banca Sella" to "Rilevato addebito di 1.390 € su tua carta. Se non lo riconosci contatta subito l'assistenza al 02 8734 5512.",
            "Poste Italiane" to "Rilevata attività anomala sul tuo conto BancoPosta. Per la tua sicurezza chiama il numero 06 8704 2209 e un operatore ti guiderà.",
            "Norton" to "Il tuo abbonamento Norton è stato rinnovato per 349,99 €. Per annullare e ottenere il rimborso chiama il +39 02 9475 2201.",
        )) assertTrue(AlertPolicy.shouldAlert(v(s, t)), t)
        for ((s, t) in listOf(
            "Amazon" to "Il tuo ordine è stato spedito. Per informazioni sul tuo ordine chiama il servizio clienti al numero 800 20 20 20.",
            "Poste Italiane" to "Accesso all'app BancoPosta eseguito il 19/10 alle 10:22. Se non sei stato tu contatta il numero verde 800 00 33 22.",
            "Studio Dott. Bianchi" to "Confermiamo la visita di giovedì alle 16:30. Se non può venire chiami lo 06 1234 5678 per spostarla.",
        )) assertFalse(AlertPolicy.shouldAlert(v(s, t)), t)
    }

    @Test fun freeMailReplyWithAlarmContext() {
        assertTrue(fired("Ufficio Rimborsi", "Il suo pagamento risulta in eccesso. Scriva a rimborsi-esempio-eye@outlook.com con IBAN e documento d'identità per il rimborso.", "MAI-01"))
        assertFalse(fired("Luca", "Per il preventivo scrivi a nome.esempio.eye@gmail.com, io sono fuori fino a lunedì", "MAI-01"))
        assertFalse(fired("Hotel", "La sua prenotazione è confermata. Per richieste scriva a info@hotel-esempio.it", "MAI-01"))
    }

    @Test fun parcelReplyWithPersonalData() {
        val r = v("Servizio Clienti", "Gentile cliente, abbiamo problemi con la sua consegna. Risponda a questo messaggio con indirizzo completo e data di nascita per poter riprogrammare.")
        assertEquals(setOf("PAC-05"), r.reasons.map { it.ruleId }.filter { it == "PAC-05" }.toSet())
        assertTrue(AlertPolicy.shouldAlert(r))
        assertFalse(AlertPolicy.shouldAlert(v("BRT", "Il tuo pacco sarà consegnato domani tra le 9 e le 13. Segui la spedizione su https://www.brt.it")))
    }

    @Test fun moneyRequestFromNamedContactIsQuiet() {
        val t = "mi puoi fare una ricarica Postepay? Domani ti do i soldi, devo pagare l'abbonamento della palestra"
        val named = v("Mamma", t)
        assertTrue(ActionVector.PAYMENT in named.vectors)
        assertEquals(AlertTier.QUIET, named.alertTier)
        // stesso testo da un numero sconosciuto: nessuno sconto
        assertTrue(AlertPolicy.shouldAlert(v("+39 328 774 0912", t)))
        // con un IBAN o con gift card non e' piu' una chat tra conoscenti
        assertTrue(AlertPolicy.shouldAlert(v("Mamma", "$t. Compra una gift card Amazon e mandami il codice")))
    }

    @Test fun onlineSchemesAlert() {
        for ((s, t) in listOf(
            "+39 345 489 2924" to "Hi dear, I am an engineer on an offshore platform. I feel something special between us. I have a small problem with my bank access, can you help me with a gift card? I will repay you when I return.",
            "+39 398 579 5220" to "Gruppo VIP Trading: segnali con 94% di successo. Raddoppia il capitale in 7 giorni con il nostro broker certificato. Registrati: bit-profit-xq7k.com/vip",
            "Info" to "Monopattino elettrico X9 PRO - 90% OFF, solo 99 EUR! Free delivery in Italy, pay with crypto or bank transfer only. escooter-xq7k.shop",
            "+39 376 166 1801" to "Buongiorno, sono il proprietario dell'appartamento a Milano. Sono all'estero per lavoro, invio le chiavi dopo bonifico di caparra di 900 EUR.",
            "Servizio Clienti" to "Gentile candidato, la Sua candidatura e' stata selezionata. Per l'assunzione la societa' richiede il pagamento anticipato di 180 euro per la divisa e la formazione obbligatoria, rimborsabili con il primo stipendio.",
        )) assertTrue(AlertPolicy.shouldAlert(v(s, t)), t)
    }
}
