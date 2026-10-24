package app.eye.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pagine intermedie degli accorciatori e redirect verso il sito del servizio (link «non trovato»). */
class ShortLinkPagesTest {
    @Test fun metaRefreshIsFollowedAsRedirect() {
        val html = """<html><head><meta http-equiv="refresh" content="0; url=https://destinazione.example.org/a?b=1&amp;c=2"></head></html>"""
        assertEquals("https://destinazione.example.org/a?b=1&c=2", ShortLinks.pageRedirect(html, "https://t.ly/abc"))
        assertEquals("https://t.ly/next", ShortLinks.pageRedirect("<META HTTP-EQUIV='Refresh' CONTENT='3;URL=/next'>", "https://t.ly/abc"))
    }

    @Test fun canonicalCountsOnlyIfItPointsToAnotherHost() {
        assertEquals("https://sito.example.org/x", ShortLinks.pageRedirect("""<link rel="canonical" href="https://sito.example.org/x">""", "https://cutt.ly/q"))
        assertNull(ShortLinks.pageRedirect("""<link rel="canonical" href="https://cutt.ly/q">""", "https://cutt.ly/q"))
    }

    @Test fun unsafeOrMissingTargetsAreIgnored() {
        assertNull(ShortLinks.pageRedirect("<html>niente</html>", "https://t.ly/abc"))
        assertNull(ShortLinks.pageRedirect("""<meta http-equiv="refresh" content="0;url=javascript:alert(1)">""", "https://t.ly/abc"))
        assertNull(ShortLinks.pageRedirect("""<meta http-equiv="refresh" content="5">""", "https://t.ly/abc"))
    }

    private class Fake(val routes: Map<String, ShortLinkProbe>) : ShortLinkTransport {
        override fun probe(url: String, timeoutMs: Int) = routes[url] ?: ShortLinkProbe.Failed
    }

    @Test fun redirectToTheServicesOwnSiteIsNotADestination() {
        val sh = setOf("ow.ly", "buff.ly", "bit.ly")
        val t = Fake(mapOf(
            "https://ow.ly/abc" to ShortLinkProbe.Redirect("https://www.hootsuite.com/error/lost-owly"),
            "https://buff.ly/3x" to ShortLinkProbe.Redirect("https://buffer.com/"),
            "https://bit.ly/ok" to ShortLinkProbe.Redirect("https://www.sito.example.org/p"),
        ))
        val r = ShortLinkResolver(t, { sh }).resolve("ow.ly/abc buff.ly/3x bit.ly/ok")
        assertEquals(mapOf("bit.ly/ok" to "https://www.sito.example.org/p"), r)
    }
}
