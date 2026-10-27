package app.eye.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.ui.text.font.FontWeight
import app.eye.android.data.NanoOpinionJson
import app.eye.engine.llm.LlmOpinion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import app.eye.android.R
import app.eye.android.core.titleRes
import app.eye.engine.Input
import app.eye.engine.Level
import app.eye.engine.Source
import app.eye.engine.Verdict
import app.eye.engine.llm.LegitGate
import app.eye.engine.llm.LlmCombiner
import app.eye.engine.llm.LlmStatus
import app.eye.engine.llm.LlmVerdict

/**
 * Secondo parere di Gemini Nano sul telefono. Solo con l'app in primo piano (RESUMED); il verdetto delle regole e' gia'
 * mostrato sopra. Parte da sola se il modello e' AVAILABLE (nessun interruttore): altrimenti non emette nulla.
 * Puo' alzare il livello (mai abbassarlo): vedi [LlmCombiner].
 */
@Composable
fun NanoSecondOpinion(vm: AppViewModel, text: String, verdict: Verdict?, historyId: Long? = null) {
    val status by vm.nanoStatus.collectAsState()
    val session by vm.nanoSession.collectAsState()
    val stored = rememberStoredNano(vm, historyId)
    val live = (if (session.key == aiKey(text)) session.state else null) as? NanoUiState.Done
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val foreground = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val active = verdict != null && text.isNotBlank() && status == LlmStatus.AVAILABLE
    // parere gia' salvato con la voce di cronologia: si mostra senza richiamare Nano
    val needAsk = active && stored == null

    LaunchedEffect(Unit) { vm.refreshNano() }
    DisposableEffect(Unit) { onDispose { vm.clearNano() } }
    LaunchedEffect(needAsk, foreground, text, session.key) {
        if (needAsk && foreground) vm.askNano(Input(text, Source.PASTE), verdict)
    }
    // salva (cifrato) il parere appena ricevuto con la voce, se c'e'
    val liveOp = live?.opinion
    LaunchedEffect(historyId, liveOp, stored == null) {
        if (liveOp != null && historyId != null && stored == null && verdict != null) {
            vm.saveNanoOpinion(historyId, liveOp, nanoFinal(verdict, liveOp).level.takeIf { it != verdict.level })
        }
    }
    if (verdict == null || (!active && stored == null)) return

    val state: NanoUiState = when {
        stored != null -> NanoUiState.Done(stored)
        live != null -> live
        else -> NanoUiState.Loading
    }
    val alreadyLowered = verdict.reasons.any { it.ruleId == LegitGate.NANO_REASON_ID }
    val contradictsCard = !alreadyLowered && LlmCombiner.contradictsRules(verdict.level, (state as? NanoUiState.Done)?.opinion) && nanoFinal(verdict, (state as? NanoUiState.Done)?.opinion).level == verdict.level
    EyeCard(
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        borderOverride = BorderStroke(2.dp, if (contradictsCard) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                SectionTitle(stringResource(R.string.nano_card_title))
            }
            when (state) {
                NanoUiState.Idle, NanoUiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.nano_loading), style = MaterialTheme.typography.bodyLarge)
                }
                is NanoUiState.Done -> {
                    val op = state.opinion
                    if (op == null) {
                        Text(stringResource(R.string.nano_unavailable), style = MaterialTheme.typography.bodyLarge)
                        OutlinedButton(onClick = { vm.clearNano() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.nano_retry)) }
                    } else {
                        val raised = LlmCombiner.apply(verdict, op).level != verdict.level
                        val contradicts = !alreadyLowered && LlmCombiner.contradictsRules(verdict.level, op) && nanoFinal(verdict, op).level == verdict.level
                        val verdictRes = when (op.verdict) {
                            LlmVerdict.TRUFFA -> R.string.nano_verdict_truffa
                            LlmVerdict.SOSPETTO -> R.string.nano_verdict_sospetto
                            LlmVerdict.LEGITTIMO -> R.string.nano_verdict_legittimo
                        }
                        // Nano dice "legittimo" ma Eye ha trovato rischio: titolo neutro, niente verde, vince Eye
                        Text(stringResource(if (contradicts) R.string.nano_different_title else verdictRes), style = MaterialTheme.typography.titleMedium)
                        Text(op.explanation, style = MaterialTheme.typography.bodyLarge)
                        if (contradicts) {
                            val strong = verdict.level == Level.PROBABILE_TRUFFA
                            Row(
                                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp)
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                                verticalAlignment = Alignment.Top,
                            ) {
                                Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(if (strong) R.string.nano_protect_strong else R.string.nano_protect),
                                    style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        } else if (op.actions.isNotEmpty()) {
                            Text(stringResource(R.string.nano_advice, op.actions.joinToString("; ")), style = MaterialTheme.typography.bodyLarge)
                        }
                        if (raised) {
                            Text(stringResource(R.string.nano_raised, stringResource(Level.SOSPETTO.titleRes())), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                        } else if (contradicts) {
                            Text(stringResource(R.string.nano_no_lowering), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(stringResource(R.string.nano_privacy), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Livello dopo il parere di Nano: puo' alzare NESSUN_SEGNALE -> SOSPETTO o abbassare di un gradino (vedi [LegitGate.applyNano]). */
private fun nanoFinal(v: Verdict, op: LlmOpinion?): Verdict = LegitGate.applyNano(LlmCombiner.apply(v, op), op)

/** Parere Nano salvato nella voce di cronologia [historyId], se c'e' (decifrato dal repository). */
@Composable
private fun rememberStoredNano(vm: AppViewModel, historyId: Long?): LlmOpinion? {
    val history by vm.history.collectAsState()
    if (historyId == null) return null
    return history.firstOrNull { it.id == historyId }?.nanoOpinion?.let(NanoOpinionJson::decode)
}

/** Parere Nano valido per [text]: quello appena ricevuto, altrimenti quello salvato con la voce di cronologia. */
@Composable
fun nanoOpinionFor(vm: AppViewModel, text: String, historyId: Long?): LlmOpinion? {
    val session by vm.nanoSession.collectAsState()
    val live = (if (session.key == aiKey(text)) session.state else null) as? NanoUiState.Done
    return live?.opinion ?: rememberStoredNano(vm, historyId)
}
