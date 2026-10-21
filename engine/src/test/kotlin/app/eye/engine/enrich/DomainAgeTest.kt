package app.eye.engine.enrich

import app.eye.engine.Brand
import app.eye.engine.Extracted
import app.eye.engine.Level
import app.eye.engine.Reason
import app.eye.engine.RuleLists
import app.eye.engine.UserExceptions
import app.eye.engine.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DomainAgeTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 10_000 * day
    private fun entry(d: String, daysAgo: Long?) = DomainAgeEntry(d, daysAgo?.let { now - it * day })
    private fun verdict(level: Level, vararg reasons: Reason, domains: List<String> = listOf("promo-x.top")) =
        Verdict(level, 0, reasons.toList(), listOf("azione"), Extracted(domains = domains), null, 1)
    private val signal = Reason("BAN-03", "chiede dati", 30)

    @Test fun strongUnder30DaysRaisesToSuspect() {
        val v = DomainAge.apply(verdict(Level.NESSUN_SEGNALE), listOf(entry("promo-x.top", 5)), now)
        assertEquals(Level.SOSPETTO, v.level)
        assertTrue(v.reasons.single().text.contains("registrato solo 5 giorni fa"))
        assertEquals(DomainAge.STRONG_ID, v.reasons.single().ruleId)
        assertTrue(v.actions.isEmpty(), "azioni vuote: l'app usa quelle del nuovo livello")
    }

    @Test fun strongWithOtherSignalsRaisesToProbable() {
        assertEquals(Level.PROBABILE_TRUFFA, DomainAge.apply(verdict(Level.SOSPETTO, signal), listOf(entry("a.top", 10)), now).level)
        assertEquals(Level.PROBABILE_TRUFFA, DomainAge.apply(verdict(Level.NESSUN_SEGNALE, signal), listOf(entry("a.top", 10)), now).level)
    }

    @Test fun boundariesAt30And180() {
        assertEquals(Level.SOSPETTO, DomainAge.apply(verdict(Level.NESSUN_SEGNALE), listOf(entry("a.top", 29)), now).level)
        // 30 giorni: lieve, da solo solo informativo
        val mild = DomainAge.apply(verdict(Level.NESSUN_SEGNALE), listOf(entry("a.top", 30)), now)
        assertEquals(Level.NESSUN_SEGNALE, mild.level)
        assertEquals(DomainAge.MILD_ID, mild.reasons.single().ruleId)
        assertEquals(10, mild.reasons.single().weight)
        assertEquals(listOf("azione"), mild.actions)
        assertEquals(Level.NESSUN_SEGNALE, DomainAge.apply(verdict(Level.NESSUN_SEGNALE), listOf(entry("a.top", 179)), now).level)
        val v180 = verdict(Level.NESSUN_SEGNALE)
        assertSame(v180, DomainAge.apply(v180, listOf(entry("a.top", 180)), now))
    }

    @Test fun mildWithOtherSignalsRaisesNoSignalToSuspectOnly() {
        assertEquals(Level.SOSPETTO, DomainAge.apply(verdict(Level.NESSUN_SEGNALE, signal), listOf(entry("a.top", 90)), now).level)
        assertEquals(Level.SOSPETTO, DomainAge.apply(verdict(Level.SOSPETTO, signal), listOf(entry("a.top", 90)), now).level)
    }

    @Test fun oldDomainsHaveNoEffectAndUnknownIsIgnored() {
        val v = verdict(Level.SOSPETTO, signal)
        assertSame(v, DomainAge.apply(v, listOf(entry("a.top", 400), entry("b.top", 3000)), now))
        assertSame(v, DomainAge.apply(v, listOf(entry("a.top", null)), now))
        assertSame(v, DomainAge.apply(v, emptyList(), now))
    }

    @Test fun neverLowersAndKeepsProbable() {
        val v = verdict(Level.PROBABILE_TRUFFA, signal)
        for (d in listOf(0L, 10L, 100L, 200L, 5000L)) {
            val r = DomainAge.apply(v, listOf(entry("a.top", d)), now)
            assertEquals(Level.PROBABILE_TRUFFA, r.level)
        }
        assertEquals(listOf("azione"), DomainAge.apply(v, listOf(entry("a.top", 3)), now).actions)
    }

    @Test fun youngestDomainWinsAndTrustedExceptionIsRespected() {
        val v = DomainAge.apply(verdict(Level.NESSUN_SEGNALE), listOf(entry("old.com", 2000), entry("new.top", 3)), now)
        assertEquals(Level.SOSPETTO, v.level)
        assertTrue(v.reasons.single().text.contains("new.top"))
        val trusted = verdict(Level.NESSUN_SEGNALE, Reason(UserExceptions.TRUSTED_ID, "affidabile", 0))
        assertSame(trusted, DomainAge.apply(trusted, listOf(entry("new.top", 1)), now))
        assertSame(v, DomainAge.apply(v, listOf(entry("new.top", 1)), now)) // idempotente
    }

    @Test fun textsHandleSingularAndToday() {
        assertTrue(DomainAge.strongText("x.top", 0).contains("registrato solo oggi"))
        assertTrue(DomainAge.strongText("x.top", 1).contains("1 giorno fa"))
        assertEquals("3 mesi", DomainAge.humanAge(95))
        assertEquals("3 anni", DomainAge.humanAge(1200))
    }

    @Test fun candidatesSkipPopularOfficialShortenersAndIps() {
        val lists = RuleLists(
            popularDomains = listOf("facebook.com"), shorteners = listOf("bit.ly"), trustedDomains = listOf("repubblica.it"),
            brands = listOf(Brand("Poste", "posta", listOf("poste"), emptyList(), listOf("poste.it"))),
        )
        val got = DomainAge.candidates(
            listOf("facebook.com", "bit.ly", "repubblica.it", "poste.it", "1.2.3.4", "Promo-X.top", "promo-x.top", "a.xyz", "b.xyz", "c.xyz"), lists,
        )
        assertEquals(listOf("promo-x.top", "a.xyz", "b.xyz"), got) // max 3, senza duplicati
    }

    @Test fun virusTotalButtonVisibilityAndDomainOnlyUrl() {
        val none = verdict(Level.NESSUN_SEGNALE, domains = emptyList())
        assertTrue(!VirusTotal.isVisible(none))
        val v = verdict(Level.SOSPETTO, domains = listOf("promo-x.top", "9.9.9.9"))
        assertTrue(VirusTotal.isVisible(v))
        assertEquals(listOf("promo-x.top", "9.9.9.9"), VirusTotal.domainsFor(v))
        assertEquals("https://www.virustotal.com/gui/domain/promo-x.top", VirusTotal.urlFor("Promo-X.top"))
        assertEquals("https://www.virustotal.com/gui/ip-address/9.9.9.9", VirusTotal.urlFor("9.9.9.9"))
        assertNull(VirusTotal.urlFor("x.top/path?token=1"))
        assertNull(VirusTotal.urlFor("http://x.top"))
        assertNull(VirusTotal.urlFor("::1"))
    }
}
