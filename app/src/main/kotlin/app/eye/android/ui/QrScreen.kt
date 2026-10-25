package app.eye.android.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.eye.android.R
import app.eye.android.core.QrContent

/** Anteprima del contenuto del QR: il link non viene mai aperto, solo mostrato. */
@Composable
fun QrScreen(q: QrContent, state: CheckState, aiBusy: Boolean = false, onCheck: () -> Unit, onBack: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val domainLabel = stringResource(R.string.verdict_domain)
    ScreenScaffold(stringResource(R.string.qr_title), onBack = onBack) {
        Text(stringResource(if (q.isUrl) R.string.qr_leads_to else R.string.qr_text_content), style = MaterialTheme.typography.titleMedium)
        EyeCard {
            val shown = q.raw.take(1000)
            val text = buildAnnotatedString {
                val reg = q.registrable
                val hostStart = if (q.isUrl && q.host != null) shown.indexOf(q.host, ignoreCase = true) else -1
                val regStart = if (reg != null && hostStart >= 0) shown.indexOf(reg, hostStart, ignoreCase = true) else -1
                if (reg != null && regStart >= 0) {
                    append(shown.substring(0, regStart))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = primary)) { append(shown.substring(regStart, regStart + reg.length)) }
                    append(shown.substring(regStart + reg.length))
                } else append(shown)
            }
            Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
        }
        if (q.registrable != null) {
            Text(
                buildAnnotatedString {
                    append("$domainLabel ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(q.registrable) }
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(stringResource(R.string.qr_not_opened), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (state) {
            CheckState.Running -> Text(stringResource(if (aiBusy) R.string.ai_autoan_running else R.string.state_checking))
            CheckState.TooLong -> Text(stringResource(R.string.paste_too_long), color = MaterialTheme.colorScheme.error)
            CheckState.Error -> Text(stringResource(R.string.state_error), color = MaterialTheme.colorScheme.error)
            else -> {}
        }
        Button(onClick = onCheck, enabled = state != CheckState.Running, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.action_check), style = MaterialTheme.typography.titleMedium)
        }
    }
}
