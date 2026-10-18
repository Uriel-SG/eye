package app.eye.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.eye.android.R
import app.eye.android.core.notif.MonitoredApps
import app.eye.engine.ExceptionKind
import java.text.DateFormat
import java.util.Date

@Composable
fun ExceptionsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val items by vm.exceptions.collectAsState()
    val ctx = LocalContext.current
    var confirmAll by remember { mutableStateOf(false) }
    val fmt = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    ScreenScaffold(stringResource(R.string.exc_settings_title), onBack = onBack) {
        if (items.isEmpty()) Text(stringResource(R.string.exc_empty), style = MaterialTheme.typography.bodyLarge)
        items.forEach { e ->
            EyeCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(if (e.kind == ExceptionKind.TRUSTED) R.string.exc_kind_trusted else R.string.exc_kind_suspicious),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.exc_app, e.origin?.let { appLabel(ctx, it) } ?: stringResource(R.string.exc_app_none)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    e.sender?.let { Text(stringResource(R.string.exc_sender, it), style = MaterialTheme.typography.bodyMedium) }
                    if (e.preview.isNotBlank()) Text(e.preview, style = MaterialTheme.typography.bodyLarge)
                    Row {
                        Text(fmt.format(Date(e.createdAt)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { vm.removeException(e.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.exc_delete_one), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (items.isNotEmpty()) {
            OutlinedButton(onClick = { confirmAll = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.exc_delete_all), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            text = { Text(stringResource(R.string.exc_delete_all_confirm)) },
            confirmButton = { TextButton(onClick = { vm.clearExceptions(); confirmAll = false }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Nome leggibile dell'app: elenco noto, altrimenti etichetta di sistema, altrimenti il package. */
private fun appLabel(ctx: android.content.Context, pkg: String): String =
    MonitoredApps.PRESETS.firstOrNull { it.pkg == pkg }?.label
        ?: runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        ?: pkg
