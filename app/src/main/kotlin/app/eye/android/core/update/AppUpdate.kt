package app.eye.android.core.update

import kotlinx.coroutines.CoroutineScope
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Stato dell'aggiornamento (flusso flexible di Google Play) come lo vede l'app. */
enum class UpdatePhase { AVAILABLE, DOWNLOADING, DOWNLOADED }

/**
 * [versionCode] = versione disponibile (0 se non nota, es. a download in corso). Chi implementa [UpdateClient] emette
 * solo AVAILABLE se il tipo flexible e' consentito.
 */
data class UpdateSnapshot(val phase: UpdatePhase, val versionCode: Int)

/** «Più tardi»: nasconde la scheda per [UpdatePolicy.SNOOZE_MS] solo per quella versione. */
data class UpdateSnooze(val versionCode: Int = 0, val untilMs: Long = 0)

/** Cosa mostrare in Home: mai un popup, solo una scheda. */
sealed interface UpdateCardState {
    data object Hidden : UpdateCardState
    data class Available(val versionCode: Int) : UpdateCardState
    data object Downloading : UpdateCardState
    data object Ready : UpdateCardState
}

object UpdatePolicy {
    const val SNOOZE_MS = 3L * 24 * 60 * 60 * 1000

    /** Logica pura: [snapshot] null = nessun aggiornamento / Play non disponibile / errore. */
    fun decide(snapshot: UpdateSnapshot?, snooze: UpdateSnooze, now: Long): UpdateCardState = when (snapshot?.phase) {
        null -> UpdateCardState.Hidden
        UpdatePhase.DOWNLOADED -> UpdateCardState.Ready // l'utente ha gia' scelto di aggiornare: mai rimandata
        UpdatePhase.DOWNLOADING -> UpdateCardState.Downloading
        UpdatePhase.AVAILABLE ->
            if (snooze.versionCode == snapshot.versionCode && now < snooze.untilMs) UpdateCardState.Hidden
            else UpdateCardState.Available(snapshot.versionCode)
    }

    fun snoozeFor(versionCode: Int, now: Long) = UpdateSnooze(versionCode, now + SNOOZE_MS)
}

/** Piccola interfaccia sopra Play In-App Updates (così la logica si prova senza Play). */
interface UpdateClient {
    /** Stato dell'aggiornamento mentre la Home e' osservata. Gli errori possono far fallire il flusso: chi lo usa li tratta come «nessuna scheda». */
    fun snapshots(): Flow<UpdateSnapshot?>
    /** Avvia il flusso flexible; Il risultato (anche l'annullamento) arriva al [launcher]. False = non avviato. */
    fun start(launcher: ActivityResultLauncher<IntentSenderRequest>): Boolean
    /** Installa l'aggiornamento scaricato (riavvia l'app). */
    fun complete()
}

interface UpdateSnoozeStore {
    val snooze: Flow<UpdateSnooze>
    suspend fun set(s: UpdateSnooze)
}

/** Collega client, snooze e orologio nello stato della scheda. */
class UpdateController(
    private val client: UpdateClient,
    private val store: UpdateSnoozeStore,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val state: StateFlow<UpdateCardState> = combine(
        client.snapshots().catch { emit(null) }, // Play assente, app non installata da Play, errore di rete: nessuna scheda
        store.snooze,
    ) { snap, snooze ->
        UpdatePolicy.decide(snap, snooze, clock())
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), UpdateCardState.Hidden)

    suspend fun later() {
        val s = state.value
        if (s is UpdateCardState.Available) store.set(UpdatePolicy.snoozeFor(s.versionCode, clock()))
    }

    fun start(launcher: ActivityResultLauncher<IntentSenderRequest>): Boolean = runCatching { client.start(launcher) }.getOrDefault(false)
    fun restart() { runCatching { client.complete() } }
}
