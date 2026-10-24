package app.eye.engine

import java.net.URI

/**
 * Espansione dei link accorciati. Logica pura: la rete sta in [ShortLinkTransport] (implementato da :app).
 * Regola di sicurezza: si contattano SOLO host della lista shortener; la destinazione finale non si contatta MAI.
 */
object ShortLinks {
    const val MAX_LINKS = 3
    const val MAX_HOPS = 3

    /** Link accorciati del testo (forma come la vede il motore, [ParsedUrl.raw]), al massimo [max]. Stessi link usati poi da [RuleEngine]. */
    fun candidates(text: String, shorteners: Set<String>, max: Int = MAX_LINKS): List<String> {
        val raw = Text.collapseRepeats(text.take(MAX_INPUT_CHARS))
        return Extractors.scan(stripBare(Text.stripInvisible(raw), bareRegex(shorteners))).urls
            .filter { isShortened(it, shorteners) }
            .map { it.raw }.distinct().take(max)
    }

    /**
     * Il solo nome di un servizio di accorciamento («ho messo il link su bit.ly», «https://tinyurl.com») NON e' un link:
     * senza percorso non porta da nessuna parte. Regex che lo riconosce (anche con schema/www e «/» finale).
     */
    internal fun bareRegex(shorteners: Set<String>): Regex? {
        if (shorteners.isEmpty()) return null
        val alt = shorteners.joinToString("|") { Regex.escape(it) }
        return Regex("""(?<![\p{L}\p{N}@._/%-])(?:https?://)?(?:www\.)?(?:$alt)/?+(?![\p{L}\p{N}_%~-]|[?#=&]\S|\.[\p{L}\p{N}]|:\d)""", RegexOption.IGNORE_CASE)
    }

    /** Toglie dal testo da analizzare i nomi di shortener senza percorso (vedi [bareRegex]). */
    internal fun stripBare(text: String, re: Regex?): String = if (re == null) text else re.replace(text, " ")

    internal fun isShortened(u: ParsedUrl, shorteners: Set<String>) =
        !u.isIp && u.registrable in shorteners && !u.isGoogleShareShortLink

    /**
     * Siti del servizio stesso (home, pagine di errore «link non trovato»): se un servizio rimanda li', il link NON e' risolto
     * (es. ow.ly sconosciuto -> hootsuite.com/error/lost-owly, buff.ly -> buffer.com). Non sono destinazioni.
     */
    private val SERVICE_SITES: Map<String, Set<String>> = mapOf(
        "ow.ly" to setOf("hootsuite.com"), "buff.ly" to setOf("buffer.com"), "bit.ly" to setOf("bitly.com", "bitly.is"),
        "rebrand.ly" to setOf("rebrandly.com"), "t.co" to setOf("twitter.com", "x.com"), "cutt.ly" to setOf("cutt.ly"),
        "t.ly" to setOf("t.ly"), "shorturl.at" to setOf("shorturl.at"), "rb.gy" to setOf("rb.gy"), "tiny.cc" to setOf("tiny.cc"),
        "is.gd" to setOf("is.gd"), "v.gd" to setOf("v.gd"), "tinyurl.com" to setOf("tinyurl.com"),
    )

    internal fun isServiceSite(shortener: String, destination: String) = SERVICE_SITES[shortener]?.contains(destination) == true

    private val metaRefresh = Regex("""<meta\s[^>]*http-equiv\s*=\s*["']?refresh["']?[^>]*>""", RegexOption.IGNORE_CASE)
    private val metaUrl = Regex("""url\s*=\s*["']?([^"'>\s;]+)""", RegexOption.IGNORE_CASE)
    private val canonicalTag = Regex("""<link\s[^>]*rel\s*=\s*["']canonical["'][^>]*>""", RegexOption.IGNORE_CASE)
    private val hrefAttr = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    /**
     * Pagina intermedia di un accorciatore (200 con HTML, i primi 32 KB al massimo): cerca un `<meta http-equiv="refresh">`
     * e, in mancanza, un `<link rel="canonical">` verso un ALTRO host. Restituisce l'URL assoluto http(s) o null.
     * Solo testo gia' scaricato dal servizio: non contatta nulla.
     */
    fun pageRedirect(html: String, baseUrl: String): String? {
        val head = html.take(MAX_PAGE_BYTES)
        val base = try { URI(baseUrl) } catch (_: Exception) { return null }
        fun abs(loc: String): String? = try {
            val u = base.resolve(URI(loc.trim().replace("&amp;", "&")))
            u.toString().takeIf { u.isAbsolute && (u.scheme == "http" || u.scheme == "https") && u.host != null && it.length <= 2048 }
        } catch (_: Exception) { null }
        metaRefresh.find(head)?.let { m -> metaUrl.find(m.value.replace("&amp;", "&"))?.let { abs(it.groupValues[1]) }?.let { return it } }
        canonicalTag.find(head)?.let { m -> hrefAttr.find(m.value)?.let { abs(it.groupValues[1]) } }
            ?.takeIf { URI(it).host?.lowercase() != base.host?.lowercase() }?.let { return it }
        return null
    }

    const val MAX_PAGE_BYTES = 32 * 1024

    /** Rende assoluto un URL con schema (aggiunge https:// ai link senza schema). */
    internal fun absolute(raw: String): String = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(raw)) raw else "https://$raw"
}

