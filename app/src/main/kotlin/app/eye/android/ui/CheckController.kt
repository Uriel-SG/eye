package app.eye.android.ui

import app.eye.android.core.CheckOutcome
import app.eye.android.core.CheckResult
import app.eye.android.core.CheckUseCase
import app.eye.engine.Source
import app.eye.engine.provenance.ProvenanceReport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface CheckState {
    data object Idle : CheckState
    data object Running : CheckState
    data class Done(val result: CheckResult) : CheckState
    data object TooLong : CheckState
    data object Error : CheckState
    /** OCR in corso su un'immagine (condivisa o scelta). */
    data object Reading : CheckState
    data object NoText : CheckState
    data object OcrUnavailable : CheckState
}

/** Logica di controllo senza dipendenze Android (testabile su JVM). */
class CheckController(
    private val scope: CoroutineScope,
    private val useCase: CheckUseCase,
    private val dispatcher: CoroutineDispatcher,
    /** Rifinitura opzionale del risultato prima di mostrarlo (parere di Gemini Nano). Mai propaga eccezioni. */
    private val refine: (suspend (CheckResult) -> CheckResult)? = null,
) {
    private val _state = MutableStateFlow<CheckState>(CheckState.Idle)
    val state: StateFlow<CheckState> = _state

    fun reset() { _state.value = CheckState.Idle }

    /** Immagine senza testo: scheda con la sola provenienza (vedi [CheckUseCase.noText]). */
    fun noText(provenance: ProvenanceReport?) { _state.value = CheckState.Done(useCase.noText(provenance).result) }
    fun set(s: CheckState) { _state.value = s }

    /** Testo da Share Target: analisi automatica; oltre il limite si controllano i primi MAX_CHARS caratteri. */
    fun onShared(text: String?, origin: String? = null, sender: String? = null) {
        if (text.isNullOrBlank()) return
        check(text, Source.SHARE, origin = origin, sender = sender)
    }

    /** [alreadyTruncated]: il chiamante ha gia' tagliato il testo (es. campo di input). */
    fun check(text: String, source: Source = Source.PASTE, alreadyTruncated: Boolean = false, origin: String? = null, sender: String? = null, provenance: ProvenanceReport? = null,
        nature: app.eye.engine.image.ImageNature = app.eye.engine.image.ImageNature.OTHER, hasQr: Boolean = false) {
        val truncated = alreadyTruncated || text.length > CheckUseCase.MAX_CHARS
        val t = text.take(CheckUseCase.MAX_CHARS)
        _state.value = CheckState.Running
        scope.launch {
            _state.value = try {
                when (val o = withContext(dispatcher) { useCase.run(t, source, truncated, origin, sender, provenance, nature, hasQr) }) {
                    is CheckOutcome.Done -> CheckState.Done(refine?.let { runCatching { it(o.result) }.getOrDefault(o.result) } ?: o.result)
                    CheckOutcome.TooLong -> CheckState.TooLong
                    CheckOutcome.Empty -> CheckState.Error
                }
            } catch (_: Exception) {
                CheckState.Error
            }
        }
    }
}
