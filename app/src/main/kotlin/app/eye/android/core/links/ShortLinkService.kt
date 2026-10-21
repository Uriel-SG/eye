package app.eye.android.core.links

import app.eye.engine.ShortLinkProbe
import app.eye.engine.ShortLinkResolver
import app.eye.engine.ShortLinkTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * HEAD verso un servizio di accorciamento (GET senza leggere il corpo se HEAD da 400/405), senza redirect, senza cookie,
 * senza corpo, User-Agent generico. Mai su host diversi da quelli passati dal risolutore (che sono solo shortener).
 */
class UrlConnectionShortLinkTransport(private val allowCleartext: Boolean = false) : ShortLinkTransport {
    override fun probe(url: String, timeoutMs: Int): ShortLinkProbe {
        val started = System.nanoTime()
        try {
            val u = URL(url)
            if (u.protocol != "https" && !(allowCleartext && u.protocol == "http")) return ShortLinkProbe.Failed
            var r = request(u, "HEAD", timeoutMs)
            if (r.first == 405 || r.first == 400) {
                val left = timeoutMs - ((System.nanoTime() - started) / 1_000_000L).toInt()
                if (left <= 0) return ShortLinkProbe.Failed
                r = request(u, "GET", left)
            }
            return if (r.first in REDIRECTS) ShortLinkProbe.Redirect(r.second) else ShortLinkProbe.Other
        } catch (_: IOException) {
            return ShortLinkProbe.Failed
        } catch (_: RuntimeException) {
            return ShortLinkProbe.Failed
        }
    }

    private fun request(u: URL, method: String, timeoutMs: Int): Pair<Int, String?> {
        val c = u.openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "*/*")
            return c.responseCode to c.getHeaderField("Location") // il corpo non si legge mai
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        /** Generico: nessun modello di dispositivo ne' versione dell'app. */
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android) Mobile"
    }
}

/**
 * Punto unico per espandere i link accorciati di un testo (primo piano e notifiche). Sempre attivo, non lancia
 * mai, non registra URL. Si chiama da thread secondari.
 */
class ShortLinkService(private val resolver: ShortLinkResolver) {
    /** Bloccante (max ~4 s): per il worker seriale delle notifiche. */
    fun expandBlocking(text: String): Map<String, String> =
        try { resolver.resolve(text) } catch (_: Exception) { emptyMap() }

    suspend fun expand(text: String): Map<String, String> = withContext(Dispatchers.IO) { expandBlocking(text) }
}
