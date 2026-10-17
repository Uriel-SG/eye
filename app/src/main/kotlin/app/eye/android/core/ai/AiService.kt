package app.eye.android.core.ai

import app.eye.android.core.MiniJson
import app.eye.android.core.Redactor
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** Interpretazione delle risposte dei provider. Il contenuto è NON fidato: si estraggono solo campi attesi. */
object AiResponses {
    private const val MAX_PARSE = 16_384
    private const val MAX_REASON = 300
    private const val MAX_ADVICE = 500

    fun parse(provider: AiProvider, code: Int, body: String): AiOutcome {
        when {
            code == 401 || code == 403 -> return AiOutcome.Failure(AiError.AUTH, code)
            code == 429 -> return AiOutcome.Failure(AiError.RATE_LIMIT, code)
            code == 500 || code == 502 || code == 503 || code == 504 || code == 529 -> return AiOutcome.Failure(AiError.OVERLOADED, code)
            code !in 200..299 -> return AiOutcome.Failure(AiError.HTTP, code)
        }
        val root = MiniJson.parse(body) as? Map<*, *> ?: return AiOutcome.Failure(AiError.INVALID)
        val (text, stop) = when (provider) {
            AiProvider.ANTHROPIC -> anthropic(root)
            AiProvider.GEMINI -> gemini(root)
            AiProvider.OPENAI, AiProvider.COMPATIBLE -> openai(root)
        }
        when (stop) {
            Stop.REFUSAL -> return AiOutcome.Failure(AiError.REFUSED)
            Stop.TRUNCATED -> return AiOutcome.Failure(AiError.TRUNCATED)
            Stop.OK -> {}
        }
        return parseOpinion(text)?.let { AiOutcome.Success(it) } ?: AiOutcome.Failure(AiError.INVALID)
    }

    private enum class Stop { OK, REFUSAL, TRUNCATED }

    private fun anthropic(root: Map<*, *>): Pair<String, Stop> {
        val text = (root["content"] as? List<*>).orEmpty()
            .mapNotNull { it as? Map<*, *> }
            .filter { it["type"] == "text" }
            .mapNotNull { it["text"] as? String }
            .joinToString("")
        val stop = when (root["stop_reason"]) {
            "refusal" -> Stop.REFUSAL
            "max_tokens" -> Stop.TRUNCATED
            else -> Stop.OK
        }
        return text to stop
    }

