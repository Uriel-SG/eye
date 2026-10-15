package app.eye.engine

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Regressione smoke test F1: una truffa ripetuta fino a 10k caratteri non deve mai dare NESSUN_SEGNALE (fail-safe). */
class LongInputFailSafeTest {
    private val scam = "Poste: conto sospeso https://poste-verifica-it.com/login "

    @Test fun repeatedScamIsDetectedFast() {
        val engine = RuleEngine.default()
        val text = scam.repeat(400)
        engine.analyze(Input(text, Source.SHARE)) // warm-up
        val t0 = System.nanoTime()
        val v = engine.analyze(Input(text, Source.SHARE))
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("repeated scam: ${v.level} score=${v.score} in ${ms}ms reasons=${v.reasons.map { it.ruleId }}")
        assertNotEquals(Level.NESSUN_SEGNALE, v.level)
        assertTrue(ms < 200, "troppo lento: ${ms}ms")
    }

    @Test fun budgetExhaustionFailsSafe() {
        val v = RuleEngine(RuleEngine.loadBundled(), budgetMillis = 0).analyze(Input("Ciao, ci vediamo domani alle 8", Source.PASTE))
        assertNotEquals(Level.NESSUN_SEGNALE, v.level, "con budget esaurito il verdetto deve essere prudente")
    }
}
