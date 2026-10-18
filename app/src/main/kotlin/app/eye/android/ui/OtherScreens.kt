package app.eye.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.eye.android.BuildConfig
import app.eye.android.R
import app.eye.android.core.titleRes
import app.eye.android.data.HistoryEntity
import app.eye.android.ui.theme.extras
import app.eye.engine.Level
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(vm: AppViewModel, onOpen: (Long) -> Unit) {
    val items by vm.history.collectAsState()
    val settings by vm.settings.collectAsState()
    var confirmAll by remember { mutableStateOf(false) }
    var onlySaved by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.purgeHistory() }
    var query by remember { mutableStateOf("") }
    // ricerca sul testo già decifrato in memoria: nessun indice su disco
    val shown = items.filter { (!onlySaved || it.saved) && app.eye.android.core.HistorySearch.matches(query, it.fullText, it.preview, it.reasons) }

    ScreenScaffold(stringResource(R.string.history_title), scroll = false) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(stringResource(R.string.history_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                androidx.compose.material3.OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.history_search)) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.history_search_clear)) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !onlySaved, onClick = { onlySaved = false }, label = { Text(stringResource(R.string.history_filter_all)) })
                    FilterChip(selected = onlySaved, onClick = { onlySaved = true }, label = { Text(stringResource(R.string.history_filter_saved)) })
                }
            }
            if (!settings.saveHistory) item { Text(stringResource(R.string.history_off), style = MaterialTheme.typography.bodyMedium) }
            if (shown.isEmpty()) item {
                Text(stringResource(if (query.isNotBlank()) R.string.history_empty_search else if (onlySaved) R.string.history_empty_saved else R.string.history_empty), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 24.dp))
            }
            items(shown, key = { it.id }) { e ->
                HistoryRow(e, onOpen = { onOpen(e.id) }, onDelete = { vm.deleteHistory(e.id) }, onToggleSaved = { vm.setSaved(e.id, !e.saved) })
            }
            if (items.isNotEmpty()) item {
                OutlinedButton(
                    onClick = { confirmAll = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) { Text(stringResource(R.string.history_clear_all_button), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (confirmAll) ClearHistoryDialog(items.any { it.saved }, { confirmAll = false }) { vm.deleteAllHistory(it); confirmAll = false }
}

/** «Cancella tutto»: chiede se includere anche le analisi salvate (di default no). */
@Composable
private fun ClearHistoryDialog(hasSaved: Boolean, onDismiss: () -> Unit, onConfirm: (includeSaved: Boolean) -> Unit) {
    var includeSaved by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_clear_title)) },
        text = {
            Column {
                Text(stringResource(R.string.history_clear_body))
                if (hasSaved) Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = includeSaved, role = androidx.compose.ui.semantics.Role.Checkbox) { includeSaved = !includeSaved },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = includeSaved, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.history_clear_include_saved))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(includeSaved) }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun HistoryRow(e: HistoryEntity, onOpen: () -> Unit, onDelete: () -> Unit, onToggleSaved: () -> Unit) {
    val level = runCatching { Level.valueOf(e.level) }.getOrDefault(Level.SOSPETTO)
    val colors = MaterialTheme.extras.of(level)
    val title = stringResource(level.titleRes())
    val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.createdAt))
    val openCd = stringResource(R.string.history_open_cd, title)
    EyeCard(onClick = onOpen, modifier = Modifier.semantics { contentDescription = openCd }) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(level.icon(), contentDescription = null, tint = colors.solid, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(e.preview, maxLines = 1, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(date, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onToggleSaved, modifier = Modifier.size(48.dp)) {
                Icon(
                    if (e.saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                    contentDescription = stringResource(if (e.saved) R.string.unsave_cd else R.string.save_cd),
                    tint = if (e.saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.history_delete_item))
            }
        }
    }
}

