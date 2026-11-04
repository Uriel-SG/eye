package app.eye.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Falso operatore «richiama il numero»: variante senza «subito», marchio scritto con puntini, marchio da cellulare. */
class CallbackScamTest {
    private val engine = RuleEngine(RuleEngine.loadBundled())
    private val pkg = "com.google.android.apps.messaging"

    private fun notif(sender: String, body: String) = engine.analyze(Input("$sender\n$body", Source.SHARE, declaredSender = sender, origin = pkg))

    private val nexi = "N.e.x.i.: E' stata richiesta un'autorizzazione di pagamento di 1.385,10 EUR. Se non ha effettuato lei l'operazione, contatti il Servizio Clienti: 0237920255"

    @Test fun nexiFromMobileIsProbableAndAlerts() {
        val v = notif("352 000 0000", nexi)
        assertEquals(Level.PROBABILE_TRUFFA, v.level)
        assertTrue(AlertPolicy.shouldAlert(v))
        val ids = v.reasons.map { it.ruleId }
        assertTrue("CALL-02" in ids && "BRD-02" in ids && "MIS-03" in ids, ids.toString())
        assertTrue(ActionVector.PHONE in v.vectors)
    }

    @Test fun obfuscatedBrandAndCallbackAloneAreEnough() {
        val v = notif("Avviso", nexi)
        assertEquals(Level.PROBABILE_TRUFFA, v.level)
        assertTrue(AlertPolicy.shouldAlert(v))
    }

    @Test fun plainBrandFromMobileWithCallback() {
        val v = notif("+39 351 000 0000", "Nexi: richiesta di pagamento di 900 EUR. In caso contrario chiami il 02 0000 0000")
        assertEquals(Level.PROBABILE_TRUFFA, v.level)
        assertTrue(AlertPolicy.shouldAlert(v))
    }

    @Test fun legitBankNoticeFromAlphanumericSenderDoesNotAlert() {
        for (b in listOf(
            "Nexi: autorizzazione pagamento di 85,00 EUR. Se non hai effettuato tu l'operazione, contatta il numero sul retro della carta o usa l'app ufficiale.",
            "Nexi: pagamento di 19,90 EUR. Se non riconosci l'operazione, contatta il Servizio Clienti al numero verde 800 000 000.",
        )) assertFalse(AlertPolicy.shouldAlert(notif("Nexi", b)), b)
    }

    @Test fun acronymsAreNotObfuscatedBrands() {
        for (b in listOf("I.N.P.S.: cedolino disponibile nell'area riservata.", "Rossi Esempio S.p.A. ha emesso la fattura 12.", "B.N.L. comunica gli orari di apertura delle filiali.")) {
            val v = notif("Info", b)
            assertTrue(v.reasons.none { it.ruleId == "BRD-02" }, b)
        }
    }

    @Test fun mobileSenderWithoutBankClaimNoFlag() {
        val v = notif("352 000 0000", "Ciao, ho pagato io il conto, ti mando la tua parte stasera")
        assertTrue(v.reasons.none { it.ruleId == "MIS-03" })
    }
}
