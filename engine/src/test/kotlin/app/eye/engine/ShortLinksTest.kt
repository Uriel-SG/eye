package app.eye.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Risolutore dei link accorciati (logica pura, trasporto finto) e integrazione nel motore. */
class ShortLinksTest {
    private val shorteners = setOf("bit.ly", "tinyurl.com", "t.ly")

    /** Trasporto finto: mappa url -> risposta; registra TUTTE le richieste (host contattati). */
    private class Fake(val routes: Map<String, ShortLinkProbe>) : ShortLinkTransport {
        val requests = ArrayList<String>()
        val timeouts = ArrayList<Int>()
        override fun probe(url: String, timeoutMs: Int): ShortLinkProbe {
            requests += url; timeouts += timeoutMs
            return routes[url] ?: ShortLinkProbe.Failed
        }
    }

    private fun redirect(to: String?) = ShortLinkProbe.Redirect(to)
    private fun resolver(t: ShortLinkTransport, now: () -> Long = { 0 }, nano: () -> Long = System::nanoTime) =
        ShortLinkResolver(t, { shorteners }, clockMs = now, nanoClock = nano)

    @Test fun singleRedirectReturnsDestinationAndNeverContactsIt() {
        val t = Fake(mapOf("https://bit.ly/abc" to redirect("https://www.sito-vero.it/pagina?x=1")))
        val r = resolver(t).resolve("Guarda qui https://bit.ly/abc grazie")
        assertEquals(mapOf("https://bit.ly/abc" to "https://www.sito-vero.it/pagina?x=1"), r)
        assertEquals(listOf("https://bit.ly/abc"), t.requests) // la destinazione finale non e' stata contattata
    }

    @Test fun chainBetweenShortenersThenStops() {
        val t = Fake(mapOf(
            "https://bit.ly/a" to redirect("https://tinyurl.com/b"),
            "https://tinyurl.com/b" to redirect("https://t.ly/c"),
            "https://t.ly/c" to redirect("https://destinazione.example.org/x"),
        ))
        assertEquals("https://destinazione.example.org/x", resolver(t).resolve("bit.ly/a")["bit.ly/a"])
        assertEquals(listOf("https://bit.ly/a", "https://tinyurl.com/b", "https://t.ly/c"), t.requests)
        assertTrue(t.requests.none { "destinazione" in it })
    }

    @Test fun moreThanThreeHopsIsUnresolved() {
        val t = Fake(mapOf(
            "https://bit.ly/a" to redirect("https://tinyurl.com/b"),
            "https://tinyurl.com/b" to redirect("https://t.ly/c"),
            "https://t.ly/c" to redirect("https://bit.ly/d"),
            "https://bit.ly/d" to redirect("https://fine.example.org/"),
        ))
        assertTrue(resolver(t).resolve("https://bit.ly/a").isEmpty())
        assertEquals(3, t.requests.size)
    }

    @Test fun relativeLocationStaysOnTheShortener() {
        val t = Fake(mapOf(
            "https://bit.ly/a" to redirect("/real/path"),
            "https://bit.ly/real/path" to redirect("https://sito.example.com/"),
        ))
        assertEquals("https://sito.example.com/", resolver(t).resolve("https://bit.ly/a")["https://bit.ly/a"])
        assertEquals(listOf("https://bit.ly/a", "https://bit.ly/real/path"), t.requests)
    }

    @Test fun loopIsUnresolved() {
        val t = Fake(mapOf("https://bit.ly/a" to redirect("https://tinyurl.com/b"), "https://tinyurl.com/b" to redirect("https://bit.ly/a")))
        assertTrue(resolver(t).resolve("https://bit.ly/a").isEmpty())
        assertEquals(2, t.requests.size)
    }

    @Test fun intermediatePageErrorsTimeoutsAndBadLocationAreUnresolved() {
        val t = Fake(mapOf(
            "https://bit.ly/ok200" to ShortLinkProbe.Other,
            "https://bit.ly/nolocation" to redirect(null),
            "https://bit.ly/bad" to redirect("javascript:alert(1)"),
            "https://bit.ly/ftp" to redirect("ftp://x.example.org/f"),
        ))
        val r = resolver(t)
        for (p in listOf("ok200", "nolocation", "bad", "ftp", "timeout")) assertTrue(r.resolve("https://bit.ly/$p").isEmpty(), p)
    }

