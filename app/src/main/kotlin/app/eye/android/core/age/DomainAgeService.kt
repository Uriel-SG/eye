package app.eye.android.core.age

import app.eye.android.BuildConfig
import app.eye.android.core.MiniJson
import app.eye.engine.enrich.DomainAgeEntry
import app.eye.engine.enrich.Rdap
import app.eye.engine.enrich.RdapBootstrap
import app.eye.engine.enrich.RdapParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Risposta HTTP minima per RDAP/bootstrap. [Failed] = rete, timeout, risposta troppo grande, redirect non ammesso, HTTP 5xx/429. */
sealed interface RdapHttpResult {
    class Ok(val body: String) : RdapHttpResult
    data object NotFound : RdapHttpResult
    data object Failed : RdapHttpResult
}

interface RdapHttp {
    fun get(url: String, maxBytes: Int): RdapHttpResult
}

/**
 * GET HTTPS diretto al registro: timeout 4 s, nessun cookie, nessun corpo, max 256 KB. Redirect (RFC 7480) solo verso
 * https su nomi host (mai IP), al massimo [MAX_REDIRECTS]. Nessun proxy di terzi: l'URL e' quello del registro.
 */
class UrlConnectionRdapHttp : RdapHttp {
    override fun get(url: String, maxBytes: Int): RdapHttpResult {
        var current = url
        try {
            for (hop in 0..MAX_REDIRECTS) {
                if (!safeHttps(current)) return RdapHttpResult.Failed
                val c = URL(current).openConnection() as HttpURLConnection
                try {
                    c.requestMethod = "GET"
                    c.connectTimeout = TIMEOUT_MS
                    c.readTimeout = TIMEOUT_MS
                    c.instanceFollowRedirects = false
                    c.useCaches = false
                    c.setRequestProperty("User-Agent", "Eye/" + BuildConfig.VERSION_NAME)
                    c.setRequestProperty("Accept", "application/rdap+json, application/json")
                    when (val code = c.responseCode) {
                        200 -> {
                            if (c.contentLengthLong > maxBytes) return RdapHttpResult.Failed
                            val out = ByteArrayOutputStream()
                            c.inputStream.use { ins ->
                                val buf = ByteArray(8192)
                                while (true) {
                                    val n = ins.read(buf)
                                    if (n < 0) break
                                    if (out.size() + n > maxBytes) return RdapHttpResult.Failed
                                    out.write(buf, 0, n)
                                }
                            }
                            return RdapHttpResult.Ok(out.toString(Charsets.UTF_8.name()))
                        }
                        404 -> return RdapHttpResult.NotFound
                        301, 302, 303, 307, 308 -> {
                            val loc = c.getHeaderField("Location") ?: return RdapHttpResult.Failed
                            current = URL(URL(current), loc).toString()
                        }
                        else -> return RdapHttpResult.Failed
                    }
                } finally { c.disconnect() }
            }
        } catch (_: IOException) { return RdapHttpResult.Failed } catch (_: RuntimeException) { return RdapHttpResult.Failed }
        return RdapHttpResult.Failed // troppi redirect
    }

    private fun safeHttps(url: String): Boolean {
        val u = runCatching { URL(url) }.getOrNull() ?: return false
        val host = u.host.lowercase()
        return u.protocol == "https" && u.userInfo == null && host.contains('.') && host.any { it.isLetter() } && !host.contains(':')
    }

    companion object {
        const val TIMEOUT_MS = 4000
        const val MAX_REDIRECTS = 2
    }
}

/** Esito complessivo: [incomplete] = almeno un dominio non e' stato raggiunto (errore di rete): non va salvato in cronologia. */
data class DomainAgeResult(val entries: List<DomainAgeEntry>, val checkedAt: Long, val incomplete: Boolean)

/**
 * Cerca la data di registrazione via RDAP direttamente al registro del TLD (bootstrap IANA in cache 30 giorni).
 * Si invia SOLO il nome del dominio. Cache dominio -> data per 7 giorni in [dir] (filesDir, esclusa dai backup).
 */
