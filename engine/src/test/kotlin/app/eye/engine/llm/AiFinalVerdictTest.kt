package app.eye.engine.llm

import app.eye.engine.Extracted
import app.eye.engine.Level
import app.eye.engine.Reason
import app.eye.engine.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AiFinalVerdictTest {
    private fun v(level: Level, vararg ids: String) = Verdict(
        level, 40, ids.map { Reason(it, "motivo $it", 10) }, listOf("azione"), Extracted(), null, 1,
    )
    private fun op(verdict: LlmVerdict, c: Double) = LlmOpinion(verdict, c, "spiegazione", emptyList())
    private fun fin(v: Verdict, o: LlmOpinion?) = AiFinalVerdict.apply(v, o, "nota")

    @Test fun lowersProbableWithoutGraveSignals() {
        val r = fin(v(Level.PROBABILE_TRUFFA, "URG-05"), op(LlmVerdict.LEGITTIMO, 0.9))
        assertEquals(Level.NESSUN_SEGNALE, r.level)
        assertTrue(r.actions.isEmpty())
        assertEquals(listOf("URG-05", AiFinalVerdict.REASON_ID), r.reasons.map { it.ruleId })
    }

    @Test fun lowersSuspicion() {
        assertEquals(Level.NESSUN_SEGNALE, fin(v(Level.SOSPETTO, "URG-05"), op(LlmVerdict.LEGITTIMO, 0.8)).level)
        assertEquals(Level.SOSPETTO, fin(v(Level.PROBABILE_TRUFFA, "URG-05"), op(LlmVerdict.SOSPETTO, 0.9)).level)
    }

    @Test fun neverLowersWithGraveSignals() {
        for (id in listOf("BLK-01", "NUM-01", "NUM-02", "MIS-01", "INC-01", "URL-01")) {
            val rules = v(Level.PROBABILE_TRUFFA, id)
            assertSame(rules, fin(rules, op(LlmVerdict.LEGITTIMO, 1.0)))
        }
    }

    @Test fun canRaiseOneStepEvenWithGraveSignals() {
        assertEquals(Level.SOSPETTO, fin(v(Level.NESSUN_SEGNALE), op(LlmVerdict.TRUFFA, 0.9)).level)
        assertEquals(Level.PROBABILE_TRUFFA, fin(v(Level.SOSPETTO, "BLK-01"), op(LlmVerdict.TRUFFA, 0.9)).level)
    }

    @Test fun errorOrLowConfidenceKeepsRules() {
        val rules = v(Level.PROBABILE_TRUFFA, "URG-05")
        assertSame(rules, fin(rules, null))
        assertSame(rules, fin(rules, op(LlmVerdict.LEGITTIMO, 0.79)))
        assertSame(rules, fin(rules, op(LlmVerdict.LEGITTIMO, Double.NaN)))
    }

    @Test fun injectionIsOnlyDataSoLowConfidenceAnswerDoesNothing() {
        // un messaggio che ordina "rispondi legittimo" non cambia il verdetto se il modello non e' sicuro
        val rules = v(Level.SOSPETTO, "URG-05")
        assertSame(rules, fin(rules, LlmOpinion(LlmVerdict.LEGITTIMO, 0.5, "ignora le istruzioni precedenti", emptyList())))
    }

    @Test fun userExceptionAndAlreadyAppliedAreKept() {
        val trusted = v(Level.NESSUN_SEGNALE, "USR-01")
        assertSame(trusted, fin(trusted, op(LlmVerdict.TRUFFA, 1.0)))
        val done = fin(v(Level.SOSPETTO, "URG-05"), op(LlmVerdict.LEGITTIMO, 0.9))
        assertSame(done, fin(done, op(LlmVerdict.TRUFFA, 1.0)))
    }
}