/** Riapre il verdetto completo di un controllo salvato; il testo si mostra oscurato finché l'utente non lo scopre. */
@Composable
fun HistoryDetailScreen(vm: AppViewModel, id: Long, onBack: () -> Unit) {
    val items by vm.history.collectAsState()
    val e = items.firstOrNull { it.id == id }
    val verdict = e?.let { app.eye.android.data.VerdictJson.decode(it.verdictJson) }
    if (e == null || verdict == null) {
        ScreenScaffold(stringResource(R.string.history_detail_title), onBack = onBack) {
            Text(stringResource(R.string.history_detail_missing), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    VerdictScreen(app.eye.android.core.CheckResult(verdict, e.fullText, e.createdAt, historyId = e.id), vm, onClose = onBack, fromHistory = true)
}

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: (() -> Unit)?, onAutoInfo: () -> Unit = {}, onLicenses: () -> Unit = {}, onExceptions: () -> Unit = {}) {
    val s by vm.settings.collectAsState()
    var familyDialog by remember { mutableStateOf(false) }
    var clearData by remember { mutableStateOf(false) }
    var clearHistory by remember { mutableStateOf(false) }
    var privacyLink by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    ScreenScaffold(stringResource(R.string.settings_title), onBack = onBack) {
        AutoProtectSection(vm, s, onAutoInfo)
        run {
            HorizontalDivider()
            val askNotif = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { vm.setAlertCampaigns(true) }
            SwitchRow(stringResource(R.string.campaign_alerts_title), stringResource(R.string.campaign_alerts_sub), s.alertCampaigns) { on ->
                if (!on) vm.setAlertCampaigns(false)
                else if (android.os.Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) askNotif.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                else vm.setAlertCampaigns(true)
            }
        }
        HorizontalDivider()
        AppearanceSection(s.themeMode) { vm.setThemeMode(it) }
        HorizontalDivider()
        SwitchRow(stringResource(R.string.settings_family), stringResource(R.string.settings_family_sub), s.familyMode) {
            if (it) familyDialog = true else vm.setFamilyMode(false)
        }
        HorizontalDivider()
        InfoRow(stringResource(R.string.settings_text_size), stringResource(R.string.settings_text_size_sub))
        HorizontalDivider()
        InfoRow(stringResource(R.string.settings_language), stringResource(R.string.settings_language_value))
        HorizontalDivider()
        val rulesStale by vm.rulesStaleDays.collectAsState()
        InfoRow(stringResource(R.string.settings_rules), rulesStale?.let { stringResource(R.string.rules_stale, it.toInt()) } ?: stringResource(R.string.settings_rules_sub))
        HorizontalDivider()

        SectionTitle(stringResource(R.string.howto_title))
        Text(stringResource(R.string.howto_body), style = MaterialTheme.typography.bodyLarge)

        SectionTitle(stringResource(R.string.settings_privacy))
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_privacy_not_doing), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.settings_privacy_policy), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = { privacyLink = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.settings_privacy_link)) }
        HorizontalDivider()
        SwitchRow(stringResource(R.string.settings_save_history), stringResource(R.string.settings_save_history_sub), s.saveHistory) { vm.setSaveHistory(it) }
        RetentionSection(s.retention) { vm.setRetention(it) }
        HorizontalDivider()
        OutlinedButton(onClick = { clearHistory = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.settings_clear_history)) }
        OutlinedButton(onClick = { clearData = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.settings_clear_data), color = MaterialTheme.colorScheme.error)
        }

        HorizontalDivider()
        AiSettingsSection(vm, s)

        HorizontalDivider()
        val excCount by vm.exceptions.collectAsState()
        TextButton(onClick = onExceptions, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Column(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.exc_settings_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.exc_settings_sub, excCount.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Stacco netto prima di «Informazioni»
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outline)
        Text(
            stringResource(R.string.settings_about),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
        val italic = androidx.compose.ui.text.font.FontStyle.Italic
        Text(stringResource(R.string.settings_about_method), style = MaterialTheme.typography.bodyLarge, fontStyle = italic)
        Text(stringResource(R.string.settings_trademarks), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = italic)
        Text(stringResource(R.string.settings_disclaimer_long), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = italic)
        Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium, fontStyle = italic)
        Text(stringResource(R.string.settings_license), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onLicenses, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.licenses_title)) }

        HorizontalDivider()
        SupportSection()
    }

    if (familyDialog) {
        AlertDialog(
            onDismissRequest = { familyDialog = false },
            title = { Text(stringResource(R.string.family_confirm_title)) },
            text = { Text(stringResource(R.string.family_confirm_body)) },
            confirmButton = { TextButton(onClick = { vm.setFamilyMode(true); familyDialog = false }) { Text(stringResource(R.string.action_activate)) } },
            dismissButton = { TextButton(onClick = { familyDialog = false }) { Text(stringResource(R.string.action_not_now)) } },
        )
    }
    if (privacyLink) {
        AlertDialog(
            onDismissRequest = { privacyLink = false },
            title = { Text(stringResource(R.string.report_open_confirm, app.eye.android.core.ExternalIntents.host(app.eye.android.core.PRIVACY_URL))) },
            confirmButton = { TextButton(onClick = { app.eye.android.core.ExternalIntents.openUrl(ctx, app.eye.android.core.PRIVACY_URL); privacyLink = false }) { Text(stringResource(R.string.action_open)) } },
            dismissButton = { TextButton(onClick = { privacyLink = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (clearHistory) ClearHistoryDialog(true, { clearHistory = false }) { vm.deleteAllHistory(it); clearHistory = false }
    if (clearData) ConfirmDialog(stringResource(R.string.settings_clear_data_confirm), { clearData = false }) {
        if (vm.listenerGranted.value) android.widget.Toast.makeText(ctx, R.string.auto_revoke_hint, android.widget.Toast.LENGTH_LONG).show()
        vm.clearAllData(); clearData = false
    }
}

@Composable
private fun RetentionSection(current: app.eye.android.data.HistoryRetention, onSelect: (app.eye.android.data.HistoryRetention) -> Unit) {
    val options = listOf(
        app.eye.android.data.HistoryRetention.DAYS_7 to R.string.retention_7,
        app.eye.android.data.HistoryRetention.DAYS_30 to R.string.retention_30,
        app.eye.android.data.HistoryRetention.FOREVER to R.string.retention_forever,
    )
    Column {
        Text(stringResource(R.string.retention_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.retention_sub), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.selectableGroup()) {
            options.forEach { (r, label) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = current == r, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = { onSelect(r) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = current == r, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun AppearanceSection(current: app.eye.android.data.ThemeMode, onSelect: (app.eye.android.data.ThemeMode) -> Unit) {
    val options = listOf(
        app.eye.android.data.ThemeMode.SYSTEM to R.string.theme_system,
        app.eye.android.data.ThemeMode.LIGHT to R.string.theme_light,
        app.eye.android.data.ThemeMode.DARK to R.string.theme_dark,
        app.eye.android.data.ThemeMode.OLED to R.string.theme_oled,
    )
    Column {
        Text(stringResource(R.string.theme_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Column(Modifier.selectableGroup()) {
            options.forEach { (mode, label) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = current == mode, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = { onSelect(mode) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = current == mode, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun ConfirmDialog(text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun InfoRow(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pager = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    val page = pager.currentPage
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    ShieldBackdrop()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_skip)) }
        }
        HorizontalPager(pager, Modifier.weight(1f)) { p -> OnboardingPage(p) }
        val pageCd = stringResource(R.string.cd_page, page + 1)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp).semantics { contentDescription = pageCd },
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(3) { i ->
                Box(
                    Modifier.padding(4.dp).size(10.dp)
                        .background(if (i == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (page > 0) OutlinedButton(onClick = { scope.launch { pager.animateScrollToPage(page - 1) } }, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.action_back))
            }
            Button(
                onClick = { if (page < 2) scope.launch { pager.animateScrollToPage(page + 1) } else onDone() },
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) { Text(stringResource(if (page < 2) R.string.action_next else R.string.action_start), style = MaterialTheme.typography.titleMedium) }
        }
    }
    }
}

@Composable
private fun OnboardingPage(p: Int) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(
            if (p == 1) Icons.Rounded.Lock else Icons.Rounded.Visibility, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(96.dp).align(Alignment.CenterHorizontally),
        )
        when (p) {
            0 -> {
                Text(stringResource(R.string.onb_title_1), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.onb_body_1), style = MaterialTheme.typography.bodyLarge)
            }
            1 -> {
                Text(stringResource(R.string.onb_title_2), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                listOf(R.string.onb_does_1, R.string.onb_does_2, R.string.onb_does_3, R.string.onb_does_4, R.string.onb_does_5).forEach { Bullet(true, stringResource(it)) }
                listOf(R.string.onb_doesnt_1, R.string.onb_doesnt_2, R.string.onb_doesnt_3, R.string.onb_doesnt_4).forEach { Bullet(false, stringResource(it)) }
            }
            else -> {
                Text(stringResource(R.string.onb_title_3), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                listOf(R.string.onb_step_1, R.string.onb_step_2, R.string.onb_step_3).forEachIndexed { i, r ->
                    Text("${i + 1}. ${stringResource(r)}", style = MaterialTheme.typography.bodyLarge)
                }
                Text(stringResource(R.string.onb_body_3), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Bullet(yes: Boolean, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(if (yes) Icons.Rounded.Check else Icons.Rounded.Close, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