    @Test fun httpUpgradesToHttpsAndFallsBackOnlyForShorteners() {
        val t = Fake(mapOf("http://bit.ly/a" to redirect("https://sito.example.com/")))
        assertEquals("https://sito.example.com/", resolver(t).resolve("http://bit.ly/a")["http://bit.ly/a"])
        assertEquals(listOf("https://bit.ly/a", "http://bit.ly/a"), t.requests) // https non regge: solo allora http, verso lo shortener
        val t2 = Fake(mapOf("https://bit.ly/a" to redirect("http://www.sito.example.com/")))
        assertEquals("http://www.sito.example.com/", resolver(t2).resolve("http://bit.ly/a")["http://bit.ly/a"])
        assertEquals(listOf("https://bit.ly/a"), t2.requests)
    }

    @Test fun neverContactsNonShortenerEvenIfLocationPointsThere() {
        val t = Fake(mapOf("https://bit.ly/a" to redirect("https://malizioso.example.net/steal")))
        resolver(t).resolve("https://bit.ly/a")
        assertTrue(t.requests.all { it.startsWith("https://bit.ly/") })
        // e un link NON shortener nel testo non viene proprio richiesto
        val t3 = Fake(emptyMap())
        assertTrue(resolver(t3).resolve("vai su https://sito-normale.example.com/x e https://maps.app.goo.gl/abc").isEmpty())
        assertTrue(t3.requests.isEmpty())
    }

    @Test fun maxThreeLinksPerMessageAndBudget() {
        val routes = (1..5).associate { "https://bit.ly/$it" to redirect("https://d$it.example.org/") }
        val t = Fake(routes)
        val r = resolver(t).resolve((1..5).joinToString(" ") { "https://bit.ly/$it" })
        assertEquals(3, r.size)
        assertEquals(3, t.requests.size)
        assertTrue(t.timeouts.all { it <= 2500 })
    }

    @Test fun totalBudgetStopsFurtherRequests() {
        var nano = 0L
        val routes = (1..3).associate { "https://bit.ly/$it" to redirect("https://d$it.example.org/") }
        val t = object : ShortLinkTransport {
            val n = ArrayList<String>()
            override fun probe(url: String, timeoutMs: Int): ShortLinkProbe { n += url; nano += 2_000_000_000L; return routes[url]!! }
        }
        val r = resolver(t, nano = { nano }).resolve("https://bit.ly/1 https://bit.ly/2 https://bit.ly/3")
        assertEquals(2, t.n.size) // 4 s totali: il terzo non parte (e il secondo ha solo il tempo residuo)
        assertEquals(2, r.size)
    }

    @Test fun cacheServesRepeatedLinksFor24HoursOnly() {
        var now = 0L
        val t = Fake(mapOf("https://bit.ly/a" to redirect("https://sito.example.com/")))
        val r = resolver(t, now = { now })
        r.resolve("https://bit.ly/a"); r.resolve("https://bit.ly/a")
        assertEquals(1, t.requests.size)
        now = 24 * 3600_000L + 1
        r.resolve("https://bit.ly/a")
        assertEquals(2, t.requests.size)
    }

    // ---- motore

    private val engine = RuleEngine.default()
    private fun v(text: String, resolved: Map<String, String> = emptyMap(), origin: String? = null) =
        engine.analyze(Input(text, Source.SHARE, origin = origin, resolvedLinks = resolved))

    @Test fun candidatesMatchEngineKeys() {
        val text = "Apri bit.ly/Xy12 o https://tinyurl.com/q ma non https://www.example.com/a"
        val c = ShortLinks.candidates(text, shorteners)
        assertEquals(2, c.size)
        val verdict = v(text, c.associateWith { "https://sito-normale.it/pagina" })
        assertTrue(verdict.reasons.any { it.ruleId == "URL-11" })
        assertTrue(verdict.reasons.none { it.ruleId == "URL-04" })
    }

