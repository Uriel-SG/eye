package app.eye.android

import app.eye.android.core.ai.AiError
import app.eye.android.core.ai.AiJudgement
import app.eye.android.core.ai.AiOpinion
import app.eye.android.core.ai.AiOutcome
import app.eye.android.core.notif.AiVerifyRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiVerifyRetryTest {
    private val ok = AiOutcome.Success(AiOpinion(AiJudgement.NESSUN_SEGNALE, listOf("ok"), "", 0.9))
    private val net = AiOutcome.Failure(AiError.NETWORK)

    private class Fake { var now = 0L; val sleeps = mutableListOf<Long>(); fun sleep(ms: Long) { now += ms; sleeps += ms } }

    @Test fun successFirstTryNoRetry() {
        val f = Fake(); var calls = 0
        val r = AiVerifyRetry({ true }, f::sleep, { f.now }).run { calls++; ok }
        assertEquals(ok, r); assertEquals(1, calls)
    }

    @Test fun networkBackRetriesOnceAndSucceeds() {
        val f = Fake(); var calls = 0
        var online = false
        val r = AiVerifyRetry({ online }, { f.sleep(it); if (f.now >= 6_000) online = true }, { f.now }).run { calls++; if (calls == 1) net else ok }
        assertEquals(ok, r); assertEquals(2, calls)
    }

    @Test fun networkNeverBackFallsBackToFirstFailure() {
        val f = Fake(); var calls = 0
        val r = AiVerifyRetry({ false }, f::sleep, { f.now }).run { calls++; net }
        assertEquals(net, r); assertEquals(1, calls)
        assertTrue(f.now <= AiVerifyRetry.WAIT_MS + AiVerifyRetry.POLL_MS)
    }

    @Test fun otherErrorsAreNotRetried() {
        var calls = 0
        val bad = AiOutcome.Failure(AiError.AUTH)
        assertEquals(bad, AiVerifyRetry({ true }, { }, { 0L }).run { calls++; bad })
        assertEquals(1, calls)
    }

    @Test fun secondFailureReturnsFailure() {
        val f = Fake(); var calls = 0
        val r = AiVerifyRetry({ true }, f::sleep, { f.now }).run { calls++; net }
        assertEquals(net, r); assertEquals(2, calls)
    }
}
