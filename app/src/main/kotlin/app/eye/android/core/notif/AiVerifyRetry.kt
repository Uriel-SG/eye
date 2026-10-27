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
) {
    /** [attempt] riceve la scadenza (ms) per connessione e lettura. */
    fun run(attempt: (Long) -> AiOutcome): AiOutcome {
        val start = clock()
        val first = attempt(FIRST_MS)
        if (!retryable(first)) return first
        while (clock() - start < WAIT_MS && !networkAvailable()) sleeper(POLL_MS)
        val left = TOTAL_MS - (clock() - start)
        if (!networkAvailable() || left < MIN_RETRY_MS) return first
        return attempt(minOf(FIRST_MS, left / 2))
    }

    private fun retryable(o: AiOutcome) = o is AiOutcome.Failure && (o.error == AiError.NETWORK || o.error == AiError.TIMEOUT)

    companion object {
        const val FIRST_MS = 8_000L
        const val POLL_MS = 2_000L
        const val WAIT_MS = 25_000L
        const val TOTAL_MS = 40_000L
        const val MIN_RETRY_MS = 6_000L
    }
}