    private fun gemini(root: Map<*, *>): Pair<String, Stop> {
        if ((root["promptFeedback"] as? Map<*, *>)?.get("blockReason") != null) return "" to Stop.REFUSAL
        val cand = (root["candidates"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: return "" to Stop.OK
        val text = ((cand["content"] as? Map<*, *>)?.get("parts") as? List<*>).orEmpty()
            .mapNotNull { (it as? Map<*, *>)?.get("text") as? String }
            .joinToString("")
        val stop = when (cand["finishReason"]) {
            "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "RECITATION", "SPII" -> Stop.REFUSAL
            "MAX_TOKENS" -> Stop.TRUNCATED
            else -> Stop.OK
        }
        return text to stop
    }

    private fun openai(root: Map<*, *>): Pair<String, Stop> {
        val choice = (root["choices"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: return "" to Stop.OK
        val msg = choice["message"] as? Map<*, *>
        if (msg?.get("refusal") is String && (msg["refusal"] as String).isNotBlank()) return "" to Stop.REFUSAL
        val content = msg?.get("content")
        val text = when (content) {
            is String -> content
            is List<*> -> content.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }.joinToString("")
            else -> ""
        }
        val stop = when (choice["finish_reason"]) {
            "content_filter" -> Stop.REFUSAL
            "length" -> Stop.TRUNCATED
            else -> Stop.OK
        }
        return text to stop
    }

    /** Parsing tollerante: primo oggetto JSON del testo; giudizio sconosciuto = risposta non valida. */
    fun parseOpinion(text: String): AiOpinion? {
        val m = MiniJson.firstObject(text.take(MAX_PARSE)) ?: return null
        val judgement = when ((m["giudizio"] as? String)?.trim()?.lowercase()) {
            "truffa" -> AiJudgement.TRUFFA
            "sospetto" -> AiJudgement.SOSPETTO
            "nessun_segnale" -> AiJudgement.NESSUN_SEGNALE
            else -> return null
        }
        val reasons = (m["motivi"] as? List<*>).orEmpty()
            .filterIsInstance<String>().map { clean(it, MAX_REASON) }.filter { it.isNotEmpty() }.take(4)
        val advice = clean(m["consiglio"] as? String ?: "", MAX_ADVICE)
        return AiOpinion(judgement, reasons, advice)
    }

    /** Solo testo semplice: via caratteri di controllo e spazi multipli; lunghezza limitata. */
    private fun clean(s: String, max: Int): String {
        // via controllo e formato (bidi, zero-width), poi link/numeri/email/IBAN oscurati: niente contatti del truffatore
        val plain = s.filter { Character.getType(it) != Character.FORMAT.toInt() && (it == '\n' || it >= ' ') }
        return Redactor.redact(plain.replace(Regex("""\s+"""), " ").trim()).take(max)
    }
}

fun interface HttpTransport {
    /** Lancia IOException / SocketTimeoutException in caso di errore di rete. */
    fun post(spec: HttpSpec): HttpResult
}

/** Trasporto reale: HttpURLConnection, connessione 15 s, lettura [HttpSpec.readTimeoutMs], scadenza complessiva 120 s, nessun redirect (la chiave non segue mai un reindirizzamento). */
object UrlConnectionTransport : HttpTransport {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val MAX_BYTES = 1_000_000
    private const val DEADLINE_MS = AiService.TOTAL_DEADLINE_MS

    override fun post(spec: HttpSpec): HttpResult {
        val started = System.currentTimeMillis()
        val conn = URL(spec.url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = spec.readTimeoutMs
            conn.instanceFollowRedirects = false
            conn.doOutput = true
            spec.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(spec.body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { s ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = s.read(buf)
                    if (System.currentTimeMillis() - started > DEADLINE_MS) throw SocketTimeoutException()
                    if (n < 0 || out.size() + n > MAX_BYTES) break
                    out.write(buf, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }.orEmpty()
            return HttpResult(code, body)
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * [sleeper], [backoffMs] e [clock] sono iniettabili per i test. Un solo nuovo tentativo automatico (timeout, 429, 500/502/503/504)
 * con attesa di 2-4 s, purché resti tempo nella scadenza complessiva di 120 s.
 */
class AiService(
    private val sleeper: (Long) -> Unit = Thread::sleep,
    private val backoffMs: () -> Long = { 2_000L + java.util.concurrent.ThreadLocalRandom.current().nextLong(2_001L) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val transport: HttpTransport = UrlConnectionTransport,
) {
    /** Bloccante: chiamare fuori dal thread principale. Mai logga chiave, testo o corpo della risposta. */
    fun ask(config: AiConfig, text: String): AiOutcome {
        val spec = AiRequests.build(config, text) ?: return AiOutcome.Failure(AiError.NOT_CONFIGURED)
        val start = clock()
        var retried = false
        while (true) {
            val left = TOTAL_DEADLINE_MS - (clock() - start)
            val out = once(config, spec.copy(readTimeoutMs = minOf(READ_TIMEOUT_MS, left).toInt().coerceAtLeast(1_000)))
            if (retried || !retryable(out)) return out
            val wait = backoffMs()
            if (TOTAL_DEADLINE_MS - (clock() - start) - wait < MIN_RETRY_BUDGET_MS) return out
            sleeper(wait)
            retried = true
        }
    }

    private fun once(config: AiConfig, spec: HttpSpec): AiOutcome {
        val res = try {
            transport.post(spec)
        } catch (_: SocketTimeoutException) {
            return AiOutcome.Failure(AiError.TIMEOUT)
        } catch (_: java.io.IOException) {
            return AiOutcome.Failure(AiError.NETWORK)
        } catch (_: Exception) {
            // mai propagare il messaggio: potrebbe contenere la chiave (intestazioni) o il testo
            return AiOutcome.Failure(AiError.NETWORK)
        }
        return AiResponses.parse(config.provider, res.code, res.body)
    }

    private fun retryable(o: AiOutcome) = o is AiOutcome.Failure &&
        (o.error == AiError.TIMEOUT || o.httpCode in RETRY_CODES)

    companion object {
        const val READ_TIMEOUT_MS = 90_000L
        const val TOTAL_DEADLINE_MS = 120_000L
        private const val MIN_RETRY_BUDGET_MS = 15_000L
        private val RETRY_CODES = setOf(429, 500, 502, 503, 504)

        /** Messaggio innocuo per «Prova connessione». */
        const val TEST_TEXT = "Messaggio di prova: ciao, ci vediamo domani."

        /** La connessione funziona se il provider ha risposto in modo valido (anche un rifiuto o un formato strano). */
        fun connectionOk(o: AiOutcome): Boolean = when (o) {
            is AiOutcome.Success -> true
            is AiOutcome.Failure -> o.error in setOf(AiError.REFUSED, AiError.TRUNCATED, AiError.INVALID)
        }
    }
}