    @Test fun unresolvedBehavesAsToday() {
        val verdict = v("Il tuo pacco: https://bit.ly/3xYz", origin = "com.google.android.apps.messaging")
        assertTrue(verdict.reasons.any { it.ruleId == "URL-04" })
        assertTrue(AlertPolicy.shouldAlert(verdict))
        assertEquals(verdict, v("Il tuo pacco: https://bit.ly/3xYz", mapOf("https://bit.ly/altro" to "https://www.zalando.it/x"), "com.google.android.apps.messaging"))
    }

    @Test fun popularCleanDestinationDoesNotCountNorAlert() {
        val verdict = v("Il tuo ordine: https://bit.ly/3xYz", mapOf("https://bit.ly/3xYz" to "https://www.zalando.it/ordini/123"), "com.google.android.apps.messaging")
        assertEquals(Level.NESSUN_SEGNALE, verdict.level)
        assertTrue(verdict.reasons.none { it.ruleId == "URL-04" || it.ruleId == "URL-12" })
        val info = verdict.reasons.single { it.ruleId == "URL-11" }
        assertTrue("bit.ly" in info.text && "www.zalando.it" in info.text, info.text)
        assertFalse(AlertPolicy.shouldAlert(verdict))
        assertTrue("zalando.it" in verdict.extracted.domains) // l'eta' del dominio guardera' anche la destinazione
    }

    @Test fun unknownCleanDestinationHasReducedWeightAndIsNotStrong() {
        val verdict = v("Menu della settimana: https://bit.ly/pizza", mapOf("https://bit.ly/pizza" to "https://www.pizzeria-da-mario.it/menu"), "com.whatsapp")
        assertEquals(Level.NESSUN_SEGNALE, verdict.level)
        assertTrue(verdict.reasons.any { it.ruleId == "URL-12" && it.weight == 10 })
        assertFalse("URL-12" in AlertPolicy.STRONG_RULE_IDS || "URL-11" in AlertPolicy.STRONG_RULE_IDS)
        assertFalse(AlertPolicy.shouldAlert(verdict))
    }

    @Test fun maliciousDestinationIsAnalyzedWithAllRules() {
        val bl = RuleEngine(RuleEngine.loadBundled(), reputation = HashBlocklist.of(listOf("evil-site.com")))
        val a = bl.analyze(Input("Guarda https://bit.ly/x", Source.SHARE, resolvedLinks = mapOf("https://bit.ly/x" to "https://login.evil-site.com/p")))
        assertEquals(Level.PROBABILE_TRUFFA, a.level)
        assertTrue(a.reasons.any { it.ruleId == "BLK-01" })
        val b = engine.analyze(Input("Accedi: https://bit.ly/x", Source.SHARE, resolvedLinks = mapOf("https://bit.ly/x" to "https://poste-verifica.com/login")))
        assertTrue(b.reasons.any { it.ruleId == "URL-01" }, b.reasons.toString())
        assertEquals(Level.PROBABILE_TRUFFA, b.level)
        val c = engine.analyze(Input("Scarica https://bit.ly/x", Source.SHARE, resolvedLinks = mapOf("https://bit.ly/x" to "http://sito.example.com/app.apk")))
        assertTrue(c.reasons.any { it.ruleId == "URL-09" })
    }

    @Test fun groupInviteDestinationIsNotTreatedAsKnown() {
        val verdict = v("Entra https://bit.ly/g", mapOf("https://bit.ly/g" to "https://t.me/+AbCdEf"))
        assertTrue(verdict.reasons.any { it.ruleId == "URL-12" })
    }

    @Test fun destinationThatIsAnotherShortenerOrJunkIsIgnored() {
        val a = v("Apri https://bit.ly/x", mapOf("https://bit.ly/x" to "https://tinyurl.com/y"))
        assertTrue(a.reasons.any { it.ruleId == "URL-04" })
        val b = v("Apri https://bit.ly/x", mapOf("https://bit.ly/x" to "javascript:alert(1)"))
        assertTrue(b.reasons.any { it.ruleId == "URL-04" })
    }
}
