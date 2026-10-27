package app.eye.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.eye.android.R
import app.eye.android.core.ai.AiError
import app.eye.android.core.ai.AiJudgement
import app.eye.android.core.ai.AiOutcome
import app.eye.android.core.ai.AiProvider
import app.eye.android.data.AiOpinionJson
import app.eye.android.data.SavedAiOpinion
import app.eye.engine.Level

fun AiProvider.labelRes(): Int = when (this) {
    AiProvider.ANTHROPIC -> R.string.ai_provider_anthropic
    AiProvider.GEMINI -> R.string.ai_provider_gemini
    AiProvider.OPENAI -> R.string.ai_provider_openai
    AiProvider.COMPATIBLE -> R.string.ai_provider_compatible
}

/** Nome del servizio per i testi all'utente; per i compatibili con l'host di destinazione. */
@Composable
fun providerLabel(p: AiProvider, baseUrl: String): String {
    val name = stringResource(p.labelRes())
    if (p != AiProvider.COMPATIBLE) return name
    val host = runCatching { java.net.URI(baseUrl.trim()).host }.getOrNull().orEmpty()
    return if (host.isEmpty()) name else "$name ($host)"
}

@Composable
fun aiErrorText(f: AiOutcome.Failure): String = when (f.error) {
    AiError.BAD_KEY -> stringResource(R.string.ai_key_invalid)
    AiError.NOT_CONFIGURED -> stringResource(R.string.ai_err_not_configured)
    AiError.NETWORK -> stringResource(R.string.ai_err_network)
    AiError.TIMEOUT -> stringResource(R.string.ai_err_timeout)
    AiError.AUTH -> stringResource(R.string.ai_err_auth)
    AiError.RATE_LIMIT -> stringResource(R.string.ai_err_rate)
    AiError.FREE_LIMIT -> stringResource(R.string.ai_err_free_limit)
    AiError.OVERLOADED -> stringResource(R.string.ai_err_overloaded, f.httpCode)
    AiError.HTTP -> stringResource(R.string.ai_err_http, f.httpCode)
    AiError.REFUSED -> stringResource(R.string.ai_err_refused)
    AiError.TRUNCATED -> stringResource(R.string.ai_err_truncated)
    AiError.INVALID -> stringResource(R.string.ai_err_invalid)
}

/**
 * Secondo parere AI: visibile solo se configurato, mai automatico, in una card con contorno blu e intestazione propria
 * (distinta dal verdetto di Eye). Non modifica mai il verdetto delle regole. Il parere ricevuto resta salvato (cifrato)
 * nella voce di cronologia [historyId], se c'è, e si rivede senza rifare la richiesta.
 */
@Composable
fun AiSecondOpinion(vm: AppViewModel, text: String, rulesLevel: Level, historyId: Long? = null, verdict: app.eye.engine.Verdict? = null) {
    NanoSecondOpinion(vm, text, verdict, historyId) // gratuito e locale: se disponibile e' il parere predefinito
    AiKeyOpinion(vm, text, rulesLevel, historyId)
}

/** Parere AI automatico (parte del verdetto) valido per [text]: quello appena ricevuto o quello salvato in cronologia. */
@Composable
fun autoAiFor(vm: AppViewModel, text: String, historyId: Long?): SavedAiOpinion? {
    val session by vm.ai.collectAsState()
    val history by vm.history.collectAsState()
    val live = (if (session.key == aiKey(text)) session.state else null) as? AiUiState.Done
    val s = live?.saved?.takeIf { it.auto } ?: historyId?.let { id -> history.firstOrNull { it.id == id }?.aiOpinion }?.let(AiOpinionJson::decode)
    return s?.takeIf { it.auto }
}

