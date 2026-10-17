package app.eye.android.ui

import android.widget.Toast
import androidx.compose.foundation.background
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
fun VerdictScreen(result: CheckResult, vm: AppViewModel, onClose: () -> Unit, fromHistory: Boolean = false) {
    val ctx = LocalContext.current
    val v = result.verdict
    val family = MaterialTheme.extras.familyMode
    val colors = MaterialTheme.extras.of(v.level)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var showMessage by remember { mutableStateOf(false) } // contenuto sensibile oscurato di default
    var showSensitive by remember { mutableStateOf(false) } // solo dettaglio cronologia: testo oscurato di default
    var expanded by remember { mutableStateOf(false) }
    var reportDialog by remember { mutableStateOf(false) }
    var openLink by remember { mutableStateOf<ReportLink?>(null) }
    var wrongDialog by remember { mutableStateOf(false) }
    var pdfDialog by remember { mutableStateOf(false) }

    var entryId by remember { mutableStateOf(result.historyId) }
    var createdForBookmark by remember { mutableStateOf(false) }
    val history by vm.history.collectAsState()
    val saved = history.firstOrNull { it.id == entryId }?.saved == true

    SecureWindow(fromHistory && showSensitive)

    val title = stringResource(v.level.titleRes())
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

    ScreenScaffold(stringResource(R.string.app_name), onBack = onClose) {
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
                Icon(v.level.icon(), contentDescription = null, tint = colors.icon, modifier = Modifier.size(if (family) 72.dp else 48.dp))
                Text(title, style = MaterialTheme.typography.displaySmall, color = colors.text)
                Text(if (family) stringResource(v.level.familyLineRes()) else subtitle, style = MaterialTheme.typography.bodyLarge, color = colors.text)
            }
        }

        if (family) {
            Button(
                onClick = { ExternalIntents.shareText(ctx, ReportBuilder.shareText(ctx, result), ReportBuilder.shareSubject(ctx, result)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
            ) {
                Icon(Icons.Rounded.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.verdict_send_family), style = MaterialTheme.typography.titleMedium)
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.action_close)) }
            Text(stringResource(R.string.verdict_disclaimer_short), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@ScreenScaffold
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
                SectionTitle(stringResource(R.string.verdict_todo))
                steps.forEachIndexed { i, s ->
                    val stepCd = stringResource(R.string.cd_step, i + 1, steps.size)
                    Text("${i + 1}. $s", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { contentDescription = "$stepCd. $s" })
                }
            }
        }

        // Secondo parere AI: facoltativo, mai automatico, mai al posto del verdetto delle regole
        AiSecondOpinion(vm, result.originalText, v.level)

        Text(stringResource(R.string.verdict_disclaimer_short), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

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
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("report", ReportBuilder.report(ctx, result, includeMessage = showMessage))))
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
        TextButton(onClick = { reportDialog = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Rounded.Flag, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.verdict_report))
        }
        TextButton(onClick = { wrongDialog = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.verdict_wrong)) }
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
                    scope.launch {
                        val uri = try {
                            withContext(Dispatchers.Default) { ShareCard.save(ctx, ShareCard.render(ctx, result, false)) }
                        } catch (_: Exception) { null }
                        ExternalIntents.composeEmail(
                            ctx, app.eye.android.core.REPORT_EMAIL, ReportBuilder.feedbackSubject(ctx, result, choice),
                            ReportBuilder.feedbackBody(ctx, result, choice), uri,
                        )
                    }
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
