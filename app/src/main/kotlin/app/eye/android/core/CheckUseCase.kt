package app.eye.android.core

import app.eye.android.data.HistoryRepository
import app.eye.android.data.VerdictJson
import app.eye.engine.Analyzer
import app.eye.engine.Extracted
import app.eye.engine.Level
import app.eye.engine.Verdict
import app.eye.engine.image.IllustrationPolicy
import app.eye.engine.image.ImageNature
import app.eye.engine.image.ImageNatureDetector
import app.eye.engine.Input
import app.eye.engine.Reason
import app.eye.engine.provenance.ProvenanceMessages
import app.eye.engine.provenance.ProvenanceReport
import app.eye.engine.Source

sealed interface CheckOutcome {
    data class Done(val result: CheckResult) : CheckOutcome
    data object TooLong : CheckOutcome
    data object Empty : CheckOutcome
}

/** Analizza e (se l'utente lo consente) salva in cronologia (solo locale) testo, esito e verdetto. */
class CheckUseCase(
    private val analyzer: Analyzer,
    private val history: HistoryRepository,
    private val saveHistory: suspend () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Destinazioni dei link accorciati del testo (vuoto se spento o non risolti); eseguita prima dell'analisi. */
    private val expand: suspend (String) -> Map<String, String> = { emptyMap() },
) {
    suspend fun run(text: String, source: Source, truncated: Boolean = false, origin: String? = null, sender: String? = null, provenance: ProvenanceReport? = null, nature: ImageNature = ImageNature.OTHER, hasQr: Boolean = false): CheckOutcome {
        if (text.isBlank()) return CheckOutcome.Empty
        if (text.length > MAX_CHARS) return CheckOutcome.TooLong // SEC-05
        // fumetto/illustrazione senza link, numero o QR: il testo e' un racconto (vedi IllustrationPolicy)
        val analyzed = IllustrationPolicy.apply(analyzer.analyze(Input(text, source, sender, origin, resolvedLinks = expand(text))), nature, hasQr)
        // Segno AI dichiarato nei metadati: motivo solo informativo (peso 0), non cambia livello ne' punteggio.
        val verdict = provenance?.let { ProvenanceMessages.reasonText(it) }
            ?.let { analyzed.copy(reasons = analyzed.reasons + Reason(ProvenanceMessages.REASON_ID, it, 0)) } ?: analyzed
        val id = if (saveHistory()) {
            history.save(verdict.level.name, Redactor.preview(text), verdict.reasonTexts(), text, VerdictJson.encode(verdict), exceptionKind = exceptionKindOf(verdict))
        } else null
        return CheckOutcome.Done(CheckResult(verdict, text, clock(), truncated, id, source, origin, sender, provenance))
    }

    /**
     * Immagine senza testo ne' QR: nessun controllo del contenuto, ma la scheda con la sola provenienza dell'immagine
     * (livello NESSUN_SEGNALE). Nulla in cronologia: non c'e' testo da conservare.
     */
    fun noText(provenance: ProvenanceReport?): CheckOutcome.Done {
        val v = Verdict(
            Level.NESSUN_SEGNALE, 0,
            listOf(Reason(ImageNatureDetector.NO_TEXT_REASON_ID, ImageNatureDetector.NO_TEXT_NOTE, 0)) +
                listOfNotNull(provenance?.let { ProvenanceMessages.reasonText(it) }?.let { Reason(ProvenanceMessages.REASON_ID, it, 0) }),
            emptyList(), Extracted(), null, 0,
        )
        return CheckOutcome.Done(CheckResult(v, "", clock(), false, null, Source.OCR, null, null, provenance))
    }

    companion object {
        /** 1 = eccezione «affidabile» (USR-01), 2 = «sospetta» (USR-02), 0 = nessuna. */
        fun exceptionKindOf(v: app.eye.engine.Verdict): Int = when {
            v.reasons.any { it.ruleId == app.eye.engine.UserExceptions.TRUSTED_ID } -> 1
            v.reasons.any { it.ruleId == app.eye.engine.UserExceptions.SUSPICIOUS_ID } -> 2
            else -> 0
        }
        const val MAX_CHARS = 10_000
    }
}
