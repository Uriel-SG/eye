package app.eye.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.eye.android.R
import app.eye.android.core.DONATE_URL
import app.eye.android.core.ExternalIntents
import app.eye.android.core.REPO_URL

/** Librerie principali e relative licenze (testo statico). */
private val Libraries = listOf(
    R.string.lic_androidx to R.string.lic_apache,
    R.string.lic_kotlin to R.string.lic_apache,
    R.string.lic_serialization to R.string.lic_apache,
    R.string.lic_zxing to R.string.lic_apache,
    R.string.lic_mlkit to R.string.lic_mlkit_terms,
    R.string.lic_bouncycastle to R.string.lic_mit,
    R.string.lic_onnx to R.string.lic_mit,
    R.string.lic_figtree to R.string.lic_ofl,
    R.string.lic_minilm to R.string.lic_apache,
    R.string.lic_ai_data to R.string.lic_ai_data_terms,
)

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    ScreenScaffold(stringResource(R.string.licenses_title), onBack = onBack) {
        Text(stringResource(R.string.licenses_intro), style = MaterialTheme.typography.bodyLarge)
        Libraries.forEach { (name, lic) ->
            EyeCard {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(name), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(lic), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(stringResource(R.string.licenses_trademark), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** «Il progetto Eye»: nessun pagamento né invito a donare nell'app; apre il sito del progetto (con conferma) e il codice sorgente. */
@Composable
fun SupportSection() {
    val ctx = LocalContext.current
    var link by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val titleCd = stringResource(R.string.support_title_cd) // senza emoji per TalkBack
        val bodyCd = stringResource(R.string.support_body_cd)
        SectionTitle(stringResource(R.string.support_title), Modifier.semantics { contentDescription = titleCd })
        Text(stringResource(R.string.support_body), style = MaterialTheme.typography.bodyLarge, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, modifier = Modifier.semantics { contentDescription = bodyCd })
        Text(stringResource(R.string.support_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { link = DONATE_URL }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.support_site)) }
        OutlinedButton(onClick = { link = REPO_URL }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.support_source)) }
    }
    link?.let { url ->
        AlertDialog(
            onDismissRequest = { link = null },
            title = { Text(stringResource(R.string.report_open_confirm, ExternalIntents.host(url))) },
            confirmButton = { TextButton(onClick = { ExternalIntents.openUrl(ctx, url); link = null }) { Text(stringResource(R.string.action_open)) } },
            dismissButton = { TextButton(onClick = { link = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
