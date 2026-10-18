package app.eye.android.core

import app.eye.android.data.HistoryRepository
import app.eye.android.data.VerdictJson
import app.eye.engine.Analyzer
import app.eye.engine.Input
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
) {
    suspend fun run(text: String, source: Source, truncated: Boolean = false, origin: String? = null, sender: String? = null): CheckOutcome {
        if (text.isBlank()) return CheckOutcome.Empty
        if (text.length > MAX_CHARS) return CheckOutcome.TooLong // SEC-05
        val verdict = analyzer.analyze(Input(text, source, sender, origin))
        val id = if (saveHistory()) {
            history.save(verdict.level.name, Redactor.preview(text), verdict.reasonTexts(), text, VerdictJson.encode(verdict))
        } else null
        return CheckOutcome.Done(CheckResult(verdict, text, clock(), truncated, id, source, origin, sender))
    }

    companion object {
        const val MAX_CHARS = 10_000
    }
}
