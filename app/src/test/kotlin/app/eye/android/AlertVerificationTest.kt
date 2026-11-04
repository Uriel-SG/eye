package app.eye.android

import app.eye.android.core.ai.AiError
import app.eye.android.core.ai.AiJudgement
import app.eye.android.core.ai.AiOpinion
import app.eye.android.core.ai.AiOutcome
import app.eye.android.core.notif.AlertBackend
import app.eye.android.core.notif.AlertReason
import app.eye.android.core.notif.AlertStore
import app.eye.android.core.notif.VerifyDecision
import app.eye.android.core.notif.VerifyPolicy
import app.eye.android.data.TextCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertVerificationTest {
    private fun op(j: AiJudgement, c: Double) = AiOutcome.Success(AiOpinion(j, emptyList(), "", c))
    private fun d(o: AiOutcome?, ok: Boolean = true) = VerifyPolicy.decide(o) { ok }

    @Test fun networkAndTimeoutAlertImmediately() {
        assertEquals(VerifyDecision.Alert(AlertReason.NETWORK), d(AiOutcome.Failure(AiError.NETWORK)))
        assertEquals(VerifyDecision.Alert(AlertReason.TIMEOUT), d(AiOutcome.Failure(AiError.TIMEOUT)))
    }

    @Test fun otherOutcomesAlertWithReason() {
        assertEquals(VerifyDecision.Alert(AlertReason.NOT_VERIFIED), d(null))
        assertEquals(VerifyDecision.Alert(AlertReason.AI_LIMIT), d(AiOutcome.Failure(AiError.FREE_LIMIT)))
        assertEquals(VerifyDecision.Alert(AlertReason.AI_LIMIT), d(AiOutcome.Failure(AiError.RATE_LIMIT)))
        assertEquals(VerifyDecision.Alert(AlertReason.AI_ERROR), d(AiOutcome.Failure(AiError.INVALID)))
        assertEquals(VerifyDecision.Alert(AlertReason.AI_NOT_LEGIT), d(op(AiJudgement.SOSPETTO, 0.99)))
        assertEquals(VerifyDecision.Alert(AlertReason.AI_LOW_CONF), d(op(AiJudgement.NESSUN_SEGNALE, 0.3), ok = false))
        assertEquals(VerifyDecision.Dismiss, d(op(AiJudgement.NESSUN_SEGNALE, 0.99)))
    }

    @Test fun reasonParse() {
        assertEquals(AlertReason.GRAVE, AlertReason.parse("GRAVE"))
        assertEquals(AlertReason.AI_BACKGROUND, AlertReason.parse("AI_BACKGROUND"))
        assertNull(AlertReason.parse("boh")); assertNull(AlertReason.parse(null))
    }

    private object Rot : TextCrypto {
        override fun encrypt(plain: String) = "enc:" + plain.reversed()
        override fun decrypt(stored: String) = stored.takeIf { it.startsWith("enc:") }?.removePrefix("enc:")?.reversed()
    }
    private class Mem : AlertBackend {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
        override fun keys() = m.keys.toSet()
    }

    @Test fun pendingIsClaimedOnceAndKeepsDataEncrypted() {
        val b = Mem(); val s = AlertStore(b, Rot)
        assertTrue(s.put(5, "testo", "SOSPETTO", "pkg", "Mitt", "NETWORK", "{\"level\":\"SOSPETTO\"}", pending = true))
        assertTrue(b.m.values.none { it.contains("testo") || it.contains("otsot") || it.contains("NETWORK") }) // nulla in chiaro
        val first = s.claimPending(5)
        assertNotNull(first)
        assertEquals("NETWORK", first!!.reason)
        assertNull(s.claimPending(5)) // secondo lavoro: niente doppio avviso
        s.setReason(5, "AI_LIMIT")
        val a = s.takeAlert(5)!! // l'avviso pubblicato si apre normalmente
        assertEquals("AI_LIMIT", a.reason); assertEquals("SOSPETTO", a.level); assertEquals("testo", a.text)
    }

    @Test fun immediateAlertIsNotPending() {
        val s = AlertStore(Mem(), Rot)
        s.put(6, "x", "SOSPETTO")
        assertNull(s.claimPending(6))
    }
}
