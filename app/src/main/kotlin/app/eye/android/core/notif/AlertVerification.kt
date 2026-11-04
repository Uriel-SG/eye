package app.eye.android.core.notif

import app.eye.android.core.ai.AiError
import app.eye.android.core.ai.AiJudgement
import app.eye.android.core.ai.AiOpinion
import app.eye.android.core.ai.AiOutcome

/** Perche' e' partito un avviso (codice diagnostico breve, senza alcun contenuto del messaggio). */
enum class AlertReason {
    NETWORK, TIMEOUT, AI_LIMIT, AI_ERROR, AI_NOT_LEGIT, AI_LOW_CONF, GRAVE, NOT_VERIFIED, AI_BACKGROUND;

    companion object {
        fun parse(code: String?): AlertReason? = code?.let { c -> entries.firstOrNull { it.name == c } }
    }
}

/** Esito della verifica AI prima dell'avviso. */
sealed interface VerifyDecision {
    /** L'AI ritiene il messaggio legittimo (confidenza alta): nessun avviso. */
    data object Dismiss : VerifyDecision
    /** Avviso subito. */
    data class Alert(val reason: AlertReason) : VerifyDecision
}

/** Logica pura (senza Android) che trasforma l'esito della verifica AI in una decisione. Fail-safe: nel dubbio si avvisa. */
object VerifyPolicy {
    /**
     * [outcome] null = verifica non eseguita (spenta o senza chiave). [mayDismiss] applica LegitGate.mayDismiss.
     * Rete assente o timeout (dopo i tentativi di [AiVerifyRetry]): si avvisa subito, senza rimandare.
     */
    fun decide(outcome: AiOutcome?, mayDismiss: (AiOpinion) -> Boolean): VerifyDecision = when (outcome) {
        null -> VerifyDecision.Alert(AlertReason.NOT_VERIFIED)
        is AiOutcome.Failure -> when (outcome.error) {
            AiError.NETWORK -> VerifyDecision.Alert(AlertReason.NETWORK)
            AiError.TIMEOUT -> VerifyDecision.Alert(AlertReason.TIMEOUT)
            AiError.RATE_LIMIT, AiError.FREE_LIMIT, AiError.OVERLOADED -> VerifyDecision.Alert(AlertReason.AI_LIMIT)
            else -> VerifyDecision.Alert(AlertReason.AI_ERROR)
        }
        is AiOutcome.Success -> {
            val op = outcome.opinion
            when {
                op.judgement != AiJudgement.NESSUN_SEGNALE -> VerifyDecision.Alert(AlertReason.AI_NOT_LEGIT)
                !mayDismiss(op) -> VerifyDecision.Alert(AlertReason.AI_LOW_CONF)
                else -> VerifyDecision.Dismiss
            }
        }
    }

}
