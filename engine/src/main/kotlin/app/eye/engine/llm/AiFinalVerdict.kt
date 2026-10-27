package app.eye.engine.llm

import app.eye.engine.Level
import app.eye.engine.Reason
import app.eye.engine.UserExceptions
import app.eye.engine.Verdict

/**
 * Verdetto finale dopo l'analisi automatica dell'AI collegata (controlli manuali). Logica pura.
 * - La decisione richiede confidenza alta ([LegitGate.CONFIDENCE]); il testo del messaggio e' solo un dato e non
 *   influisce mai (un'istruzione nascosta nel messaggio non porta a una confidenza alta per costruzione del prompt).
 * - Senza segnali gravi il livello finale e' quello dell'AI (puo' anche abbassare); alzare e' limitato a un gradino.
 * - Con segnali gravi (vedi [LegitGate.GRAVE_IDS]) l'AI puo' solo alzare.
 * - Errore/timeout (parere assente) o eccezione dell'utente: restano le regole.
 */
object AiFinalVerdict {
    const val REASON_ID = "AI-01"

    private fun severity(l: Level) = when (l) { Level.NESSUN_SEGNALE -> 0; Level.SOSPETTO -> 1; Level.PROBABILE_TRUFFA -> 2 }
    private fun level(s: Int) = when (s) { 0 -> Level.NESSUN_SEGNALE; 1 -> Level.SOSPETTO; else -> Level.PROBABILE_TRUFFA }

    /** Livello finale; coincide con quello delle regole se l'AI non puo' cambiarlo. */
    fun finalLevel(v: Verdict, opinion: LlmOpinion?): Level {
        if (opinion == null || opinion.confidence.isNaN() || opinion.confidence < LegitGate.CONFIDENCE) return v.level
        if (v.reasons.any { it.ruleId == UserExceptions.TRUSTED_ID || it.ruleId == REASON_ID }) return v.level
        val ai = when (opinion.verdict) {
            LlmVerdict.TRUFFA -> Level.PROBABILE_TRUFFA
            LlmVerdict.SOSPETTO -> Level.SOSPETTO
            LlmVerdict.LEGITTIMO -> Level.NESSUN_SEGNALE
        }
        val rules = severity(v.level)
        val target = severity(ai)
        return when {
            target > rules -> level(rules + 1)
            target < rules && !LegitGate.hasGraveSignals(v) -> ai
            else -> v.level
        }
    }

    /**
     * Verdetto da mostrare/condividere: se il livello cambia aggiunge il motivo [REASON_ID] con [note] e svuota le azioni
     * (l'app usa quelle predefinite del nuovo livello). I motivi delle regole restano tutti.
     */
    fun apply(v: Verdict, opinion: LlmOpinion?, note: String): Verdict {
        val l = finalLevel(v, opinion)
        val steps = if (opinion == null || !decides(v, opinion)) emptyList() else opinion.actions.filter { it.isNotBlank() }.take(4)
        if (l == v.level) return if (steps.isEmpty() || steps == v.actions) v else v.copy(actions = steps)
        return v.copy(level = l, reasons = v.reasons + Reason(REASON_ID, note, 0), actions = steps)
    }

    /** L'AI "decide" (parere con confidenza alta e verdetto non gia' bloccato da eccezione/AI precedente): i suoi consigli valgono come "Cosa fare ora". */
    private fun decides(v: Verdict, o: LlmOpinion): Boolean =
        !o.confidence.isNaN() && o.confidence >= LegitGate.CONFIDENCE &&
            v.reasons.none { it.ruleId == UserExceptions.TRUSTED_ID || it.ruleId == REASON_ID }
}
