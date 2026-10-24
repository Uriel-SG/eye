package app.eye.engine

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AiCombineTest {
    private val bundled = RuleEngine.loadBundled()
    // Il declassamento AI-02 è DISATTIVATO nelle regole incluse (lowerThreshold = 0): i test della funzione
    // (che resta configurabile via feed) usano una copia con la soglia storica 0.005.
    private val rules = bundled.copy(ai = bundled.ai.copy(lowerThreshold = 0.005))
    private fun engine(c: TextClassifier?) = RuleEngine(rules, classifier = c)
    private fun engineT(timeout: Long, c: TextClassifier) = RuleEngine(rules, classifier = c, classifierTimeoutMillis = timeout)
    private fun v(e: RuleEngine, t: String) = e.analyze(Input(t, Source.PASTE))

    // con un vettore d'azione (un numero da chiamare): senza azioni il modello da solo non alza mai il verdetto
    private val neutral = "Ciao, ci vediamo domani alle otto per la cena. Chiamami al 347 123 4567."
    // segnali solo deboli (urgenza/minaccia di sospensione), nessuna regola «grave»: SOSPETTO con le sole regole
    private val weak = "Ultimo avviso: la preghiamo di rispondere subito a questo messaggio o di chiamare il 347 123 4567."
    private val hard = "Gentile cliente, comunichi subito il codice OTP ricevuto via SMS per sbloccare la carta."
    private val probable = "Poste: il tuo conto è sospeso, verifica subito su http://poste-verifica.top e comunica il codice OTP e il PIN."

    @Test fun raiseNoSignalToSuspect() {
        val base = v(engine(null), neutral)
        assertEquals(Level.NESSUN_SEGNALE, base.level)
        val r = v(engine { 0.999f }, neutral)
        assertEquals(Level.SOSPETTO, r.level)
        assertEquals("AI-01", r.reasons.first().ruleId)
        assertTrue(r.reasons.first().text.contains("modello AI"))
        assertEquals(rules.thresholds.suspect, r.score)
        assertTrue(r.actions.isNotEmpty())
    }

    @Test fun noRaiseBelowThreshold() {
        val t = rules.ai.raiseThreshold.toFloat()
        assertEquals(Level.NESSUN_SEGNALE, v(engine { t - 0.01f }, neutral).level)
        assertEquals(Level.NESSUN_SEGNALE, v(engine { t * 0.5f }, neutral).level)
    }

    @Test fun unavailableOrBrokenClassifierIgnored() {
        assertEquals(Level.NESSUN_SEGNALE, v(engine { null }, neutral).level)
        assertEquals(Level.NESSUN_SEGNALE, v(engine { error("boom") }, neutral).level)
        assertEquals(Level.NESSUN_SEGNALE, v(engine { Float.NaN }, neutral).level)
    }

    @Test fun slowClassifierTimesOut() {
        val t0 = System.nanoTime()
        val r = v(engineT(200) { Thread.sleep(5000); 0.9999f }, neutral)
        assertEquals(Level.NESSUN_SEGNALE, r.level)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2000)
    }

    @Test fun probableNeverChangedAndClassifierNotCalled() {
        val calls = AtomicInteger()
        val base = v(engine(null), probable)
        assertEquals(Level.PROBABILE_TRUFFA, base.level)
        val r = v(engine { calls.incrementAndGet(); 0.0001f }, probable)
        assertEquals(Level.PROBABILE_TRUFFA, r.level)
        assertEquals(base.reasons, r.reasons)
        assertEquals(0, calls.get())
    }

    @Test fun lowerSuspectToNoSignalWhenWeakOnly() {
        val base = v(engine(null), weak)
        assertEquals(Level.SOSPETTO, base.level, "precondizione: ${base.reasons}")
        assertTrue(base.reasons.none { it.ruleId in rules.ai.hardRuleIds })
        val r = v(engine { 0.001f }, weak)
        assertEquals(Level.NESSUN_SEGNALE, r.level)
        assertEquals("AI-02", r.reasons.first().ruleId)
        assertEquals(0, r.reasons.first().weight)
        assertTrue(r.reasons.size > 1, "i motivi deboli restano visibili")
        assertNotNull(r.reasons.firstOrNull { it.ruleId == base.reasons.first().ruleId })
        assertTrue(r.score < rules.thresholds.suspect)
    }

    @Test fun lowerDeniedWhenProbabilityAboveLimit() {
        assertEquals(Level.SOSPETTO, v(engine { 0.006f }, weak).level)
        assertEquals(Level.SOSPETTO, v(engine { 0.5f }, weak).level)
        assertEquals(Level.SOSPETTO, v(engine { 0.9999f }, weak).level) // alta p non cambia un SOSPETTO
    }

    @Test fun lowerDeniedWithHardRule() {
        val base = v(engine(null), hard)
        assertTrue(base.level != Level.NESSUN_SEGNALE)
        assertTrue(base.reasons.any { it.ruleId in rules.ai.hardRuleIds })
        val r = v(engine { 0.0f }, hard)
        assertEquals(base.level, r.level)
        assertEquals(base.reasons, r.reasons)
    }

    @Test fun hardListMatchesRules() {
        val ids = rules.rules.map { it.id }.toSet()
        assertTrue(rules.ai.hardRuleIds.all { it in ids }, "ID hard inesistenti: ${rules.ai.hardRuleIds.filter { it !in ids }}")
        assertEquals(AiConfig().hardRuleIds, rules.ai.hardRuleIds, "rules.json e default nel codice devono coincidere")
    }

    @Test fun noScamEverLoweredOnValidation() {
        assumeTrue(MlTestSupport.valJsonl.exists() && MlTestSupport.valScores.exists(), "ml/data/val.jsonl non disponibile")
        val scores = MlTestSupport.csvScores()
        val rows = MlTestSupport.valRows()
        val byText = rows.associate { it.text to scores.getValue(it.id).toFloat() }
        // Configurazione di produzione (AI-02 spento): nessuna truffa deve scendere con il modello attuale.
        val eng = RuleEngine(bundled, classifier = { byText[it] })
        val plain = RuleEngine(bundled)
        var lowered = 0; var raised = 0; var scamLowered = 0; var scamRaised = 0
        for (r in rows) {
            val a = v(plain, r.text); val b = v(eng, r.text)
            if (a.level == Level.SOSPETTO && b.level == Level.NESSUN_SEGNALE) { lowered++; if (r.label == "scam") scamLowered++ }
            if (a.level == Level.NESSUN_SEGNALE && b.level == Level.SOSPETTO) { raised++; if (r.label == "scam") scamRaised++ }
            assertTrue(!(a.level == Level.PROBABILE_TRUFFA && b.level != a.level), "PROBABILE toccato: ${r.id}")
        }
        println("validation: declassati $lowered (truffe: $scamLowered), alzati $raised (truffe: $scamRaised)")
        assertEquals(0, scamLowered, "nessuna truffa deve essere declassata")
    }

    @Test fun bundledRulesNeverLowerAVerdict() {
        assertEquals(0.0, bundled.ai.lowerThreshold)
        val base = RuleEngine(bundled).analyze(Input(weak, Source.PASTE))
        val r = RuleEngine(bundled, classifier = { 0.0001f }).analyze(Input(weak, Source.PASTE))
        assertEquals(base.level, r.level, "con le regole incluse il modello non deve mai abbassare il verdetto")
    }

    @Test fun noRaiseWhenAllLinksAreWellKnown() {
        val friend = "guarda qua https://www.reddit.com/r/Android/comments/abc/novita_google/ fa ridere"
        assertEquals(Level.NESSUN_SEGNALE, RuleEngine(bundled, classifier = { 0.999f }).analyze(Input(friend, Source.PASTE)).level)
    }

    @Test fun groupInviteLinksAreNotTrusted() {
        val invite = "Entra nel gruppo, guadagni ogni giorno: https://t.me/+gruppoXq7k"
        assertTrue(RuleEngine(bundled, classifier = { 0.999f }).analyze(Input(invite, Source.PASTE)).level != Level.NESSUN_SEGNALE)
    }
}