/** Esito di una singola richiesta a un servizio di accorciamento. */
sealed interface ShortLinkProbe {
    /** 301/302/303/307/308 con [location] (puo' essere relativa o assente). */
    class Redirect(val location: String?) : ShortLinkProbe
    /** Qualunque altra risposta (200 senza indicazioni nella pagina, 4xx, 5xx): destinazione non risolta. */
    data object Other : ShortLinkProbe
    /** Rete, timeout, TLS. */
    data object Failed : ShortLinkProbe
}

fun interface ShortLinkTransport {
    /** HEAD (o GET senza leggere il corpo) verso [url], senza seguire redirect, con timeout [timeoutMs]. */
    fun probe(url: String, timeoutMs: Int): ShortLinkProbe
}

/**
 * Risolve i link accorciati di un messaggio: per ciascuno (max [ShortLinks.MAX_LINKS]) segue al piu' [ShortLinks.MAX_HOPS]
 * salti tra servizi della lista; appena la destinazione NON e' un accorciatore si ferma e la restituisce senza contattarla.
 * Budget totale [totalMs] per messaggio, [hopMs] per salto. Cache LRU in memoria (24 h) url -> destinazione; nessun log.
 */
class ShortLinkResolver(
    private val transport: ShortLinkTransport,
    private val shorteners: () -> Set<String>,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val nanoClock: () -> Long = System::nanoTime,
    private val hopMs: Int = 2_500,
    private val totalMs: Int = 4_000,
    private val cacheSize: Int = 256,
    private val cacheTtlMs: Long = 24 * 60 * 60 * 1000L,
    private val negativeTtlMs: Long = 5 * 60 * 1000L,
) {
    private class Entry(val dest: String?, val at: Long)
    private val cache = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > cacheSize
    }

    /** Mappa link accorciato (come in [ShortLinks.candidates]) -> destinazione; assente = non risolto. */
    fun resolve(text: String): Map<String, String> {
        val sh = shorteners()
        val links = ShortLinks.candidates(text, sh)
        if (links.isEmpty()) return emptyMap()
        val deadline = nanoClock() + totalMs * 1_000_000L
        val out = LinkedHashMap<String, String>()
        for (l in links) {
            val d = resolveOne(l, sh, deadline) ?: continue
            out[l] = d
        }
        return out
    }

    private fun cached(key: String): Entry? = synchronized(cache) {
        val e = cache[key] ?: return null
        val ttl = if (e.dest == null) negativeTtlMs else cacheTtlMs
        if (clockMs() - e.at > ttl) { cache.remove(key); null } else e
    }

    private fun resolveOne(link: String, sh: Set<String>, deadline: Long): String? {
        cached(link)?.let { return it.dest }
        val dest = follow(ShortLinks.absolute(link), sh, deadline)
        // un fallimento per budget scaduto non si memorizza (non dice nulla sul link)
        if (dest != null || nanoClock() < deadline) synchronized(cache) { cache[link] = Entry(dest, clockMs()) }
        return dest
    }

    private fun remainingMs(deadline: Long) = ((deadline - nanoClock()) / 1_000_000L).toInt()

    private fun follow(start: String, sh: Set<String>, deadline: Long): String? {
        var cur = start
        val seen = HashSet<String>()
        repeat(ShortLinks.MAX_HOPS) {
            if (!seen.add(cur)) return null // ciclo
            val probe = probeWithUpgrade(cur, sh, deadline) ?: return null
            if (probe !is ShortLinkProbe.Redirect) return null
            val next = target(cur, probe.location) ?: return null
            val parsed = Extractors.parse(next) ?: return null
            if (!ShortLinks.isShortened(parsed, sh)) {
                // il servizio rimanda al proprio sito (errore/home): link non risolto
                Extractors.parse(cur)?.let { c -> if (ShortLinks.isServiceSite(c.registrable, parsed.registrable)) return null }
                // destinazione finale: si restituisce e basta (mai contattata)
                return next.takeIf { parsed.scheme == "http" || parsed.scheme == "https" }
            }
            cur = next
        }
        return null // troppi salti
    }

    /** Solo host della lista (garantito dal chiamante). https per primo; http solo se https non regge. */
    private fun probeWithUpgrade(url: String, sh: Set<String>, deadline: Long): ShortLinkProbe? {
        val host = Extractors.parse(url)?.takeIf { ShortLinks.isShortened(it, sh) } ?: return null
        if (host.scheme != "http" && host.scheme != "https") return null
        val tmo = minOf(hopMs, remainingMs(deadline))
        if (tmo <= 0) return null
        val https = if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url
        val first = transport.probe(https, tmo)
        if (first !is ShortLinkProbe.Failed || https == url) return first.takeIf { it !is ShortLinkProbe.Failed }
        val tmo2 = minOf(hopMs, remainingMs(deadline))
        if (tmo2 <= 0) return null
        return transport.probe(url, tmo2).takeIf { it !is ShortLinkProbe.Failed } // http: solo verso uno shortener della lista
    }

    private fun target(base: String, location: String?): String? {
        val loc = location?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2048 } ?: return null
        return try {
            val u = URI(base).resolve(URI(loc))
            u.toString().takeIf { u.isAbsolute && (u.scheme == "http" || u.scheme == "https") }
        } catch (e: Exception) {
            null
        }
    }
}
