package app.eye.android.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.toggleable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.eye.android.BuildConfig
import app.eye.android.R
import app.eye.android.core.notif.AutoStatus
import app.eye.android.core.notif.MonitoredApps
import app.eye.android.core.notif.autoStatus
import app.eye.android.data.Settings

private fun openListenerSettings(ctx: Context) {
    runCatching {
        ctx.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun openAppNotificationSettings(ctx: Context) {
    runCatching {
        ctx.startActivity(
            Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun installed(ctx: Context, pkg: String): Boolean =
    runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess

/** Sezione Impostazioni «Protezione automatica»: interruttore, stato reale e app da controllare. */
@Composable
fun AutoProtectSection(vm: AppViewModel, s: Settings, onStartActivation: () -> Unit) {
    val ctx = LocalContext.current
    val granted by vm.listenerGranted.collectAsState()
    val alertsOk by vm.alertsAllowed.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshAutoAccess(ctx) }
    val status = autoStatus(s.autoProtect, granted, alertsOk)
    val apps = remember(granted, s.autoProtect) { MonitoredApps.presets(BuildConfig.DEBUG).filter { installed(ctx, it.pkg) } }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .toggleable(value = s.autoProtect, role = Role.Switch) { if (it) onStartActivation() else vm.setAutoProtect(false) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.auto_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.auto_sub), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = s.autoProtect, onCheckedChange = null)
        }
        val statusText = when (status) {
            AutoStatus.OFF -> R.string.auto_status_off
            AutoStatus.ACTIVE -> R.string.auto_status_active
            AutoStatus.NEEDS_ACCESS -> R.string.auto_status_needs_access
            AutoStatus.ALERTS_BLOCKED -> R.string.auto_status_alerts_blocked
        }
        Text(
            stringResource(statusText), style = MaterialTheme.typography.bodyLarge,
            color = if (status == AutoStatus.NEEDS_ACCESS || status == AutoStatus.ALERTS_BLOCKED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (status == AutoStatus.NEEDS_ACCESS) {
            Button(onClick = { openListenerSettings(ctx) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.auto_open_system)) }
        }
        if (status == AutoStatus.ALERTS_BLOCKED) {
            OutlinedButton(onClick = { openAppNotificationSettings(ctx) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.auto_open_notif_settings)) }
        }
        if (s.autoProtect) {
            Text(stringResource(R.string.auto_apps_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            if (apps.isEmpty()) Text(stringResource(R.string.auto_apps_none), style = MaterialTheme.typography.bodyMedium)
            apps.forEach { a ->
                val on = a.pkg !in s.autoDisabledApps
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = on, role = Role.Switch) { vm.setAutoApp(a.pkg, it) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(a.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = on, onCheckedChange = null)
                }
            }
            Text(stringResource(R.string.auto_revoke_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (granted) OutlinedButton(onClick = { openListenerSettings(ctx) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.auto_open_system)) }
        }
    }
}

/** Informativa che precede l'attivazione (prominent disclosure); solo dopo «continua» si apre la pagina di sistema. */
@Composable
fun AutoProtectInfoScreen(vm: AppViewModel, onDone: () -> Unit) {
    val ctx = LocalContext.current
    fun proceed() {
        vm.setAutoProtect(true)
        openListenerSettings(ctx)
        onDone()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { proceed() } // anche se negato: lo stato lo mostra
    ScreenScaffold(stringResource(R.string.auto_info_title), onBack = onDone) {
        Text(stringResource(R.string.auto_info_intro), style = MaterialTheme.typography.bodyLarge)
        listOf(
            R.string.auto_info_reads_h to R.string.auto_info_reads,
            R.string.auto_info_stays_h to R.string.auto_info_stays,
            R.string.auto_info_alert_h to R.string.auto_info_alert,
            R.string.auto_info_off_h to R.string.auto_info_off,
        ).forEach { (h, b) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionTitle(stringResource(h))
                Text(stringResource(b), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Text(stringResource(R.string.auto_info_next), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(
            onClick = {
                if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else proceed()
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text(stringResource(R.string.auto_info_continue)) }
        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.action_not_now)) }
    }
}
