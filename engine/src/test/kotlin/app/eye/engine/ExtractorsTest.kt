package app.eye.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtractorsTest {
    private fun urls(t: String) = Extractors.urls(t)

    @Test fun urlWithScheme() {
        val u = urls("Vai su https://www.poste-verifica.com/login?x=1, grazie").single()
        assertEquals("www.poste-verifica.com", u.host)
        assertEquals("poste-verifica.com", u.registrable)
        assertTrue(u.hasScheme)
    }

    @Test fun urlWithoutScheme() {
        assertEquals("bit.ly", urls("clicca bit.ly/3xKp9Qa ora").single().registrable)
        assertEquals("gls-ritiro-pacchi.top", urls("conferma qui 👉 gls-ritiro-pacchi.top/conferma").single().registrable)
    }

    @Test fun noFalsePositiveUrls() {
        assertTrue(urls("Ore 10.30 circa, S.p.A. ecc.Ciao e-mail: info@poste.it").isEmpty())
        assertTrue(urls("Totale 12.50 euro").isEmpty())
    }

    @Test fun obfuscatedUrl() {
        assertEquals("poste-sicurezza.com", urls("hxxps://poste-sicurezza[.]com/a").single().registrable)
    }

    @Test fun multiLevelSuffix() {
        assertEquals("agenziaentrate.gov.it", Extractors.parse("https://rimborsi.agenziaentrate.gov.it/x")!!.registrable)
        assertEquals("sito.co.uk", Extractors.parse("http://www.sito.co.uk")!!.registrable)
        assertEquals("evil.github.io", Extractors.parse("https://evil.github.io/p")!!.registrable)
        assertEquals("esempio.it", Extractors.parse("https://a.b.esempio.it")!!.registrable)
    }

    @Test fun ipAndUserinfo() {
        val ip = Extractors.parse("http://185.23.4.5/login")!!
        assertTrue(ip.isIp)
        val ui = Extractors.parse("https://poste.it@evil.com/x")!!
        assertTrue(ui.hasUserInfo)
        assertEquals("evil.com", ui.registrable)
    }

    @Test fun punycodeAndCyrillic() {
        val u = Extractors.parse("https://pоste.it/login")!! // о cirillica
        assertTrue(u.isPunycode)
        assertTrue(Text.hasNonAsciiLetters(u.hostUnicode))
        assertTrue(u.registrable.startsWith("xn--"))
        assertEquals(Text.skeleton("poste"), Text.skeleton(u.mainLabelUnicode))
    }

    @Test fun nonHttpSchemesIgnored() {
        assertNull(Extractors.parse("javascript:alert(1)"))
        assertNull(Extractors.parse("intent://evil#Intent;end"))
    }

    @Test fun ibanMod97() {
        assertTrue(Extractors.ibanValid("IT60X0542811101000000123456"))
        assertFalse(Extractors.ibanValid("IT60X0542811101000000123457"))
        assertFalse(Extractors.ibanValid("IT60X05428111010000001234"))
        val r = Extractors.ibansWithRanges("Bonifico a IT60 X054 2811 1010 0000 0123 456 intestato a Mario")
        assertEquals("IT60X0542811101000000123456", r.single().first)
        assertTrue(Extractors.ibansWithRanges("IT60X0542811101000000123457").isEmpty())
    }

    @Test fun phones() {
        val p = Extractors.phones("Chiama 347 1234567 o +39 02 1234567 o il 899 123456, oppure +44 7700 900123")
        assertTrue("3471234567" in p)
        assertTrue(p.any { it.startsWith("+39") })
        assertTrue("899123456" in p)
        assertTrue(p.any { it.startsWith("+44") })
    }

    @Test fun amounts() {
        val a = Extractors.amounts("Paga 1,99 EUR o € 1.250,00 oppure 3 euro")
        assertTrue(a.size >= 3, a.toString())
        assertEquals(1250.0, Extractors.amountValue("€ 1.250,00"))
    }

    @Test fun truncationAt10000() {
        val text = "a".repeat(9_990) + " " + "x".repeat(20) + " https://tardi.example.com"
        val v = RuleEngine.default().analyze(Input(text, Source.PASTE))
        assertTrue(v.extracted.urls.isEmpty(), "l'URL oltre 10.000 caratteri deve essere ignorato")
        assertEquals(10_000, "b".repeat(10_001).take(MAX_INPUT_CHARS).length)
    }

    @Test fun normalizationFoldsConfusablesAndInvisibles() {
        assertEquals("poste", Text.normalizeForRules("P​оste"))
        assertNotNull(Text.skeleton("paypa1"))
        assertEquals(Text.skeleton("paypal"), Text.skeleton("paypa1"))
        assertEquals(Text.skeleton("poste"), Text.skeleton("p0ste"))
    }
}

