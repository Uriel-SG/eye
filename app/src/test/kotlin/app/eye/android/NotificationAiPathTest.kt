package app.eye.android

import app.eye.android.core.notif.NotifData
import app.eye.android.core.notif.NotificationText
import app.eye.engine.Input
import app.eye.engine.Level
import app.eye.engine.RuleEngine
import app.eye.engine.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Percorso notifiche: NotificationText.compose + RuleEngine con classificatore. Regressione v1.4.0: ogni notifica
 * (anche «ok» o il segnaposto «1 nuovo messaggio») diventava SOSPETTO per AI-01. Il modello, anche sul PC, da' p alta
 * ai segnaposto delle app (WhatsApp|1 nuovo messaggio 0.99, Mario|Messaggio eliminato 0.96).
 */
class NotificationAiPathTest {
    private fun notif(title: String, body: String, pkg: String = "com.whatsapp") =
        NotifData(pkg = pkg, ongoing = false, groupSummary = false, title = title, text = body, bigText = null)

    private fun level(n: NotifData, p: Float): Level {
        val engine = RuleEngine(RuleEngine.loadBundled(), classifier = { p })
        val text = NotificationText.compose(n)
        assertNotNull(text)
        return engine.analyze(Input(text!!, Source.SHARE, declaredSender = n.title?.toString(), origin = n.pkg)).level
    }

    @Test fun ordinaryChatsNeverAlert() {
        for ((t, b) in listOf("Mario" to "ok", "Mario" to "Ci vediamo dopo alle 8", "Giulia" to "Hai visto la partita ieri sera?", "Mamma" to "👍")) {
            assertEquals("$t/$b p basso", Level.NESSUN_SEGNALE, level(notif(t, b), 0.003f))
        }
    }

    @Test fun appPlaceholdersNeverAlertEvenWithHighP() {
        for ((t, b) in listOf("WhatsApp" to "1 nuovo messaggio", "WhatsApp" to "3 messaggi da 2 chat", "Telegram" to "Nuovo messaggio", "Mario" to "Messaggio eliminato", "Mario" to "ok")) {
            assertEquals("$t/$b", Level.NESSUN_SEGNALE, level(notif(t, b), 0.99f))
        }
    }

    @Test fun longTextNeverRaisedByModelOnNotifications() {
        val n = notif("Numero sconosciuto", "Buongiorno, abbiamo una proposta di lavoro per lei, mi risponda appena possibile.")
        assertEquals(Level.NESSUN_SEGNALE, level(n, 0.99f))
    }

    @Test fun pasteOfShortTextStillRaisedOnlyWithAnAction() {
        val engine = RuleEngine(RuleEngine.loadBundled(), classifier = { 0.99f })
        assertEquals(Level.SOSPETTO, engine.analyze(Input("Mario\nok https://x-promo-win.top/a", Source.PASTE)).level)
        // nessuna azione richiesta: il modello da solo non basta a fare di un testo un sospetto
        assertEquals(Level.NESSUN_SEGNALE, engine.analyze(Input("Mario\nok", Source.PASTE)).level)
    }
}
