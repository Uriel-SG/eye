package app.eye.engine.llm

import app.eye.engine.Level
import app.eye.engine.Reason
import app.eye.engine.UserExceptions
import app.eye.engine.Verdict

/**
 * Verdetto finale dopo l'analisi automatica dell'AI collegata (controlli manuali). Logica pura.
 * - Parere PIU' GRAVE delle regole: alza al livello dell'AI con confidenza >= [RAISE_CONFIDENCE] (meglio un raro falso
 *   positivo di una truffa persa; vince l'AI collegata), anche con segnali gravi.
 * - Parere meno grave: abbassa solo con confidenza alta ([LegitGate.CONFIDENCE]) e senza segnali gravi (vedi [LegitGate.GRAVE_IDS]).
 * - Il testo del messaggio e' solo un dato e non influisce mai sulla decisione.
 * - Errore/timeout (parere assente) o eccezione dell'utente: restano le regole.
 */
object AiFinalVerdict {
    const val REASON_ID = "AI-01"
    /** Confidenza minima perche' un parere piu' grave delle regole alzi il verdetto. */
    const val RAISE_CONFIDENCE = 0.5

    private fun severity(l: Level) = when (l) { Level.NESSUN_SEGNALE -> 0; Level.SOSPETTO -> 1; Level.PROBABILE_TRUFFA -> 2 }

    private fun blocked(v: Verdict, o: LlmOpinion?) =
        o == null || o.confidence.isNaN() || v.reasons.any { it.ruleId == UserExceptions.TRUSTED_ID || it.ruleId == REASON_ID }

    private fun aiLevel(o: LlmOpinion) = when (o.verdict) {
        LlmVerdict.TRUFFA -> Level.PROBABILE_TRUFFA
        LlmVerdict.SOSPETTO -> Level.SOSPETTO
        LlmVerdict.LEGITTIMO -> Level.NESSUN_SEGNALE
    }

    /** Livello finale; coincide con quello delle regole se l'AI non puo' cambiarlo. */
    fun finalLevel(v: Verdict, opinion: LlmOpinion?): Level {
        if (opinion == null || blocked(v, opinion)) return v.level
        val ai = aiLevel(opinion)
        return when {
            severity(ai) > severity(v.level) && opinion.confidence >= RAISE_CONFIDENCE -> ai
            severity(ai) < severity(v.level) && opinion.confidence >= LegitGate.CONFIDENCE && !LegitGate.hasGraveSignals(v) -> ai
            else -> v.level
        }
    }

    /** true se il parere alza il livello delle regole. */
    fun raises(v: Verdict, opinion: LlmOpinion?): Boolean = severity(finalLevel(v, opinion)) > severity(v.level)

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
        !blocked(v, o) && (o.confidence >= LegitGate.CONFIDENCE || raises(v, o))
}
