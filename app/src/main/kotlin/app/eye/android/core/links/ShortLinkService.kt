package app.eye.android.core.links

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.eye.engine.ShortLinkProbe
import app.eye.engine.ShortLinkResolver
import app.eye.engine.ShortLinkTransport
import app.eye.engine.ShortLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * HEAD verso un servizio di accorciamento, senza redirect, senza cookie, User-Agent generico. Mai su host diversi da
 * quelli passati dal risolutore (che sono solo shortener). Se HEAD non e' supportato o e' rifiutato (400/403/405/501)
 * o risponde 200 con una pagina, si ripete con GET: un 200 HTML viene letto per al piu' 32 KB, SOLO dallo shortener,
 * per cercare un meta refresh o un canonical verso un altro sito (pagina intermedia). Gli altri corpi non si leggono.
 */
class UrlConnectionShortLinkTransport(private val allowCleartext: Boolean = false) : ShortLinkTransport {
    private class Response(val status: Int, val location: String?, val html: String?)

    override fun probe(url: String, timeoutMs: Int): ShortLinkProbe {
        val started = System.nanoTime()
        try {
            val u = URL(url)
            if (u.protocol != "https" && !(allowCleartext && u.protocol == "http")) return ShortLinkProbe.Failed
            var r = request(u, "HEAD", timeoutMs, readPage = false)
            if (r.status in GET_FALLBACK || r.status == 200) {
                val left = timeoutMs - ((System.nanoTime() - started) / 1_000_000L).toInt()
                if (left <= 0) return ShortLinkProbe.Failed
                r = request(u, "GET", left, readPage = true)
            }
            if (r.status in REDIRECTS) return ShortLinkProbe.Redirect(r.location)
            if (r.status == 200 && r.html != null) ShortLinks.pageRedirect(r.html, url)?.let { return ShortLinkProbe.Redirect(it) }
            return ShortLinkProbe.Other
        } catch (_: IOException) {
            return ShortLinkProbe.Failed
        } catch (_: RuntimeException) {
            return ShortLinkProbe.Failed
        }
    }

    private fun request(u: URL, method: String, timeoutMs: Int, readPage: Boolean): Response {
        val c = u.openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "text/html,*/*")
            val status = c.responseCode
            val html = if (readPage && status == 200 && c.contentType.orEmpty().contains("html", ignoreCase = true)) readCapped(c) else null
            return Response(status, c.getHeaderField("Location"), html)
        } finally {
            c.disconnect()
        }
    }

    /** Al piu' [ShortLinks.MAX_PAGE_BYTES] byte, poi si smette di leggere. */
    private fun readCapped(c: HttpURLConnection): String? = try {
        c.inputStream.use { ins ->
            val buf = ByteArray(ShortLinks.MAX_PAGE_BYTES)
            var n = 0
            while (n < buf.size) {
                val k = ins.read(buf, n, buf.size - n)
                if (k < 0) break
                n += k
            }
            String(buf, 0, n, Charsets.UTF_8)
        }
    } catch (_: IOException) {
        null
    }

    companion object {
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        /** HEAD non supportato o rifiutato dal servizio: si riprova con GET (404/410 sono risposte definitive). */
        private val GET_FALLBACK = setOf(400, 403, 405, 501)
        /** Generico: nessun modello di dispositivo ne' versione dell'app. */
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android) Mobile"
    }
}

/**
 * Rete utilizzabile in background (NotificationListenerService): Doze, App Standby e Risparmio dati possono negarla.
 * Se non c'e' rete o il Risparmio dati la blocca per l'app, e' inutile aspettare i timeout: link «non risolto».
 */
object BackgroundNetwork {
    /** Logica pura (testabile): [restrictStatus] come ConnectivityManager.getRestrictBackgroundStatus(). */
    fun usable(hasInternet: Boolean, restrictStatus: Int, metered: Boolean): Boolean =
        hasInternet && !(restrictStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED && metered)

    fun check(context: Context): () -> Boolean = {
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            usable(
                hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                restrictStatus = cm?.restrictBackgroundStatus ?: ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED,
                metered = cm?.isActiveNetworkMetered ?: false,
            )
        } catch (_: RuntimeException) {
            true // nel dubbio si prova: il timeout per messaggio resta il limite
        }
    }
}

/**
 * Punto unico per espandere i link accorciati di un testo (primo piano e notifiche). Sempre attivo, non lancia
 * mai, non registra URL. Si chiama da thread secondari.
 */
class ShortLinkService(
    private val resolver: ShortLinkResolver,
    private val backgroundNetworkUsable: () -> Boolean = { true },
) {
    /**
     * Bloccante (max ~4 s): per il worker seriale delle notifiche. In background ([background]) senza rete utilizzabile
     * restituisce subito «nessun link risolto», senza attese.
     */
    fun expandBlocking(text: String, background: Boolean = false): Map<String, String> =
        try {
            if (background && !backgroundNetworkUsable()) emptyMap() else resolver.resolve(text)
        } catch (_: Exception) { emptyMap() }

    suspend fun expand(text: String): Map<String, String> = withContext(Dispatchers.IO) { expandBlocking(text) }
}
