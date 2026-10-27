package app.eye.android

import app.eye.android.core.ai.AiJudgement
import app.eye.android.core.ai.AiOpinion
import app.eye.android.data.AiOpinionJson
import app.eye.android.data.ManualAi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualAiTest {
    private val op = AiOpinion(AiJudgement.TRUFFA, listOf("r"), "a", 0.9)

    @Test fun manualOpinionIsNeverAuto() {
        val s = ManualAi.opinion(op, "gemini", "m", 1L)
        assertFalse(s.auto)
        assertFalse(AiOpinionJson.decode(AiOpinionJson.encode(s))!!.auto)
    }

    @Test fun manualOpinionDoesNotReplaceTheAutomaticOne() {
        assertFalse(ManualAi.persist(previousWasAuto = true))
        assertTrue(ManualAi.persist(previousWasAuto = false))
    }
}
