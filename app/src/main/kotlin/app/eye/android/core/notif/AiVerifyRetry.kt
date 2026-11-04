package app.eye.android.core.notif

import app.eye.android.core.ai.AiError
import app.eye.android.core.ai.AiOutcome

/**
 * Verifica AI prima dell'avviso con un secondo tentativo breve. Se il primo fallisce per rete assente o timeout, attende
 * (max [WAIT_MS] dall'inizio) che la rete torni disponibile e riprova una volta; tetto totale [TOTAL_MS]. Qualsiasi altro
 * errore, o rete che non torna, restituisce l'ultimo esito: il chiamante avvisa (fail-safe). Logica pura, senza Android.
 */
class AiVerifyRetry(
    private val networkAvailable: () -> Boolean,
    private val sleeper: (Long) -> Unit = Thread::sleep,
    private val clock: () -> Long = System::currentTimeMillis,
    private val firstMs: Long = FIRST_MS,
    private val waitMs: Long = WAIT_MS,
    private val totalMs: Long = TOTAL_MS,
    private val minRetryMs: Long = MIN_RETRY_MS,
) {
    /** [attempt] riceve la scadenza (ms) per connessione e lettura. */
    fun run(attempt: (Long) -> AiOutcome): AiOutcome {
        val start = clock()
        val first = attempt(firstMs)
        if (!retryable(first)) return first
        while (clock() - start < waitMs && !networkAvailable()) sleeper(POLL_MS)
        val left = totalMs - (clock() - start)
        if (!networkAvailable() || left < minRetryMs) return first
        return attempt(minOf(firstMs, left / 2))
    }

    private fun retryable(o: AiOutcome) = o is AiOutcome.Failure && (o.error == AiError.NETWORK || o.error == AiError.TIMEOUT)

    companion object {
        const val FIRST_MS = 8_000L
        const val POLL_MS = 2_000L
        const val WAIT_MS = 25_000L
        const val TOTAL_MS = 40_000L
        const val MIN_RETRY_MS = 6_000L

        /** Analisi automatica nei controlli manuali (primo piano): testi lunghi, risposta piu' lenta; un solo secondo tentativo entro ~30 s. */
        fun forAutoAnalysis(networkAvailable: () -> Boolean) =
            AiVerifyRetry(networkAvailable, firstMs = AUTO_READ_MS, waitMs = 10_000L, totalMs = 30_000L, minRetryMs = 5_000L)
        const val AUTO_READ_MS = 20_000L
        const val AUTO_CONNECT_MS = 8_000L
    }
}
