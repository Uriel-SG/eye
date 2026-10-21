package app.eye.engine.enrich

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RdapTest {
    private fun fx(n: String) = javaClass.getResource("/rdap/$n")!!.readText()
    private fun ms(iso: String) = java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    @Test fun verisignComRegistration() {
        assertEquals(RdapParse.Registered(ms("1995-08-14T04:00:00Z")), Rdap.parseRegistration(fx("verisign_com.json")))
    }

    @Test fun ccTldWithFractionalSeconds() {
        assertEquals(RdapParse.Registered(ms("1994-12-13T03:49:48Z")), Rdap.parseRegistration(fx("nominet_uk.json")))
    }

    @Test fun responseWithoutRegistrationEvent() {
        assertEquals(RdapParse.NoRegistration, Rdap.parseRegistration(fx("denic_de_no_registration.json")))
        assertEquals(RdapParse.NoRegistration, Rdap.parseRegistration("""{"objectClassName":"domain"}"""))
    }

    @Test fun errorsAndGarbageAreInvalid() {
        assertEquals(RdapParse.Invalid, Rdap.parseRegistration(fx("error_404.json")))
        assertEquals(RdapParse.Invalid, Rdap.parseRegistration(""))
        assertEquals(RdapParse.Invalid, Rdap.parseRegistration("<html>"))
        assertEquals(RdapParse.Invalid, Rdap.parseRegistration("[1,2]"))
    }

    @Test fun tolerantToMalformedEvents() {
        val body = """{"events":[1,{"eventAction":"registration"},{"eventAction":"registration","eventDate":"boh"},
            {"eventAction":"Registration","eventDate":"2024-01-02T00:00:00+01:00"},{"eventAction":"registration","eventDate":"2025-01-01T00:00:00Z"}]}"""
        assertEquals(RdapParse.Registered(ms("2024-01-02T00:00:00+01:00")), Rdap.parseRegistration(body)) // il piu' vecchio valido
    }

    @Test fun bootstrapLongestSuffixMatchWins() {
        val b = assertNotNull(RdapBootstrap.parse(fx("bootstrap_sample.json")))
        assertEquals("https://rdap.verisign.com/com/v1/", b.serviceFor("promo-x.com"))
        assertEquals("https://rdap.nominet.uk/uk/", b.serviceFor("promo-x.uk"))
        assertEquals("https://rdap.example-multi.test/couk/", b.serviceFor("promo-x.co.uk")) // co.uk piu' lungo di uk
        assertEquals("https://rdap.verisign.com/com/v1/domain/promo-x.com", b.queryUrl("Promo-X.com"))
        assertNull(b.serviceFor("poste-verifica.it")) // .it senza RDAP: eta' non disponibile
        assertNull(b.queryUrl("poste-verifica.it"))
    }

    @Test fun bootstrapUpgradesHttpToHttpsAndRejectsBadInput() {
        val b = assertNotNull(RdapBootstrap.parse(fx("bootstrap_sample.json")))
        assertEquals("https://rdap.cctld.kg/", b.serviceFor("sito.kg")) // l'elenco IANA ha http://
        assertNull(b.serviceFor("com"))
        assertNull(b.serviceFor("a.com/../../x"))
        assertNull(b.serviceFor("a b.com"))
        assertNull(RdapBootstrap.parse("{}"))
        assertNull(RdapBootstrap.parse("non json"))
        assertTrue(RdapBootstrap.isValidDomain("xn--e1afmkfd.com"))
    }
}
