package app.eye.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Regressione AUDIT_F1 ri-audit: H-2 (punteggiatura), M-5 (TAB/LF), L-10 (comune.it nudo). */
class UrlEvasionTest {
    private val engine = RuleEngine.default()
    private val head = "Poste Italiane: il tuo conto è sospeso, accedi qui "

    private fun analyze(s: String) = engine.analyze(Input(s, Source.PASTE))
    private fun hasOff(v: Verdict) = v.reasons.any { it.ruleId == "OFF-01" || it.ruleId == "OFF-03" }

    @Test fun glued_punctuation_extracts_evil_domain() {
        for (u in listOf(
            "accedi:evil.top/login www.poste.it", "*evil.top/login* www.poste.it", "->evil.top/login www.poste.it",
            "=evil.top/login www.poste.it", "(evil.top/login) www.poste.it", "\"evil.top/login\" www.poste.it",
            "https:evil.top/login www.poste.it", "https:/evil.top/login www.poste.it", "hxxps:evil.top/login www.poste.it",
            "hxxp://evil[.]top/login www.poste.it", "evil[.]top/login www.poste.it",
        )) {
            val v = analyze(head + u)
            assertTrue("evil.top" in v.extracted.domains, "dominio evil.top non estratto: $u -> ${v.extracted.domains}")
            assertFalse(hasOff(v), "sconto ufficiale con link malevolo: $u")
            assertTrue(v.level != Level.NESSUN_SEGNALE, u)
        }
    }

    @Test fun glued_punctuation_without_official_link() {
        for (u in listOf("accedi:poste-verifica.top/login", "https:poste-verifica.top/login")) {
            val v = analyze(head + u)
            assertTrue("poste-verifica.top" in v.extracted.domains, u)
            assertEquals(Level.PROBABILE_TRUFFA, v.level, u)
        }
    }

    @Test fun tabOrNewlineInsideUrl() {
        for (sep in listOf("\t", "\n", "\r\n")) {
            val v = analyze(head + "https://poste.it" + sep + "@evil.top/x")
            assertTrue("evil.top" in v.extracted.domains, "sep=${sep.length}: ${v.extracted.domains}")
            assertFalse(hasOff(v))
        }
        val v = analyze(head + "https://poste.it\t.evil.top/x")
        assertTrue(v.extracted.domains.any { it.endsWith("evil.top") })
        assertFalse(hasOff(v))
    }

    @Test fun unresolvedOrMalformedLinkNeverDiscounts() {
        // URL scartato come non valido (NBSP, %2f) + link ufficiale
        for (u in listOf("https://evil.top .poste.it/x www.poste.it", "https://evil%2ftop/x www.poste.it")) {
            val v = analyze(head + u)
            assertFalse(hasOff(v), u)
            assertTrue(v.reasons.any { it.ruleId == "URL-10" }, u)
        }
        // frammento simile a dominio non estratto, accanto a un dominio ufficiale
        val v = analyze(head + "www.poste.it oppure evil.top​/x")
        assertFalse(hasOff(v))
    }

    @Test fun cleanOfficialLinkStillDiscounted() {
        val v = analyze(head + "https://www.poste.it/login")
        assertTrue(v.reasons.any { it.ruleId == "OFF-01" })
        assertFalse(v.reasons.any { it.ruleId == "URL-10" })
    }

    @Test fun bareInstitutionalNamesAreNotInstitutional() {
        for (u in listOf("https://comune.it/x", "https://regione.it/x", "https://provincia.it/x")) {
            assertFalse(analyze("Comune: paga ora $u").reasons.any { it.ruleId == "OFF-01" }, u)
        }
        assertTrue(analyze("Comune: paga ora https://www.comune.bologna.it/x").reasons.any { it.ruleId == "OFF-01" })
    }

    @Test fun invisibleCharsInsideDomain() {
        for (c in listOf('­', '​', '‍', '﻿', '⁠')) {
            val v = analyze("${head}evil${c}.top/login www.poste.it")
            assertTrue("evil.top" in v.extracted.domains, "char ${c.code}: ${v.extracted.domains}")
            assertFalse(hasOff(v), "char ${c.code}: sconto ufficiale applicato")
            assertEquals(Level.PROBABILE_TRUFFA, v.level, "char ${c.code}")
        }
        assertEquals("evil.top", UrlInfo.registrableDomain("https://evil­.top/login"))
    }
}