class UrlParsingSecurityTest {
    private fun reg(u: String) = UrlInfo.registrableDomain(u)

    @Test fun backslashIsPathSeparator() {
        assertEquals("evil.top", reg("https://evil.top\\.poste.it/login"))
        assertEquals("evil.top", reg("https://evil.top\\@poste.it/"))
        assertEquals("evil.top", reg("https:\\\\evil.top\\x"))
        assertEquals("evil.top", reg("evil.top\\.poste.it/login"))
        assertEquals("evil.top", Extractors.urls("apri https://evil.top\\.poste.it/login ora").single().registrable)
    }

    @Test fun userInfo() {
        assertEquals("evil.top", reg("https://poste.it@evil.top/x"))
        assertTrue(UrlInfo.hasUserInfo("https://poste.it@evil.top"))
        assertEquals("evil.top", reg("https://a@poste.it@evil.top"))
        assertFalse(UrlInfo.hasUserInfo("https://poste.it/x"))
    }

    @Test fun percentEncodedHost() {
        assertEquals("www.poste.it", UrlInfo.host("https://www.poste%2eit/x"))
        assertEquals("poste.it", reg("https://poste%2Eit"))
        assertNull(reg("https://poste.it%2f.evil.top"))
        assertNull(UrlInfo.host("https://evil.top%/x"))
        assertNull(UrlInfo.host("https://a%zz.it"))
        assertNull(UrlInfo.host("https://poste.it%40evil.top/"))
    }

    @Test fun invalidHostsRejected() {
        assertNull(UrlInfo.host("https://poste.it evil.top/"))
        assertNull(UrlInfo.host("https://pos<te.it/"))
        assertNull(UrlInfo.host("https://a..b.it/"))
        assertNull(UrlInfo.host("https://.poste.it/"))
        assertNull(UrlInfo.host("https://poste.it:evil/"))
        assertNull(UrlInfo.host("https://poste.it:99999/"))
        assertNull(UrlInfo.host("javascript://poste.it"))
    }

    @Test fun portsCaseDotsIdn() {
        assertEquals("poste.it", UrlInfo.host("https://poste.it:8443/x"))
        assertEquals("poste.it", UrlInfo.host("https://POSTE.IT:/x"))
        assertEquals("www.poste.it", UrlInfo.host("HTTPS://WWW.Poste.IT./x"))
        assertEquals("evil.top", reg("https://poste.it:443@EVIL.top:80"))
        assertEquals("poste.it", reg("https://www.poste.it?x=https://evil.top"))
        assertTrue(UrlInfo.host("https://póst.it/")!!.startsWith("xn--"))
        assertEquals("poste.it", UrlInfo.host("https://poste\u3002it/"))
        assertEquals("evil.top", UrlInfo.host("  https://evil.top  "))
        assertEquals("1.2.3.4", UrlInfo.host("http://1.2.3.4:80/"))
    }

    @Test fun backslashDoesNotFoolDetectors() {
        val e = RuleEngine.default()
        val v = e.analyze(Input("Poste: verifica ora https://evil.top\\.poste.it/login", Source.PASTE))
        assertEquals("evil.top", v.extracted.domains.single())
    }
}
