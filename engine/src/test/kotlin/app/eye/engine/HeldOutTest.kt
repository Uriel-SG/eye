package app.eye.engine

import kotlin.test.Test
import kotlin.test.assertTrue

/** Casi scritti a mano, indipendenti dal corpus, per controllare che le regole generalizzino. */
class HeldOutTest {
    private val engine = RuleEngine.default()
    private fun level(t: String, sender: String? = null) = engine.analyze(Input(t, Source.PASTE, sender)).level

    @Test fun scamVariantsAreFlagged() {
        val scams = listOf(
            "Telepass: risulta un pedaggio autostradale non saldato di 4,20 euro. Regolarizza su telepass-regolarizza.top/pay",
            "Gentile cliente, il suo conto BNL e' stato sospeso per attivita' sospetta. Acceda a bnl-sicurezza-clienti.com per verificare i dati.",
            "Ciao papa, ho cambiato numero e ho perso il telefono. Mi puoi fare un bonifico urgente? Poi ti spiego",
            "Ultimo avviso: la sua identita' digitale SPID e' in scadenza. Rinnovi ora su https://spid-rinnovo-gov.net/login",
            "Hai vinto un iPhone 15! Ritira il premio entro 24 ore: https://cutt.ly/premio-iphone",
            "Agenzia delle Entrate: rimborso di 412,00 euro disponibile. Richiedilo su agenziaentrate-rimborsi.xyz",
            "Sono dell'ufficio antifrode della tua banca, mi comunichi il codice OTP appena ricevuto per bloccare l'operazione",
            "Paypa1: il tuo account e' limitato. Verifica i dati su https://paypa1-secure.com/login",
        )
        for (s in scams) assertTrue(level(s) != Level.NESSUN_SEGNALE, "non rilevata: $s")
    }

    @Test fun legitMessagesAreNotFlagged() {
        val legit = listOf(
            "Ciao, ci vediamo alle 20 al ristorante? Ho prenotato per quattro.",
            "Il tuo ordine Amazon e' stato spedito. Traccia la consegna su https://www.amazon.it/gp/your-account/order-details",
            "Poste Italiane: il tuo codice di conferma e' 584120. Non condividerlo con nessuno.",
            "Promemoria: appuntamento dal dentista domani alle 15:30. Per disdire chiami lo 02 1234567.",
            "Mamma, arrivo tardi stasera, non aspettarmi per cena.",
            "La bolletta del gas di settembre e' disponibile nell'area clienti su https://www.enel.it",
            "Grazie per l'acquisto! Il tuo pacco BRT arrivera' domani. Seguilo su https://www.brt.it/it/",
            "Riunione condominio giovedi' alle 18 in prima convocazione. Ordine del giorno in allegato.",
        )
        for (s in legit) assertTrue(level(s) == Level.NESSUN_SEGNALE, "falso positivo: $s")
    }
}
