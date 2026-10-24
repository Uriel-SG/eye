package app.eye.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlertPolicyTest {
    private fun v(level: Level, vararg ids: String, vectors: Set<ActionVector> = setOf(ActionVector.LINK)) =
        Verdict(level, 0, ids.map { Reason(it, "", 0) }, emptyList(), Extracted(), null, 1, vectors, AlertPolicy.tierFor(level, ids.toList(), vectors))

    private val rules = RuleEngine.loadBundled()
    private val ids = rules.rules.map { it.id }.toSet()

    @Test fun probableAlwaysAlerts() {
        assertTrue(AlertPolicy.shouldAlert(v(Level.PROBABILE_TRUFFA, "URG-05", "COM-01")))
    }

    @Test fun suspectNeedsAStrongSignalAndAFullActionVector() {
        for (weak in listOf(emptySet(), setOf(ActionVector.LINK_OFFICIAL), setOf(ActionVector.LINK_BARE)))
            assertFalse(AlertPolicy.shouldAlert(v(Level.SOSPETTO, "URL-01", vectors = weak)), weak.toString())
        for (strong in ActionVector.entries.filter { it != ActionVector.LINK_OFFICIAL && it != ActionVector.LINK_BARE })
            assertTrue(AlertPolicy.shouldAlert(v(Level.SOSPETTO, "BAN-01", vectors = setOf(strong))), strong.toString())
        assertFalse(AlertPolicy.shouldAlert(v(Level.PROBABILE_TRUFFA, "BAN-01", vectors = emptySet())))
        assertEquals(AlertTier.QUIET, AlertPolicy.tierFor(Level.SOSPETTO, listOf("URG-05"), setOf(ActionVector.LINK)))
        assertEquals(AlertTier.ALERT, AlertPolicy.tierFor(Level.SOSPETTO, listOf("INC-01"), emptySet()))
    }

    @Test fun noSignalNeverAlerts() {
        assertFalse(AlertPolicy.shouldAlert(v(Level.NESSUN_SEGNALE, "URL-01")))
    }

    @Test fun suspectAlertsOnlyWithStrongSignal() {
        for (id in listOf("URL-01", "URL-03", "URL-04", "URL-07", "MIS-01", "BAN-03", "CRD-02", "IBN-01", "LAV-01", "FAM-02"))
            assertTrue(AlertPolicy.shouldAlert(v(Level.SOSPETTO, "URG-05", id)), id)
        for (ids in listOf(listOf("URG-05", "URG-01"), listOf("COM-01", "COM-02"), listOf("SHP-01", "URG-05"), listOf("ENE-01"), listOf("FOR-01", "GEN-01"), listOf("AI-01")))
            assertFalse(AlertPolicy.shouldAlert(v(Level.SOSPETTO, *ids.toTypedArray())), ids.toString())
        assertFalse(AlertPolicy.shouldAlert(v(Level.SOSPETTO)))
    }

    @Test fun strongSetCoversHardRulesAndOnlyKnownRules() {
        val missing = rules.ai.hardRuleIds - AlertPolicy.STRONG_RULE_IDS
        assertTrue(missing.isEmpty(), "regole gravi (ai.hardRuleIds) non coperte dalla politica avvisi: $missing")
        val unknown = AlertPolicy.STRONG_RULE_IDS - ids
        assertTrue(unknown.isEmpty(), "id inesistenti in rules.json: $unknown")
    }

    @Test fun contextRulesAreNeverStrong() {
        val context = AlertPolicy.STRONG_RULE_IDS.filter { it.startsWith("URG-") || it.startsWith("COM-") || it.startsWith("FOR-") || it.startsWith("GEN-") || it.startsWith("OFF-") || it.startsWith("AI-") }
        assertEquals(emptyList(), context)
    }

    private val pkg = "com.whatsapp"

    @Test fun aiNeverRaisesNotificationsEvenWithHighP() {
        val e = RuleEngine(rules, classifier = { 0.99f })
        for (t in listOf("Mario\nok", "WhatsApp\n1 nuovo messaggio", "Mario\nBuongiorno, abbiamo una proposta di lavoro per lei, mi risponda appena possibile e senza impegno."))
            assertEquals(Level.NESSUN_SEGNALE, e.analyze(Input(t, Source.SHARE, declaredSender = t.substringBefore('\n'), origin = pkg)).level, t)
    }

    @Test fun aiStillRaisesForegroundPaste() {
        val e = RuleEngine(rules, classifier = { 0.99f })
        assertEquals(Level.SOSPETTO, e.analyze(Input("Mario\nok 347 123 4567", Source.PASTE)).level)
        // nessuna azione richiesta: il modello da solo non basta
        assertEquals(Level.NESSUN_SEGNALE, e.analyze(Input("Mario\nok", Source.PASTE)).level)
    }

    @Test fun googleShareShortLinksAreNotShorteners() {
        val e = RuleEngine(rules)
        for (t in listOf("Ci vediamo qui https://maps.app.goo.gl/AbCdEf123", "Foto https://photos.app.goo.gl/AbCdEf123", "Dove: https://goo.gl/maps/AbCdEfGh123"))
            assertFalse(e.analyze(Input(t, Source.SHARE, origin = pkg)).reasons.any { it.ruleId == "URL-04" }, t)
        assertTrue(e.analyze(Input("Guarda https://goo.gl/AbCdEf", Source.SHARE, origin = pkg)).reasons.any { it.ruleId == "URL-04" })
        assertTrue(AlertPolicy.shouldAlert(e.analyze(Input("Guarda https://bit.ly/3AbCdEf", Source.SHARE, origin = pkg))))
    }
}
