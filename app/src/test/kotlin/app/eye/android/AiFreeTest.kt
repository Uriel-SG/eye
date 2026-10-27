package app.eye.android

import app.eye.android.core.ai.AiError
import app.eye.android.core.ai.AiJudgement
import app.eye.android.core.ai.AiOutcome
import app.eye.android.core.ai.AiProvider
import app.eye.android.core.ai.AiRequests
import app.eye.android.core.ai.AiResponses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiFreeTest {
    @Test fun pollinationsRequestHasNoCredentials() {
        val spec = AiRequests.pollinations("ciao [link nascosto]", "abc")
        assertEquals("https://text.pollinations.ai/openai", spec.url)
        assertEquals(setOf("content-type"), spec.headers.keys)
        assertEquals(30_000, spec.readTimeoutMs)
        assertTrue(spec.body.contains("<<<MESSAGGIO_abc>>>"))
    }

    @Test fun http402IsFreeLimit() {
        val o = AiResponses.parse(AiProvider.OPENAI, 402, "{}", free = true)
        assertEquals(AiError.FREE_LIMIT, (o as AiOutcome.Failure).error)
        // con una chiave propria il 402 resta un errore HTTP generico
        assertEquals(AiError.HTTP, (AiResponses.parse(AiProvider.OPENAI, 402, "{}") as AiOutcome.Failure).error)
    }

    @Test fun confidenceIsParsedAndDefaultsToZero() {
        val a = AiResponses.parseOpinion("""{"giudizio":"nessun_segnale","motivi":[],"consiglio":"","confidenza":0.93}""")!!
        assertEquals(AiJudgement.NESSUN_SEGNALE, a.judgement)
        assertEquals(0.93, a.confidence, 1e-9)
        assertEquals(0.0, AiResponses.parseOpinion("""{"giudizio":"nessun_segnale","motivi":[],"consiglio":""}""")!!.confidence, 0.0)
        assertEquals(1.0, AiResponses.parseOpinion("""{"giudizio":"sospetto","motivi":[],"consiglio":"","confidenza":7}""")!!.confidence, 0.0)
    }

    @Test fun geminiIsDefaultWithFlashLite() {
        assertEquals(AiProvider.GEMINI, AiProvider.fromId(null))
        assertEquals("gemini-flash-lite-latest", AiProvider.GEMINI.defaultModel)
        assertFalse(AiRequests.systemPrompt("n").isEmpty())
    }

    @Test fun googleKeyFormat() {
        val k = "AIza" + "a1B2-_".repeat(6).take(35)
        assertTrue(app.eye.android.ui.GOOGLE_KEY.matches(k))
        assertFalse(app.eye.android.ui.GOOGLE_KEY.matches(k + "x"))
        assertFalse(app.eye.android.ui.GOOGLE_KEY.matches("ciao " + k))
        assertFalse(app.eye.android.ui.GOOGLE_KEY.matches("sk-" + k))
        val n = "AQ." + "Ab8RN6-_.x".repeat(5)
        assertTrue(app.eye.android.ui.GOOGLE_KEY.matches(n))
        assertFalse(app.eye.android.ui.GOOGLE_KEY.matches("AQ.corta"))
        assertFalse(app.eye.android.ui.GOOGLE_KEY.matches(n + " x"))
    }
}