class DomainAgeService(
    private val dir: File,
    private val http: RdapHttp = UrlConnectionRdapHttp(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val cacheFile get() = File(dir, "age_cache.json")
    private val bootstrapFile get() = File(dir, "dns_bootstrap.json")
    private val lock = Any()

    suspend fun check(domains: List<String>): DomainAgeResult = withContext(Dispatchers.IO) {
        val now = clock()
        val list = domains.take(MAX_DOMAINS)
        val done = withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            val boot by lazy { bootstrap(now) }
            coroutineScope { list.map { d -> async { lookup(d, now) { boot } } }.awaitAll() }
        }
        if (done == null) return@withContext DomainAgeResult(emptyList(), now, true)
        DomainAgeResult(done.map { DomainAgeEntry(it.first, it.second) }, now, done.any { it.third })
    }

    private fun lookup(domain: String, now: Long, boot: () -> RdapBootstrap?): Triple<String, Long?, Boolean> {
        cached(domain, now)?.let { return Triple(domain, it.value, false) }
        val b = boot() ?: return Triple(domain, null, true)
        val url = b.queryUrl(domain) ?: run { remember(domain, null, now); return Triple(domain, null, false) } // TLD senza RDAP
        return when (val r = http.get(url, Rdap.MAX_BYTES)) {
            is RdapHttpResult.Ok -> when (val p = Rdap.parseRegistration(r.body)) {
                is RdapParse.Registered -> { remember(domain, p.atMs, now); Triple(domain, p.atMs, false) }
                RdapParse.NoRegistration -> { remember(domain, null, now); Triple(domain, null, false) }
                RdapParse.Invalid -> Triple(domain, null, true)
            }
            RdapHttpResult.NotFound -> { remember(domain, null, now); Triple(domain, null, false) }
            RdapHttpResult.Failed -> Triple(domain, null, true)
        }
    }

    private class Cached(val value: Long?)

    private fun readCache(): MutableMap<String, Pair<Long?, Long>> = synchronized(lock) {
        val m = runCatching { MiniJson.parse(cacheFile.readText()) as? Map<*, *> }.getOrNull() ?: return mutableMapOf()
        val out = mutableMapOf<String, Pair<Long?, Long>>()
        for ((k, v) in m) {
            val e = v as? Map<*, *> ?: continue
            val at = (e["t"] as? Number)?.toLong() ?: continue
            out[k as? String ?: continue] = (e["r"] as? Number)?.toLong() to at
        }
        out
    }

    private fun cached(domain: String, now: Long): Cached? {
        val e = readCache()[domain] ?: return null
        return if (now - e.second in 0..CACHE_MS) Cached(e.first) else null
    }

    private fun remember(domain: String, registeredAt: Long?, now: Long) = synchronized(lock) {
        runCatching {
            val m = readCache()
            m.entries.removeAll { now - it.value.second !in 0..CACHE_MS } // scaduti
            m[domain] = registeredAt to now
            while (m.size > MAX_CACHE) m.remove(m.minByOrNull { it.value.second }!!.key)
            dir.mkdirs()
            cacheFile.writeText(MiniJson.stringify(m.mapValues { (_, v) -> mapOf("r" to v.first, "t" to v.second) }))
        }
    }

    /** Bootstrap IANA: file in cache 30 giorni; se lo scarico fallisce si usa quello scaduto, se c'e'. */
    private fun bootstrap(now: Long): RdapBootstrap? {
        val f = bootstrapFile
        val fresh = f.exists() && now - f.lastModified() in 0..BOOTSTRAP_MS
        if (fresh) runCatching { RdapBootstrap.parse(f.readText()) }.getOrNull()?.let { return it }
        val got = (http.get(BOOTSTRAP_URL, MAX_BOOTSTRAP_BYTES) as? RdapHttpResult.Ok)?.body
        val parsed = got?.let { RdapBootstrap.parse(it) }
        if (parsed != null) {
            runCatching { dir.mkdirs(); f.writeText(got); f.setLastModified(now) }
            return parsed
        }
        return if (f.exists()) runCatching { RdapBootstrap.parse(f.readText()) }.getOrNull() else null
    }

    /** Cancella cache e bootstrap (Impostazioni > cancella tutti i dati). */
    fun clear() { synchronized(lock) { cacheFile.delete(); bootstrapFile.delete() } }

    companion object {
        const val BOOTSTRAP_URL = "https://data.iana.org/rdap/dns.json"
        const val MAX_DOMAINS = 3
        const val TOTAL_TIMEOUT_MS = 12_000L
        const val CACHE_MS = 7L * 24 * 60 * 60 * 1000
        const val BOOTSTRAP_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_BOOTSTRAP_BYTES = 1024 * 1024
        private const val MAX_CACHE = 500
    }
}
