package app.eye.android

import app.eye.android.core.links.UrlConnectionShortLinkTransport
import app.eye.engine.ShortLinkProbe
import app.eye.engine.ShortLinkResolver
import app.eye.engine.ShortLinkTransport
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Collections

/** Trasporto reale contro un server HTTP locale finto: HEAD/GET, redirect non seguiti, timeout, destinazione mai contattata. */
class ShortLinkTransportTest {
    private lateinit var server: HttpServer
    private val seen = Collections.synchronizedList(ArrayList<String>())
    private val port get() = server.address.port
    private val transport = UrlConnectionShortLinkTransport(allowCleartext = true)

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            seen += ex.requestMethod + " " + ex.requestURI.path
            val h = ex.responseHeaders
            when (ex.requestURI.path) {
                "/r301" -> { h.add("Location", "http://127.0.0.1:$port/never"); ex.sendResponseHeaders(301, -1) }
                "/rel" -> { h.add("Location", "/next/page"); ex.sendResponseHeaders(302, -1) }
                "/head405" -> if (ex.requestMethod == "HEAD") ex.sendResponseHeaders(405, -1) else { h.add("Location", "https://dest.example.org/"); ex.sendResponseHeaders(307, -1) }
                "/page200" -> ex.sendResponseHeaders(200, -1)
                "/head403" -> if (ex.requestMethod == "HEAD") ex.sendResponseHeaders(403, -1) else { h.add("Location", "https://dest.example.org/403"); ex.sendResponseHeaders(302, -1) }
                "/interstitial" -> {
                    h.add("Content-Type", "text/html")
                    val b = "<html><head><meta http-equiv=\"refresh\" content=\"0;url=https://dest.example.org/meta\"></head></html>".toByteArray()
                    if (ex.requestMethod == "HEAD") ex.sendResponseHeaders(200, -1) else { ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.write(b) }
                }
                "/hugepage" -> {
                    h.add("Content-Type", "text/html")
                    val b = ByteArray(200_000) { 'a'.code.toByte() }
                    if (ex.requestMethod == "HEAD") ex.sendResponseHeaders(200, -1) else { ex.sendResponseHeaders(200, b.size.toLong()); try { ex.responseBody.write(b) } catch (_: java.io.IOException) {} }
                }
                "/slow" -> { Thread.sleep(1500); ex.sendResponseHeaders(301, -1) }
                else -> ex.sendResponseHeaders(404, -1)
            }
            ex.close()
        }
        server.start()
    }

    @After fun stop() = server.stop(0)

    private fun url(p: String) = "http://127.0.0.1:$port$p"

    @Test fun redirectIsReturnedNotFollowed() {
        val r = transport.probe(url("/r301"), 2000) as ShortLinkProbe.Redirect
        assertEquals("http://127.0.0.1:$port/never", r.location)
        assertEquals(listOf("HEAD /r301"), seen) // solo HEAD, e /never mai richiesto
        assertEquals("/next/page", (transport.probe(url("/rel"), 2000) as ShortLinkProbe.Redirect).location)
    }

    @Test fun head405FallsBackToGetWithoutBody() {
        val r = transport.probe(url("/head405"), 2000) as ShortLinkProbe.Redirect
        assertEquals("https://dest.example.org/", r.location)
        assertEquals(listOf("HEAD /head405", "GET /head405"), seen)
    }

    @Test fun intermediatePageTimeoutAndHttpsOnlyInRelease() {
        assertTrue(transport.probe(url("/page200"), 2000) is ShortLinkProbe.Other)
        assertTrue(transport.probe(url("/missing"), 2000) is ShortLinkProbe.Other)
        assertTrue(transport.probe(url("/slow"), 300) is ShortLinkProbe.Failed)
        assertTrue(UrlConnectionShortLinkTransport(false).probe(url("/r301"), 2000) is ShortLinkProbe.Failed)
        assertFalse(seen.any { it.endsWith("/never") })
    }

    @Test fun fullChainThroughResolverNeverTouchesTheFinalSite() {
        // bit.ly e tinyurl.com sono "riscritti" verso il server locale; la destinazione finale (/never) non deve MAI essere richiesta
        val rewrite = ShortLinkTransport { u, t -> transport.probe(u.replace("https://bit.ly", "http://127.0.0.1:$port").replace("https://tinyurl.com", "http://127.0.0.1:$port"), t) }
        val resolver = ShortLinkResolver(rewrite, { setOf("bit.ly", "tinyurl.com") })
        val r = resolver.resolve("guarda https://bit.ly/r301 e https://tinyurl.com/page200")
        assertEquals(mapOf("https://bit.ly/r301" to "http://127.0.0.1:$port/never"), r)
        assertTrue(seen.none { it.endsWith("/never") })
        assertTrue(seen.contains("HEAD /r301") && seen.contains("HEAD /page200"))
    }

    @Test fun head403FallsBackToGet() {
        assertEquals("https://dest.example.org/403", (transport.probe(url("/head403"), 2000) as ShortLinkProbe.Redirect).location)
        assertEquals(listOf("HEAD /head403", "GET /head403"), seen)
    }

    @Test fun intermediatePageMetaRefreshIsReadFromTheShortenerOnly() {
        val r = transport.probe(url("/interstitial"), 2000) as ShortLinkProbe.Redirect
        assertEquals("https://dest.example.org/meta", r.location)
        assertEquals(listOf("HEAD /interstitial", "GET /interstitial"), seen)
    }

    @Test fun pageWithoutHintsIsOtherAndBodyIsCapped() {
        assertTrue(transport.probe(url("/hugepage"), 3000) is ShortLinkProbe.Other)
    }

    @Test fun backgroundNetworkDecision() {
        val enabled = android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        val disabled = android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED
        assertTrue(app.eye.android.core.links.BackgroundNetwork.usable(true, disabled, true))
        assertTrue(app.eye.android.core.links.BackgroundNetwork.usable(true, enabled, false)) // risparmio dati ma rete non a consumo
        assertFalse(app.eye.android.core.links.BackgroundNetwork.usable(true, enabled, true))
        assertFalse(app.eye.android.core.links.BackgroundNetwork.usable(false, disabled, false))
    }

    @Test fun serviceSkipsTheNetworkInBackgroundWhenDenied() {
        var calls = 0
        val resolver = ShortLinkResolver({ _, _ -> calls++; ShortLinkProbe.Redirect("https://x.example.org/") }, { setOf("bit.ly") })
        val denied = app.eye.android.core.links.ShortLinkService(resolver) { false }
        assertTrue(denied.expandBlocking("https://bit.ly/abc", background = true).isEmpty())
        assertEquals(0, calls)
        assertEquals(1, denied.expandBlocking("https://bit.ly/abc", background = false).size) // primo piano: si prova sempre
        assertEquals(1, calls)
    }
}
