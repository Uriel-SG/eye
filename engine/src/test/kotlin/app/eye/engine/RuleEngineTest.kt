package app.eye.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleEngineTest {
    private val engine = RuleEngine.default()
    private fun analyze(t: String, sender: String? = null) = engine.analyze(Input(t, Source.PASTE, sender))

    @Test fun ruleCountInRange() {
        assertTrue(engine.ruleCount in 40..70, "regole: ${engine.ruleCount}")
    }

    @Test fun lookalikeDomainIsFlagged() {
        val v = analyze("Poste: il tuo conto e' limitato, verifica su https://poste-verifica.com/login")
        assertTrue(v.reasons.any { it.ruleId == "URL-01" }, v.reasons.toString())
        assertEquals(Level.PROBABILE_TRUFFA, v.level)
        assertEquals("banca", v.category)
        assertTrue(v.actions.isNotEmpty())
    }

    @Test fun cyrillicHomograph() {
        val v = analyze("Accedi a https://pоste.it/area-riservata per verificare l'account")
        assertTrue(v.reasons.any { it.ruleId == "URL-05" }, v.reasons.toString())
        assertTrue(v.level != Level.NESSUN_SEGNALE)
    }

    @Test fun deceptiveSubdomain() {
        val v = analyze("Aggiorna: https://poste.it.verifica-conto.com/x")
        assertTrue(v.reasons.any { it.ruleId == "URL-02" })
    }

    @Test fun senderMismatch() {
        val v = analyze("Il tuo pacco e' in giacenza: https://consegne-rapide.xyz/a", sender = "Poste")
        assertTrue(v.reasons.any { it.ruleId == "MIS-01" })
    }

    @Test fun officialDomainReducesScore() {
        val withOfficial = analyze("Il tuo estratto conto e' disponibile su https://www.poste.it/area-clienti")
        assertTrue(withOfficial.reasons.any { it.ruleId == "OFF-01" })
        assertEquals(Level.NESSUN_SEGNALE, withOfficial.level)
        assertTrue(withOfficial.actions.any { it.contains("non significa") }, "mai dire «sicuro»")
    }

    @Test fun otpRequestVsWarning() {
        assertTrue(analyze("Comunicaci subito il codice OTP che hai ricevuto").reasons.any { it.ruleId == "BAN-03" })
        assertTrue(analyze("Il tuo codice è 123456. Non comunicare il codice OTP a nessuno.").reasons.none { it.ruleId == "BAN-03" })
    }

    @Test fun neverSafeLevel() {
        assertEquals(setOf("PROBABILE_TRUFFA", "SOSPETTO", "NESSUN_SEGNALE"), Level.entries.map { it.name }.toSet())
        val v = analyze("Ciao, ci vediamo domani alle 18 al bar.")
        assertEquals(Level.NESSUN_SEGNALE, v.level)
        assertNull(v.category)
    }

    @Test fun extractedFieldsPopulated() {
        val v = analyze("Paga 2,50 EUR su IT60X0542811101000000123456 o chiama 899 123456: bit.ly/abc")
        assertEquals(listOf("bit.ly"), v.extracted.domains)
        assertEquals(listOf("IT60X0542811101000000123456"), v.extracted.ibans)
        assertTrue(v.extracted.phones.contains("899123456"))
        assertTrue(v.extracted.amounts.isNotEmpty())
        assertEquals(engine.rulesVersion, v.rulesVersion)
    }

    @Test fun emptyInput() {
        val v = analyze("")
        assertEquals(Level.NESSUN_SEGNALE, v.level)
    }

    // ---- Loader / linter ----
    @Test fun lintRejectsDangerousRegex() {
        for (bad in listOf("(a+)+b", "(a|aa)*c", "(.*)x", ".*foo", "(a)\\1", "(\\w+\\s?)+$", "(?:a*){2,}b")) {
            assertNotNull(RegexLint.check(bad), "doveva essere rifiutata: $bad")
        }
        for (ok in listOf("\\bpacco\\b.{0,40}\\bgiacenza", "(?:a|b){1,3}c", "[a-z]{2,10}\\d+")) {
            assertNull(RegexLint.check(ok), "doveva essere accettata: $ok")
        }
    }

    @Test fun bundledRulesPassLint() {
        for (r in RuleEngine.loadBundled().rules) {
            for (p in r.patterns + listOfNotNull(r.unless)) assertNull(RegexLint.check(p), "${r.id}: $p")
        }
    }

    @Test fun loaderRejectsBadPackage() {
        val bad = """{"version":1,"thresholds":{"probable":55,"suspect":25},"rules":[{"id":"X","category":"c","weight":10,"explanation":"e","patterns":["(a+)+b"]}]}"""
        assertFailsWith<IllegalArgumentException> { RuleLoader.parse(bad) }
        assertFailsWith<IllegalArgumentException> { RuleLoader.parse("non json") }
        val dup = """{"version":1,"thresholds":{"probable":55,"suspect":25},"rules":[{"id":"X","category":"c","weight":10,"explanation":"e","patterns":["a"]},{"id":"X","category":"c","weight":10,"explanation":"e","patterns":["b"]}]}"""
        assertFailsWith<IllegalArgumentException> { RuleLoader.parse(dup) }
    }

    @Test fun customRulesFromJson() {
        val json = """{"version":7,"thresholds":{"probable":55,"suspect":25},"rules":[{"id":"T-1","category":"test","weight":30,"explanation":"trovato","keywords":["banana"]}]}"""
        val v = RuleEngine.fromJson(json).analyze(Input("che bella BANANA", Source.PASTE))
        assertEquals(Level.SOSPETTO, v.level)
        assertEquals(7L, v.rulesVersion)
    }
}
