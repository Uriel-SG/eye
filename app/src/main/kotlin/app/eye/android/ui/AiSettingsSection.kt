package app.eye.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.eye.android.R
import app.eye.android.core.ai.AiOutcome
import app.eye.android.core.ai.AiProvider
import app.eye.android.core.ai.AiService
import app.eye.android.core.ai.AiUrls
import app.eye.android.data.Settings

/** Impostazioni del secondo parere AI: provider, modello, chiave API (cifrata), prova connessione. */
@Composable
fun AiSettingsSection(vm: AppViewModel, s: Settings) {
    var provider by remember(s.aiProvider) { mutableStateOf(s.aiProvider) }
    var model by remember(s.aiModel) { mutableStateOf(s.aiModel) }
    var baseUrl by remember(s.aiBaseUrl) { mutableStateOf(s.aiBaseUrl) }
    var key by remember { mutableStateOf("") } // mai in rememberSaveable: non deve finire nello stato salvato
    var showKey by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val test by vm.aiTest.collectAsState()
    val keyError by vm.aiKeyError.collectAsState()
    var confirmRaw by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val revoked = stringResource(R.string.ai_revoke_consent_done)
    DisposableEffect(Unit) { onDispose { vm.clearAiTest(); vm.clearAiKeyError() } }

    val keyFlow = rememberGoogleKeyFlow(vm, model, baseUrl) { key = "" }

    val compatible = provider == AiProvider.COMPATIBLE
    val baseOk = !compatible || AiUrls.validBase(baseUrl) != null
    val modelOk = !compatible || model.isNotBlank()
    val valid = baseOk && modelOk

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NanoSettingsRow(vm)
        Text(stringResource(R.string.ai_section_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.ai_section_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text(stringResource(R.string.ai_provider_label), style = MaterialTheme.typography.titleMedium)
        Column(Modifier.selectableGroup()) {
            AiProvider.entries.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = provider == p, role = Role.RadioButton, onClick = { if (provider != p) { provider = p; model = "" } }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = provider == p, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(p.labelRes()), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        if (provider == AiProvider.GEMINI) {
            Text(stringResource(R.string.ai_gemini_howto), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(
                onClick = { keyFlow.open() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.ai_gemini_get_key)) }
            GeminiStepsAndLink()
        }
        if (compatible) {
            OutlinedTextField(
                value = baseUrl, onValueChange = { baseUrl = it.take(300) },
                label = { Text(stringResource(R.string.ai_base_url_label)) },
                singleLine = true, isError = baseUrl.isNotEmpty() && !baseOk,
                supportingText = { if (baseUrl.isNotEmpty() && !baseOk) Text(stringResource(R.string.ai_base_url_error)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = model, onValueChange = { model = it.take(100) },
            label = { Text(stringResource(R.string.ai_model_label)) },
            singleLine = true, isError = compatible && model.isBlank(),
            supportingText = {
                Text(
                    if (compatible) stringResource(R.string.ai_model_required)
                    else stringResource(R.string.ai_model_default_hint, provider.defaultModel),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = key, onValueChange = { key = it.take(500); vm.clearAiKeyError() },
            isError = keyError,
            label = { Text(stringResource(R.string.ai_key_label)) },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            supportingText = {
                Text(if (keyError) stringResource(R.string.ai_key_invalid) else if (s.aiHasKey) stringResource(R.string.ai_key_saved_hint) else stringResource(R.string.ai_key_note))
            },
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = stringResource(if (showKey) R.string.ai_key_hide else R.string.ai_key_show),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        if (provider == AiProvider.GEMINI) PasteKeyButton(keyFlow)
        Button(
            onClick = { vm.saveAi(provider, model, baseUrl, key); key = "" },
            enabled = valid,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text(stringResource(R.string.ai_save)) }

        val noKey = key.isBlank() && !s.aiHasKey
        OutlinedButton(
            onClick = { vm.testAi(provider, model, baseUrl, key) },
            enabled = valid && !noKey && test !is AiUiState.Loading,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text(stringResource(R.string.ai_test)) }
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            when (val t = test) {
                AiUiState.Idle -> if (noKey) Text(stringResource(R.string.ai_test_no_key), style = MaterialTheme.typography.bodyMedium)
                AiUiState.Loading -> Text(stringResource(R.string.ai_loading), style = MaterialTheme.typography.bodyMedium)
                is AiUiState.Done -> when {
                    AiService.connectionOk(t.outcome) -> Text(stringResource(R.string.ai_test_ok), style = MaterialTheme.typography.bodyLarge)
                    t.outcome is AiOutcome.Failure -> Text(aiErrorText(t.outcome), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (s.aiHasKey) {
            OutlinedButton(onClick = { confirmRemove = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.ai_remove_key), color = MaterialTheme.colorScheme.error)
            }
        }

        if (s.aiConfigured) AiToggleRows(vm, s)
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ai_raw_label), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.ai_raw_sub), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = s.aiSendUnredacted, onCheckedChange = { if (it) confirmRaw = true else vm.setAiSendUnredacted(false) })
        }
    }

    if (s.aiConsent) {
        OutlinedButton(onClick = { vm.setAiConsent(false); android.widget.Toast.makeText(ctx, revoked, android.widget.Toast.LENGTH_SHORT).show() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.ai_revoke_consent))
        }
    }
    if (confirmRaw) {
        AlertDialog(
            onDismissRequest = { confirmRaw = false },
            text = { Text(stringResource(R.string.ai_raw_confirm)) },
            confirmButton = { TextButton(onClick = { vm.setAiSendUnredacted(true); confirmRaw = false }) { Text(stringResource(R.string.action_activate)) } },
            dismissButton = { TextButton(onClick = { confirmRaw = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            text = { Text(stringResource(R.string.ai_remove_key_confirm)) },
            confirmButton = {
                TextButton(onClick = { vm.removeAiKey(); key = ""; confirmRemove = false }) {
                    Text(stringResource(R.string.ai_remove_key), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}


/** Riga informativa di sola lettura sullo stato di Gemini Nano (nessun interruttore: e' automatico). */
@Composable
private fun NanoSettingsRow(vm: AppViewModel) {
    val status by vm.nanoStatus.collectAsState()
    val downloading by vm.nanoDownloading.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshNano() }
    val st = status ?: return
    val res = when {
        st == app.eye.engine.llm.LlmStatus.AVAILABLE -> R.string.nano_info_active
        downloading || st == app.eye.engine.llm.LlmStatus.DOWNLOADING || st == app.eye.engine.llm.LlmStatus.DOWNLOADABLE -> R.string.nano_info_downloading
        else -> R.string.nano_info_unavailable
    }
    Text(
        stringResource(res), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontStyle = if (st == app.eye.engine.llm.LlmStatus.AVAILABLE) androidx.compose.ui.text.font.FontStyle.Normal else androidx.compose.ui.text.font.FontStyle.Italic,
    )
}

/** I due interruttori dell'AI collegata (analisi automatica, verifica prima degli avvisi): condivisi con l'onboarding. */
@Composable
fun AiToggleRows(vm: AppViewModel, s: Settings) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.ai_autoan_label), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.ai_autoan_sub), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = s.aiAutoAnalysis, onCheckedChange = { vm.setAiAutoAnalysis(it) })
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.ai_verify_label), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.ai_verify_sub), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = s.aiVerifyBeforeAlert, onCheckedChange = { vm.setAiVerifyBeforeAlert(it) })
    }
}

/** Tre passi brevissimi e link alla pagina con le immagini. */
@Composable
fun GeminiStepsAndLink() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Text(stringResource(R.string.ai_gemini_steps), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick = { openUrl(ctx, GEMINI_HELP_URL) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.ai_gemini_help_images)) }
}

const val GEMINI_HELP_URL = "https://eye.urielsec.com/gemini.html"
const val REDACTION_HELP_URL = "https://eye.urielsec.com/oscuramento.html"

fun openUrl(ctx: android.content.Context, url: String) {
    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
}

/** Flusso "Ottieni chiave" + "Incolla chiave". Gli appunti si leggono solo su azione dell'utente o al ritorno dalla pagina aperta. */
class GoogleKeyFlow(
    private val vm: AppViewModel,
    private val ctx: android.content.Context,
    val opened: Boolean,
    val clipPresent: Boolean,
    private val onOpen: () -> Unit,
    private val onSaved: () -> Unit,
    private val model: String,
    private val baseUrl: String,
) {
    val canPaste get() = opened && clipPresent
    fun open() { onOpen(); vm.googleKeyPending = true; vm.googleKeyLeft = false; openGoogleKeyPage(ctx) }
    fun paste() {
        val clip = readClip(ctx)
        if (vm.saveGoogleKeyFromClipboard(clip, AiProvider.GEMINI, model, baseUrl)) {
            onSaved()
            android.widget.Toast.makeText(ctx, ctx.getString(R.string.ai_key_from_clipboard), android.widget.Toast.LENGTH_LONG).show()
        } else android.widget.Toast.makeText(ctx, ctx.getString(R.string.ai_key_paste_invalid), android.widget.Toast.LENGTH_LONG).show()
    }
}

private fun readClip(ctx: android.content.Context): String? = runCatching {
    (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()
}.getOrNull()

@Composable
fun rememberGoogleKeyFlow(vm: AppViewModel, model: String, baseUrl: String, onSaved: () -> Unit): GoogleKeyFlow {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var opened by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(true) }
    var clipPresent by remember { mutableStateOf(false) }
    fun hasClip() = runCatching {
        (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).hasPrimaryClip()
    }.getOrDefault(false) // non legge il contenuto
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> { resumed = false; if (vm.googleKeyPending) vm.googleKeyLeft = true }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> { resumed = true; clipPresent = hasClip() }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val focused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    val clipDone = stringResource(R.string.ai_key_from_clipboard)
    val onSavedNow by androidx.compose.runtime.rememberUpdatedState(onSaved)
    androidx.compose.runtime.LaunchedEffect(focused, resumed) {
        if (focused && resumed) clipPresent = hasClip()
        if (focused && resumed && vm.googleKeyPending && vm.googleKeyLeft) {
            vm.googleKeyPending = false; vm.googleKeyLeft = false
            if (vm.saveGoogleKeyFromClipboard(readClip(ctx), AiProvider.GEMINI, model, baseUrl)) {
                onSavedNow()
                android.widget.Toast.makeText(ctx, clipDone, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    return GoogleKeyFlow(vm, ctx, opened, clipPresent, { opened = true }, onSavedNow, model, baseUrl)
}

@Composable
fun PasteKeyButton(flow: GoogleKeyFlow) {
    OutlinedButton(onClick = { flow.paste() }, enabled = flow.canPaste, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(stringResource(R.string.ai_key_paste))
    }
}

/** Pagina delle chiavi di Google AI Studio nel browser (Custom Tab non e' tra le dipendenze del progetto: ACTION_VIEW). */
private fun openGoogleKeyPage(ctx: android.content.Context) {
    val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://aistudio.google.com/app/apikey"))
    runCatching { ctx.startActivity(i) }
}
