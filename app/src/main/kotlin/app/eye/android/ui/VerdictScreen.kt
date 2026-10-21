package app.eye.android.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.eye.android.core.CheckUseCase
import app.eye.engine.Level
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import app.eye.android.core.ShareTexts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ClipEntry
import android.content.ClipData
import app.eye.android.R
import app.eye.android.core.CheckResult
import app.eye.android.core.ExternalIntents
import app.eye.android.core.ReasonTone
import app.eye.android.core.ReportBuilder
import app.eye.android.core.reasonTone
import app.eye.android.core.Redactor
import app.eye.android.core.ReportLink
import app.eye.android.core.ShareCard
import app.eye.android.core.familyLineRes
import app.eye.android.core.reasonItems
import app.eye.android.core.reasonTexts
import app.eye.android.core.subtitleRes
import app.eye.android.core.titleRes
import app.eye.android.core.todoSteps
import app.eye.android.ui.theme.extras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val OfficialChannels = listOf(
    ReportLink("Polizia Postale", app.eye.android.core.POLIZIA_POSTALE_URL),
    ReportLink("CERT-AgID", "https://cert-agid.gov.it"),
)

@Composable
fun VerdictScreen(result0: CheckResult, vm: AppViewModel, onClose: () -> Unit, fromHistory: Boolean = false, onExceptions: () -> Unit = {}) {
    val ctx = LocalContext.current
    var updated by remember { mutableStateOf<app.eye.engine.Verdict?>(null) } // dopo una nuova eccezione
    var entryId by remember { mutableStateOf(result0.historyId) }
    val baseV = updated ?: result0.verdict
    // parere di Gemini Nano (appena ricevuto o salvato in cronologia): se alza il livello vale ovunque, anche in condivisione e PDF
    // eta' del dominio (RDAP, solo in primo piano): puo' solo alzare il livello; poi l'eventuale parere Nano
    val ageResult = domainAgeFor(vm, result0.originalText, entryId)
    val agedV = if (ageResult == null) baseV else app.eye.engine.enrich.DomainAge.apply(baseV, ageResult.entries, ageResult.checkedAt)
    val v = app.eye.engine.llm.LlmCombiner.apply(agedV, nanoOpinionFor(vm, result0.originalText, entryId))
    val result = if (v !== result0.verdict) result0.copy(verdict = v) else result0 // condivisione, report e PDF riflettono il verdetto aggiornato (eccezione, parere Nano)
    var excDialog by remember { mutableStateOf<app.eye.engine.ExceptionKind?>(null) }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val excSaved = stringResource(R.string.exc_saved)
    val excFailed = stringResource(R.string.exc_save_failed)
    val family = MaterialTheme.extras.familyMode
    val colors = MaterialTheme.extras.of(v.level)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var showMessage by remember { mutableStateOf(true) } // «Includi il messaggio»: attivo a ogni apertura, l'utente può disattivarlo
    var showSensitive by remember { mutableStateOf(false) } // solo dettaglio cronologia: testo oscurato di default
    var expanded by remember { mutableStateOf(false) }
    var reportDialog by remember { mutableStateOf(false) }
    var openLink by remember { mutableStateOf<ReportLink?>(null) }
    var wrongDialog by remember { mutableStateOf(false) }
    var pdfDialog by remember { mutableStateOf(false) }

    var createdForBookmark by remember { mutableStateOf(false) }
    val history by vm.history.collectAsState()
    val entry = history.firstOrNull { it.id == entryId }
    val saved = entry?.saved == true
    // eccezione: voce marcata in cronologia oppure motivo USR-01/USR-02 nel verdetto
    val excKind = entry?.exceptionKind?.takeIf { it > 0 } ?: CheckUseCase.exceptionKindOf(v)

    SecureWindow(fromHistory && showSensitive)

    val title = stringResource(if (excKind == 1) R.string.exc_entry_title else if (excKind == 2) R.string.exc_entry_title_suspicious else v.level.titleRes())
    val subtitle = stringResource(v.level.subtitleRes())
    val cd = stringResource(R.string.cd_verdict, title, subtitle)
    val cardFail = stringResource(R.string.verdict_card_error)
    val copied = stringResource(R.string.verdict_copied)
    val reasons = v.reasonTexts()

    fun shareCard() {
        scope.launch {
            try {
                val uri = withContext(Dispatchers.Default) {
                    ShareCard.save(ctx, ShareCard.render(ctx, result, showMessage))
                }
                val alt = ctx.getString(R.string.verdict_card_alt, title, reasons.take(3).joinToString(" "))
                ctx.startActivity(ShareCard.shareIntent(ctx, uri, alt).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                Toast.makeText(ctx, cardFail, Toast.LENGTH_SHORT).show()
            }
        }
    }

    ScreenScaffold(stringResource(R.string.app_name), onBack = onClose, snackbar = snackbar) {
        // Card verdetto: icona + titolo + colore + barra 8dp (mai solo colore)
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                .semantics(mergeDescendants = true) { contentDescription = cd; liveRegion = LiveRegionMode.Polite },
        ) {
            Box(Modifier.width(8.dp).fillMaxHeight().background(colors.bar))
            Column(
                Modifier.weight(1f).background(colors.container).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val iconTint = if (excKind > 0 && !family) MaterialTheme.extras.of(Level.NESSUN_SEGNALE).icon else colors.icon
                // pupilla verde leggibile sul fondo della card: più chiara su fondo scuro, più scura su fondo chiaro
                val pupil = if (colors.container.luminance() < 0.4f) Color(0xFF00E676) else Color(0xFF007A3D)
                LevelIcon(v.level, iconTint, Modifier.size(if (family) 72.dp else 48.dp), pupil)
                Text(title, style = MaterialTheme.typography.displaySmall, color = colors.text)
                Text(if (family) stringResource(v.level.familyLineRes()) else subtitle, style = MaterialTheme.typography.bodyLarge, color = colors.text)
            }
        }

        if (excKind > 0) {
            EyeCard(borderOverride = BorderStroke(2.dp, MaterialTheme.extras.of(Level.NESSUN_SEGNALE).icon)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.exc_banner), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onExceptions, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.exc_banner_link)) }
                }
            }
        }

        if (result.truncated) Text(stringResource(R.string.truncated_warning), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)

        // Anteprima: testo dell'utente, solo in memoria; i link restano testo, mai aperti
        EyeCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(stringResource(R.string.verdict_preview))
                val shown = if (fromHistory && !showSensitive) Redactor.redact(result.originalText) else result.originalText
                if (fromHistory) {
                    if (shown.isBlank()) Text(stringResource(R.string.history_detail_missing), style = MaterialTheme.typography.bodyMedium)
                    else Text(stringResource(R.string.history_sensitive_hidden_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(shown, maxLines = if (expanded) Int.MAX_VALUE else 3, style = MaterialTheme.typography.bodyLarge)
                if (fromHistory && result.originalText.isNotBlank()) {
                    TextButton(onClick = { showSensitive = !showSensitive }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(if (showSensitive) R.string.history_hide_sensitive else R.string.history_show_sensitive))
                    }
                }
                if (shown.length > 120 || shown.count { it == '\n' } > 2) {
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(if (expanded) R.string.action_close else R.string.action_show_all))
                    }
                }
                v.extracted.domains.distinct().take(3).forEach {
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            append(stringResource(R.string.verdict_domain) + " ")
                            pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)); append(it); pop()
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // Perché
        EyeCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(stringResource(R.string.verdict_why))
                if (reasons.isEmpty()) Text(stringResource(R.string.verdict_why_none))
                v.reasonItems().forEach { r -> ReasonRow(r) }
            }
        }

        // Cosa fare ora
        val steps = v.todoSteps(ctx)
        EyeCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(stringResource(R.string.verdict_todo), color = MaterialTheme.extras.green)
                steps.forEachIndexed { i, s ->
                    val stepCd = stringResource(R.string.cd_step, i + 1, steps.size)
                    Text("${i + 1}. $s", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.extras.green, modifier = Modifier.semantics { contentDescription = "$stepCd. $s" })
                }
            }
        }

        // Secondo parere AI: facoltativo, mai automatico, mai al posto del verdetto delle regole
        DomainAgeCheck(vm, result.originalText, baseV, entryId)
        AiSecondOpinion(vm, result.originalText, v.level, entryId, agedV)

        Text(stringResource(R.string.verdict_disclaimer_short), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // Eccezioni dell'utente: falso positivo / falso negativo (con conferma)
        OutlinedButton(
            onClick = { excDialog = app.eye.engine.ExceptionKind.TRUSTED },
            enabled = v.level != app.eye.engine.Level.NESSUN_SEGNALE,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.extras.orange),
        ) { Text(stringResource(R.string.exc_fp_button), color = MaterialTheme.extras.orange, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal) }
        OutlinedButton(
            onClick = { excDialog = app.eye.engine.ExceptionKind.SUSPICIOUS },
            enabled = v.level == app.eye.engine.Level.NESSUN_SEGNALE,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.exc_fn_button)) }

        // Azioni
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = showMessage, onCheckedChange = { showMessage = it })
            Text(stringResource(R.string.verdict_card_show_message), style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = ::shareCard, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Icon(Icons.Rounded.Share, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.verdict_share_card), style = MaterialTheme.typography.titleMedium)
        }
        OutlinedButton(
            onClick = {
                val id = entryId
                when {
                    id == null -> vm.saveBookmark(result) { entryId = it; createdForBookmark = true }
                    saved && createdForBookmark -> { vm.deleteHistory(id); entryId = null; createdForBookmark = false }
                    else -> vm.setSaved(id, !saved)
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Icon(if (saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (saved) R.string.saved_action else R.string.save_action))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { ExternalIntents.shareText(ctx, ReportBuilder.shareText(ctx, result), ReportBuilder.shareSubject(ctx, result)) },
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.verdict_send_family)) }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("report", ShareTexts.safeOutgoing(ReportBuilder.report(ctx, result, includeMessage = showMessage)))))
                    }
                    Toast.makeText(ctx, copied, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.verdict_copy_report))
            }
        }
        OutlinedButton(onClick = { pdfDialog = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.pdf_button)) }
        VirusTotalButton(vm, v)
        TextButton(onClick = { reportDialog = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Rounded.Flag, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.verdict_report))
        }
        TextButton(onClick = { wrongDialog = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.verdict_wrong)) }
    }

    excDialog?.let { kind ->
        val fp = kind == app.eye.engine.ExceptionKind.TRUSTED
        AlertDialog(
            onDismissRequest = { excDialog = null },
            title = { Text(stringResource(if (fp) R.string.exc_fp_title else R.string.exc_fn_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(stringResource(if (fp) R.string.exc_fp_body else R.string.exc_fn_body))
                if (result.origin == null) Text(stringResource(R.string.exc_any_app), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            } },
            confirmButton = {
                TextButton(onClick = {
                    excDialog = null
                    vm.saveException(kind, result, v, entryId) { nv ->
                        if (nv != null) updated = nv
                        scope.launch { snackbar.showSnackbar(if (nv != null) excSaved else excFailed) }
                    }
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.exc_yes)) }
            },
            dismissButton = { TextButton(onClick = { excDialog = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.exc_no)) } },
        )
    }
    if (pdfDialog) PdfExportDialog(result, onDismiss = { pdfDialog = false })
    if (reportDialog) {
        AlertDialog(
            onDismissRequest = { reportDialog = false },
            title = { Text(stringResource(R.string.report_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.report_dialog_body))
                    OfficialChannels.forEach { ch ->
                        TextButton(onClick = { reportDialog = false; openLink = ch }, modifier = Modifier.heightIn(min = 48.dp)) { Text(ch.label) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { reportDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    openLink?.let { ch ->
        AlertDialog(
            onDismissRequest = { openLink = null },
            title = { Text(stringResource(R.string.report_open_confirm, ExternalIntents.host(ch.url))) },
            confirmButton = { TextButton(onClick = { ExternalIntents.openUrl(ctx, ch.url); openLink = null }) { Text(stringResource(R.string.action_open)) } },
            dismissButton = { TextButton(onClick = { openLink = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (wrongDialog) {
        var view by remember { mutableStateOf<ShareTexts.UserView?>(null) }
        val labels = listOf(
            ShareTexts.UserView.SCAM to stringResource(R.string.wrong_view_scam),
            ShareTexts.UserView.LEGIT to stringResource(R.string.wrong_view_legit),
            ShareTexts.UserView.OTHER to stringResource(R.string.wrong_view_other),
        )
        AlertDialog(
            onDismissRequest = { wrongDialog = false },
            title = { Text(stringResource(R.string.verdict_wrong)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.wrong_info_v2))
                    Text(stringResource(R.string.wrong_view_question), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.wrong_clip_hint), style = MaterialTheme.typography.bodyMedium)
                    Column(Modifier.selectableGroup()) {
                        labels.forEach { (opt, label) ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .selectable(selected = view == opt, onClick = { view = opt }, role = Role.RadioButton),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = view == opt, onClick = null)
                                Spacer(Modifier.width(8.dp))
                                Text(label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = view != null, onClick = {
                    val choice = view ?: return@TextButton
                    wrongDialog = false
                    val body = ShareTexts.safeOutgoing(ReportBuilder.feedbackBody(ctx, result, choice))
                    ExternalIntents.copyToClipboard(ctx, "Segnalazione Eye", body)
                    ExternalIntents.composeEmail(ctx, app.eye.android.core.REPORT_EMAIL, ReportBuilder.feedbackSubject(ctx, result, choice), body)
                }) { Text(stringResource(R.string.wrong_send)) }
            },
            dismissButton = { TextButton(onClick = { wrongDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ReasonRow(r: app.eye.engine.Reason) {
    val (icon, tint, cd) = when (reasonTone(r.weight)) {
        ReasonTone.RISK -> Triple(Icons.Rounded.Warning, MaterialTheme.colorScheme.error, R.string.reason_cd_risk)
        ReasonTone.REDUCES -> Triple(Icons.Rounded.CheckCircle, MaterialTheme.extras.of(app.eye.engine.Level.NESSUN_SEGNALE).icon, R.string.reason_cd_reduces)
        ReasonTone.NEUTRAL -> Triple(Icons.Rounded.Info, MaterialTheme.colorScheme.onSurfaceVariant, R.string.reason_cd_info)
    }
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = stringResource(cd), tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(r.text, style = MaterialTheme.typography.bodyLarge)
    }
}
