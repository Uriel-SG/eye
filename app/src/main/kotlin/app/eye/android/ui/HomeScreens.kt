package app.eye.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.eye.android.R
import app.eye.android.core.Catalog
import app.eye.android.core.CheckUseCase
import app.eye.android.ui.theme.extras
import app.eye.engine.Source
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onPaste: () -> Unit,
    onSettings: () -> Unit,
    onCatalog: () -> Unit,
    onCatalogCard: (String) -> Unit,
    onHistory: () -> Unit,
    onPasteCheck: (String) -> Unit,
    onQr: () -> Unit,
    onImage: (android.net.Uri) -> Unit,
    ongoing: List<app.eye.android.core.ScamCard> = Catalog.ongoing(3),
) {
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { it?.let(onImage) }
    val pickImage = { picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val family = MaterialTheme.extras.familyMode
    var sheet by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val emptyMsg = stringResource(R.string.clipboard_empty_notice)
    // Appunti letti solo al tocco, in primo piano.
    val pasteCheck: () -> Unit = {
        scope.launch {
            val raw = runCatching { clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx) }.getOrNull()
            val text = app.eye.android.core.QuickIntents.clean(raw)
            if (text == null) android.widget.Toast.makeText(ctx, emptyMsg, android.widget.Toast.LENGTH_LONG).show() else onPasteCheck(text)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Visibility, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                    }
                },
                actions = {
                    IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.cd_settings))
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (family) {
                FamilyHomeButton(Icons.Rounded.ContentPaste, stringResource(R.string.action_paste_check), pasteCheck)
                FamilyHomeButton(Icons.Rounded.ContentPaste, stringResource(R.string.home_paste_title), onPaste)
                FamilyHomeButton(Icons.Rounded.QrCodeScanner, stringResource(R.string.home_qr_title), onQr)
                FamilyHomeButton(Icons.Rounded.ImageSearch, stringResource(R.string.home_shot_title), pickImage)
                Text(stringResource(R.string.nav_more), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCatalog, modifier = Modifier.heightIn(min = 56.dp).weight(1f)) { Text(stringResource(R.string.nav_catalog)) }
                    OutlinedButton(onClick = onHistory, modifier = Modifier.heightIn(min = 56.dp).weight(1f)) { Text(stringResource(R.string.nav_history)) }
                }
            } else {
                EyeCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.home_hero), style = MaterialTheme.typography.headlineSmall)
                        val cd = stringResource(R.string.cd_check_button)
                        Button(
                            onClick = { sheet = true },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { contentDescription = cd },
                        ) {
                            Icon(Icons.Rounded.Search, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_check), style = MaterialTheme.typography.titleMedium)
                        }
                        OutlinedButton(onClick = pasteCheck, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Icon(Icons.Rounded.ContentPaste, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_paste_check), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                EyeCard {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        SectionTitle(stringResource(R.string.home_trending), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        ongoing.forEach { c ->
                            IconRow(Icons.Rounded.Visibility, c.name, c.summary, onClick = { onCatalogCard(c.id) }, minHeight = 72) {
                                Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                            }
                        }
                        TextButton(onClick = onCatalog, modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.action_see_all))
                        }
                    }
                }
            }
            Text(stringResource(R.string.howto_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.home_trust), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }, shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(bottom = 24.dp)) {
                IconRow(Icons.Rounded.ContentPaste, stringResource(R.string.home_paste_title), stringResource(R.string.home_paste_sub), { sheet = false; onPaste() })
                IconRow(Icons.Rounded.QrCodeScanner, stringResource(R.string.home_qr_title), stringResource(R.string.home_qr_sub), { sheet = false; onQr() })
                IconRow(Icons.Rounded.ImageSearch, stringResource(R.string.home_shot_title), stringResource(R.string.home_shot_sub), { sheet = false; pickImage() })
            }
        }
    }
}

@Composable
private fun FamilyHomeButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun ComingSoonDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.coming_soon)) },
        text = { Text(stringResource(R.string.coming_soon_body)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

/** Incolla o condividi: gli appunti si leggono solo al tocco di «Incolla dagli appunti». */
@Composable
fun PasteScreen(vm: AppViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var clipEmpty by remember { mutableStateOf(false) }
    var truncated by remember { mutableStateOf(false) }


    ScreenScaffold(stringResource(R.string.paste_title), onBack = { vm.reset(); onBack() }) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                if (it.length > CheckUseCase.MAX_CHARS) truncated = true
                text = it.take(CheckUseCase.MAX_CHARS); clipEmpty = false
            },
            label = { Text(stringResource(R.string.paste_hint)) },
            minLines = 5,
            maxLines = 8, // altezza massima: oltre scorre dentro il campo, «Controlla» resta raggiungibile
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions.Default,
        )
        OutlinedButton(
            onClick = {
                scope.launch {
                    val pasted = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                    if (pasted.isNullOrBlank()) clipEmpty = true
                    else {
                        if (pasted.length > CheckUseCase.MAX_CHARS) truncated = true
                        text = pasted.take(CheckUseCase.MAX_CHARS); clipEmpty = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Icon(Icons.Rounded.ContentPaste, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.paste_button))
        }
        if (truncated) Text(stringResource(R.string.truncated_warning), color = MaterialTheme.colorScheme.error)
        if (clipEmpty) Text(stringResource(R.string.paste_empty), color = MaterialTheme.colorScheme.error)
        when (state) {
            CheckState.Running -> Text(stringResource(R.string.state_checking))
            CheckState.TooLong -> Text(stringResource(R.string.paste_too_long), color = MaterialTheme.colorScheme.error)
            CheckState.Error -> Text(stringResource(R.string.state_error), color = MaterialTheme.colorScheme.error)
            CheckState.Reading -> Text(stringResource(R.string.state_checking))
            CheckState.NoText -> Text(stringResource(R.string.state_no_text), color = MaterialTheme.colorScheme.error)
            CheckState.OcrUnavailable -> Text(stringResource(R.string.state_ocr_unavailable), color = MaterialTheme.colorScheme.error)
            else -> {}
        }
        Button(
            onClick = { vm.check(text, Source.PASTE, truncated) },
            enabled = text.isNotBlank() && state != CheckState.Running,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text(stringResource(R.string.action_check), style = MaterialTheme.typography.titleMedium) }
    }
}