@Composable
private fun AiKeyOpinion(vm: AppViewModel, text: String, rulesLevel: Level, historyId: Long?) {
    val settings by vm.settings.collectAsState()
    val session by vm.ai.collectAsState()
    val history by vm.history.collectAsState()
    var consent by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { vm.leaveAi() } }
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshNano() }
    val nanoStatus by vm.nanoStatus.collectAsState()
    // senza chiave il pulsante c'e' solo se Gemini Nano non e' disponibile (se lo e', basta Nano)
    val free = !settings.aiConfigured
    if (text.isBlank() || (free && (nanoStatus == null || nanoStatus == app.eye.engine.llm.LlmStatus.AVAILABLE))) return

    val state = if (session.key == aiKey(text)) session.state else AiUiState.Idle
    val stored = historyId?.let { id -> history.firstOrNull { it.id == id }?.aiOpinion }?.let(AiOpinionJson::decode)
    val shown: SavedAiOpinion? = when (state) {
        is AiUiState.Done -> state.saved
        AiUiState.Idle -> stored
        AiUiState.Loading -> null
    }
    val loading = state == AiUiState.Loading
    val hasCard = loading || shown != null || state is AiUiState.Done
    val ask = { if (if (free) settings.aiFreeConsent else settings.aiConsent) vm.askAi(text, historyId) else consent = true }

    if (!hasCard) {
        OutlinedButton(onClick = ask, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ai_ask_button))
        }
    } else {
        EyeCard(
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            borderOverride = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    SectionTitle(stringResource(R.string.ai_card_title))
                }
                when {
                    loading -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.ai_loading), style = MaterialTheme.typography.bodyLarge)
                        }
                        TextButton(onClick = { vm.cancelAi() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.ai_cancel)) }
                    }
                    shown != null -> OpinionBody(shown, rulesLevel) {
                        OutlinedButton(onClick = ask, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.ai_ask_again)) }
                    }
                    state is AiUiState.Done -> {
                        (state.outcome as? AiOutcome.Failure)?.let {
                            Text(aiErrorText(it), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                        }
                        OutlinedButton(onClick = ask, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.ai_ask_again)) }
                    }
                }
            }
        }
    }

    if (consent) {
        AlertDialog(
            onDismissRequest = { consent = false },
            title = { Text(stringResource(if (free) R.string.ai_free_consent_title else R.string.ai_consent_title)) },
            text = {
                Text(if (free) stringResource(R.string.ai_free_consent_body) else stringResource(R.string.ai_consent_body, providerLabel(settings.aiProvider, settings.aiBaseUrl)))
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (free) TextButton(onClick = {
                        consent = false
                        vm.askAi(text, historyId, consentNow = true)
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.ai_free_consent_once)) }
                    TextButton(onClick = {
                        consent = false
                        if (free) vm.setAiFreeConsent(true) else vm.setAiConsent(true)
                        vm.askAi(text, historyId, consentNow = true)
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(if (free) R.string.ai_free_consent_always else R.string.ai_consent_accept)) }
                }
            },
            dismissButton = { TextButton(onClick = { consent = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun OpinionBody(saved: SavedAiOpinion, rulesLevel: Level, again: @Composable () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val op = saved.opinion
    if (op.softerThan(rulesLevel)) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ai_warning_softer), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
    val judgement = when (op.judgement) {
        AiJudgement.TRUFFA -> R.string.ai_judgement_truffa
        AiJudgement.SOSPETTO -> R.string.ai_judgement_sospetto
        AiJudgement.NESSUN_SEGNALE -> R.string.ai_judgement_nessun_segnale
    }
    Text(stringResource(judgement), style = MaterialTheme.typography.titleMedium)
    op.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge) }
    if (op.advice.isNotEmpty()) Text(stringResource(R.string.ai_advice, op.advice), style = MaterialTheme.typography.bodyLarge)
    val providerName = stringResource(if (saved.providerId == FREE_PROVIDER_ID) R.string.ai_provider_pollinations else AiProvider.fromId(saved.providerId).labelRes())
    val whenText = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(saved.at))
    Text(
        stringResource(R.string.ai_saved_meta, providerName, saved.model.ifEmpty { "?" }, whenText),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(stringResource(R.string.ai_no_guarantee), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.ai_no_contacts), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val subject = stringResource(R.string.ai_report_subject)
    val body = stringResource(R.string.ai_report_body, providerName, stringResource(judgement), op.reasons.joinToString("; "), op.advice)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        again()
        TextButton(onClick = { app.eye.android.core.ExternalIntents.shareText(ctx, body, subject) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.ai_report_button))
        }
    }
}
