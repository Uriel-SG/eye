package app.eye.android

import app.eye.android.core.CheckOutcome
import app.eye.android.core.CheckUseCase
import app.eye.android.data.HistoryRepository
import app.eye.engine.Source
import app.eye.engine.provenance.ProvenanceKind
import app.eye.engine.provenance.ProvenanceMessages
import app.eye.engine.provenance.ProvenanceReport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvenanceUseCaseTest {
    private val uc = CheckUseCase(FakeAnalyzer(), HistoryRepository(FakeDao()), { false })
    private val text = "Urgente: codice PIN subito https://x.top"

    @Test fun aiDeclaredAddsInformativeReasonWithoutChangingLevel() = runTest {
        val base = (uc.run(text, Source.OCR) as CheckOutcome.Done).result.verdict
        val r = (uc.run(text, Source.OCR, provenance = ProvenanceReport(ProvenanceKind.AI_DECLARED, "Midjourney")) as CheckOutcome.Done).result
        assertEquals(base.level, r.verdict.level)
        assertEquals(base.score, r.verdict.score)
        val reason = r.verdict.reasons.single { it.ruleId == ProvenanceMessages.REASON_ID }
        assertEquals(0, reason.weight)
        assertTrue(reason.text.contains("Midjourney"))
        assertEquals(ProvenanceKind.AI_DECLARED, r.provenance?.kind)
    }

    @Test fun noAiMeansNoExtraReason() = runTest {
        for (k in listOf(ProvenanceKind.NONE, ProvenanceKind.C2PA_NO_AI)) {
            val r = (uc.run(text, Source.OCR, provenance = ProvenanceReport(k)) as CheckOutcome.Done).result
            assertTrue(r.verdict.reasons.none { it.ruleId == ProvenanceMessages.REASON_ID })
            assertEquals(k, r.provenance?.kind)
        }
    }
}
