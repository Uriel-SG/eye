package app.eye.android.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.eye.android.BuildConfig
import app.eye.android.R
import app.eye.android.core.CheckResult
import app.eye.android.core.PdfBlock
import app.eye.android.core.PdfInput
import app.eye.android.core.PdfStr
import app.eye.android.core.ReportPdf
import app.eye.android.core.reasonTexts
import app.eye.android.core.titleRes
import app.eye.android.core.todoSteps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** PDF per la denuncia: testo oscurato di default; salvataggio con il selettore di sistema o condivisione. Nessun permesso. */
@Composable
fun PdfExportDialog(result: CheckResult, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var plain by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<List<PdfBlock>?>(null) }
    val failed = stringResource(R.string.pdf_error)
    val saved = stringResource(R.string.pdf_saved)

    fun blocks(): List<PdfBlock> {
        val v = result.verdict
        val input = PdfInput(
            createdAt = result.createdAt, appVersion = BuildConfig.VERSION_NAME, rulesVersion = v.rulesVersion,
            levelTitle = ctx.getString(v.level.titleRes()), reasons = v.reasonTexts(), steps = v.todoSteps(ctx),
            originalText = result.originalText, domains = v.extracted.domains, includePlainText = plain,
        )
        return ReportPdf.content(input, PdfStr { id, args -> ctx.getString(id, *args) })
    }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val b = pending
        pending = null
        if (uri != null && b != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { ReportPdf.write(b, it) } }.isSuccess
            }
            Toast.makeText(ctx, if (ok) saved else failed, Toast.LENGTH_SHORT).show()
            if (ok) onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.pdf_dialog_body))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = plain, onCheckedChange = { plain = it })
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.pdf_include_plain), style = MaterialTheme.typography.bodyLarge)
                }
                if (plain) Text(stringResource(R.string.pdf_plain_warning), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth()) {
                TextButton(onClick = { pending = blocks(); saver.launch(ReportPdf.fileName(result.createdAt)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.pdf_save))
                }
                TextButton(onClick = {
                    val b = blocks()
                    scope.launch {
                        try {
                            val uri = withContext(Dispatchers.IO) { ReportPdf.saveToCache(ctx, b, ReportPdf.fileName(result.createdAt)) }
                            ctx.startActivity(ReportPdf.shareIntent(ctx, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            onDismiss()
                        } catch (_: Exception) {
                            Toast.makeText(ctx, failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.pdf_share)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) } },
    )
}
