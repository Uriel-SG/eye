package app.eye.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import app.eye.android.R
import app.eye.android.core.ExternalIntents
import app.eye.android.core.age.DomainAgeResult
import app.eye.android.data.DomainAgeJson
import app.eye.engine.Verdict
import app.eye.engine.enrich.DomainAge
import app.eye.engine.enrich.VirusTotal

/** Eta' del dominio (RDAP) salvata con la voce di cronologia [historyId], se c'e'. */
@Composable
private fun rememberStoredAge(vm: AppViewModel, historyId: Long?): DomainAgeResult? {
    val history by vm.history.collectAsState()
    if (historyId == null) return null
    return history.firstOrNull { it.id == historyId }?.domainAge?.let(DomainAgeJson::decode)
}

/** Eta' dei domini valida per [text]: quella appena controllata, altrimenti quella salvata con la voce di cronologia. */
@Composable
fun domainAgeFor(vm: AppViewModel, text: String, historyId: Long?): DomainAgeResult? {
    val session by vm.ageSession.collectAsState()
    val live = (if (session.key == aiKey(text)) session.state else null) as? AgeUiState.Done
    return live?.result ?: rememberStoredAge(vm, historyId)
}

/**
 * Controllo dell'eta' del sito. Solo con l'app in primo piano (RESUMED) e con l'impostazione attiva; il verdetto delle regole
 * e' gia' mostrato: il risultato arriva dopo e puo' solo alzare il livello ([DomainAge.apply]). Mai dal servizio notifiche.
 * [baseVerdict] e' il verdetto prima dell'eta'.
 */
@Composable
fun DomainAgeCheck(vm: AppViewModel, text: String, baseVerdict: Verdict, historyId: Long?) {
    val settings by vm.settings.collectAsState()
    val session by vm.ageSession.collectAsState()
    val stored = rememberStoredAge(vm, historyId)
    val key = aiKey(text)
    val live = (if (session.key == key) session.state else null)
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val foreground = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val domains = remember(baseVerdict.extracted.domains) { vm.ageCandidates(baseVerdict.extracted.domains) }
    val settingsLoaded = settings.onboardingSeen != null // prima del caricamento vale il predefinito: non si decide nulla
    val needAsk = settingsLoaded && settings.domainAgeCheck && domains.isNotEmpty() && text.isNotBlank() && stored == null

    DisposableEffect(Unit) { onDispose { vm.clearDomainAge() } }
    LaunchedEffect(needAsk, foreground, key) {
        if (needAsk && foreground) vm.askDomainAge(key, domains)
    }
    val done = (live as? AgeUiState.Done)?.result
    LaunchedEffect(historyId, done, stored == null) {
        if (done != null && !done.incomplete && historyId != null && stored == null) {
            val raised = DomainAge.apply(baseVerdict, done.entries, done.checkedAt).level.takeIf { it != baseVerdict.level }
            vm.saveDomainAge(historyId, done, raised)
        }
    }

    if (settingsLoaded && !settings.domainAgeCheck) return // impostazione disattivata: nessuna riga relativa all'eta'
    val shown: DomainAgeResult? = stored ?: done
    val loading = shown == null && needAsk && (live == null || live == AgeUiState.Loading || live == AgeUiState.Idle)
    if (!loading && shown == null) return
    if (shown != null && shown.entries.isEmpty() && !shown.incomplete) return

    EyeCard(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.age_card_title))
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.age_loading), style = MaterialTheme.typography.bodyLarge)
                }
            } else if (shown != null) {
                val unavailable = stringResource(R.string.age_unavailable)
                if (shown.entries.isEmpty()) Text(unavailable, style = MaterialTheme.typography.bodyLarge)
                shown.entries.forEach { e ->
                    val reg = e.registeredAtMs
                    Text(
                        if (reg == null) stringResource(R.string.age_line_unknown, e.domain, unavailable)
                        else stringResource(R.string.age_line, e.domain, DomainAge.humanAge(DomainAge.ageDays(reg, shown.checkedAt))),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (done != null && stored == null) {
                    Text(stringResource(R.string.age_privacy), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * «Apri su VirusTotal»: visibile solo se il verdetto ha almeno un dominio. Si apre la pagina del DOMINIO (mai l'URL completo)
 * nel browser, dopo un avviso mostrato una sola volta (con «non mostrare piu»).
 */
@Composable
fun VirusTotalButton(vm: AppViewModel, verdict: Verdict) {
    val domains = remember(verdict.extracted.domains) { VirusTotal.domainsFor(verdict) }
    if (domains.isEmpty()) return
    val settings by vm.settings.collectAsState()
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) }
    var never by remember { mutableStateOf(false) }

    fun open(domain: String) {
        val url = VirusTotal.urlFor(domain) ?: return
        ExternalIntents.openInBrowser(ctx, url)
    }
    fun choose(domain: String) {
        if (settings.vtNoticeDismissed) open(domain) else pending = domain
    }

    Column {
        OutlinedButton(
            onClick = { if (domains.size == 1) choose(domains[0]) else menu = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Icon(Icons.Rounded.Language, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.vt_button))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            domains.forEach { d ->
                DropdownMenuItem(text = { Text(d) }, onClick = { menu = false; choose(d) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
    }
    pending?.let { d ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.vt_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.vt_dialog_body), style = MaterialTheme.typography.bodyLarge)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = never, role = Role.Checkbox, onValueChange = { never = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = never, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.vt_dialog_never), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (never) vm.setVtNoticeDismissed()
                    pending = null
                    open(d)
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_open)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
