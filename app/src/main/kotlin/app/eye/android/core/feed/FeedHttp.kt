package app.eye.android.core.feed

import app.eye.android.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Base dei feed firmati: UN SOLO punto (provvisoria, da confermare). Mai presa dal server. */
object FeedConfig {
    const val FEED_BASE_URL = "https://eye-security.netlify.app/data/"

    /** Solo debug: server locale per le prove (vuoto in release). */
    val baseUrl: String = BuildConfig.FEED_BASE_OVERRIDE.ifEmpty { FEED_BASE_URL }

    /** In release solo https; in debug anche http (con network security config di debug). */
    val allowCleartext: Boolean = BuildConfig.DEBUG
}

class FeedTooLargeException : IOException("risposta oltre il limite")

sealed interface FeedResponse {
    data object NotModified : FeedResponse
    class Ok(val bytes: ByteArray, val etag: String?) : FeedResponse
}

/** Trasporto HTTP minimo, sostituibile nei test. Lancia IOException per rete, HTTP != 200/304 e risposte troppo grandi. */
interface FeedHttp {
    fun get(url: String, etag: String?, maxBytes: Int): FeedResponse

    /** Scarica su file (per il modello, troppo grande per la memoria). Predefinito: tramite [get]; l'implementazione di rete scrive in streaming. */
    fun download(url: String, dest: java.io.File, maxBytes: Long) {
        val r = get(url, null, maxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) as? FeedResponse.Ok ?: throw IOException("risposta inattesa")
        dest.writeBytes(r.bytes)
    }
}

/** GET su HttpURLConnection: nessun cookie, nessun identificativo, niente corpo, niente redirect, timeout brevi. */
class UrlConnectionFeedHttp(private val allowCleartext: Boolean = false) : FeedHttp {
    override fun download(url: String, dest: java.io.File, maxBytes: Long) {
        val u = URL(url)
        if (u.protocol != "https" && !(allowCleartext && u.protocol == "http")) throw IOException("solo https")
        val c = u.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "GET"
            c.connectTimeout = CONNECT_MS
            c.readTimeout = DOWNLOAD_READ_MS
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.setRequestProperty("User-Agent", "Eye/" + BuildConfig.VERSION_NAME)
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            if (c.contentLengthLong > maxBytes) throw FeedTooLargeException()
            var total = 0L
            c.inputStream.use { ins ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw FeedTooLargeException()
                        out.write(buf, 0, n)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    override fun get(url: String, etag: String?, maxBytes: Int): FeedResponse {
        val u = URL(url)
        if (u.protocol != "https" && !(allowCleartext && u.protocol == "http")) throw IOException("solo https")
        val c = u.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "GET"
            c.connectTimeout = CONNECT_MS
            c.readTimeout = READ_MS
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.setRequestProperty("User-Agent", "Eye/" + BuildConfig.VERSION_NAME) // fisso, senza modello del dispositivo (V3-13)
            if (etag != null) c.setRequestProperty("If-None-Match", etag)
            when (val code = c.responseCode) {
                304 -> return FeedResponse.NotModified
                200 -> {
                    if (c.contentLengthLong > maxBytes) throw FeedTooLargeException()
                    val out = ByteArrayOutputStream()
                    c.inputStream.use { ins ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            if (out.size() + n > maxBytes) throw FeedTooLargeException()
                            out.write(buf, 0, n)
                        }
                    }
                    return FeedResponse.Ok(out.toByteArray(), c.getHeaderField("ETag"))
                }
                else -> throw IOException("HTTP $code")
            }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val CONNECT_MS = 10_000
        const val READ_MS = 15_000
        const val DOWNLOAD_READ_MS = 60_000
    }
}
