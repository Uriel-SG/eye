package app.eye.android

import app.eye.android.core.ai.AiError
import app.eye.android.core.ai.AiJudgement
import app.eye.android.core.ai.AiOpinion
import app.eye.android.core.ai.AiOutcome
import app.eye.android.core.notif.AiCallLimiter
import app.eye.android.core.notif.BackgroundAiCheck
import app.eye.android.core.notif.CounterStore
import app.eye.engine.ActionVector
import app.eye.engine.Extracted
import app.eye.engine.Level
import app.eye.engine.Reason
import app.eye.engine.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundAiCheckTest {
    private fun v(level: Level = Level.NESSUN_SEGNALE, vectors: Set<ActionVector> = emptySet(), reasons: List<Reason> = emptyList(), amounts: List<String> = emptyList()) =
        Verdict(level, 0, reasons, emptyList(), Extracted(amounts = amounts), null, 1, vectors)
    private fun ok(j: AiJudgement, c: Double) = AiOutcome.Success(AiOpinion(j, emptyList(), "", c))

    @Test fun eligibleNeedsRealActionAndAClue() {
        assertTrue(BackgroundAiCheck.eligible(v(vectors = setOf(ActionVector.PHONE), reasons = listOf(Reason("URG-05", "x", 0)))))
        assertTrue(BackgroundAiCheck.eligible(v(vectors = setOf(ActionVector.PHONE), amounts = listOf("1.385,10 EUR"))))
        assertFalse(BackgroundAiCheck.eligible(v(vectors = setOf(ActionVector.PHONE)))) // nessun indizio
        assertFalse(BackgroundAiCheck.eligible(v(reasons = listOf(Reason("URG-05", "x", 0))))) // nessuna azione
        assertFalse(BackgroundAiCheck.eligible(v(vectors = setOf(ActionVector.LINK_OFFICIAL, ActionVector.LINK_BARE), amounts = listOf("5 EUR")))) // link deboli
        assertFalse(BackgroundAiCheck.eligible(v()))
    }

    @Test fun alertsOnlyOnConfidentRiskyOpinions() {
        assertEquals(Level.PROBABILE_TRUFFA, BackgroundAiCheck.levelFor(ok(AiJudgement.TRUFFA, 0.7)))
        assertNull(BackgroundAiCheck.levelFor(ok(AiJudgement.TRUFFA, 0.69)))
        assertEquals(Level.SOSPETTO, BackgroundAiCheck.levelFor(ok(AiJudgement.SOSPETTO, 0.9)))
        assertNull(BackgroundAiCheck.levelFor(ok(AiJudgement.SOSPETTO, 0.89)))
        assertNull(BackgroundAiCheck.levelFor(ok(AiJudgement.NESSUN_SEGNALE, 1.0)))
        assertNull(BackgroundAiCheck.levelFor(ok(AiJudgement.TRUFFA, Double.NaN)))
        assertNull(BackgroundAiCheck.levelFor(AiOutcome.Failure(AiError.NETWORK)))
        assertNull(BackgroundAiCheck.levelFor(null))
    }

    private class Mem : CounterStore {
        val m = HashMap<String, Long>()
        override fun get(key: String) = m[key] ?: 0L
        override fun put(key: String, value: Long) { m[key] = value }
    }

    @Test fun limiterCapsPerHourAndPerDay() {
        var now = 1_000L
        val mem = Mem()
        val lim = AiCallLimiter(mem, { now })
        repeat(20) { assertTrue(lim.tryAcquire()) }
        assertFalse(lim.tryAcquire()) // 21a nella stessa ora
        now += AiCallLimiter.HOUR_MS + 1
        repeat(20) { assertTrue(lim.tryAcquire()) } // nuova ora
        now += AiCallLimiter.HOUR_MS + 1
        repeat(20) { assertTrue(lim.tryAcquire()) }
        now += AiCallLimiter.HOUR_MS + 1
        repeat(20) { assertTrue(lim.tryAcquire()) }
        now += AiCallLimiter.HOUR_MS + 1
        repeat(20) { assertTrue(lim.tryAcquire()) } // 100 nel giorno
        now += AiCallLimiter.HOUR_MS + 1
        assertFalse(lim.tryAcquire()) // tetto giornaliero
        now += AiCallLimiter.DAY_MS
        assertTrue(lim.tryAcquire()) // il giorno dopo riparte
        assertTrue(mem.m.keys.all { it.endsWith("_t") || it.endsWith("_n") }) // solo numeri
    }
}
