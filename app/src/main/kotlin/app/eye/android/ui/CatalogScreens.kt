package app.eye.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import app.eye.android.core.Catalog
import app.eye.android.core.ExternalIntents
import app.eye.android.core.ReportLink
import app.eye.android.core.ScamCard
import app.eye.android.core.ScamCategory

@Composable
private fun ScamCategory.label(): String = stringResource(
    when (this) {
        ScamCategory.SMS -> R.string.cat_sms
        ScamCategory.BANCA -> R.string.cat_bank
        ScamCategory.PACCHI -> R.string.cat_parcel
        ScamCategory.PEDAGGI -> R.string.cat_toll
        ScamCategory.ENTI -> R.string.cat_public
        ScamCategory.QR -> R.string.cat_qr
        ScamCategory.WHATSAPP -> R.string.cat_whatsapp
        ScamCategory.MALWARE -> R.string.cat_malware
        ScamCategory.INVESTIMENTI -> R.string.cat_invest
        ScamCategory.ACQUISTI -> R.string.cat_shop
        ScamCategory.ALTRO -> R.string.cat_other
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(vm: AppViewModel, onCard: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf<ScamCategory?>(null) }
    val all by vm.catalog.collectAsState()
    val update by vm.catalogUpdate.collectAsState()
    val lastCheck by vm.lastCatalogCheck.collectAsState()
    val staleDays by vm.campaignStaleDays.collectAsState()
    val list = remember(query, cat, all) { Catalog.search(query, cat, all) }
    val ongoing = list.filter { it.ongoing }
    val rest = list.filter { !it.ongoing }
    val present = remember(all) { ScamCategory.entries.filter { c -> all.any { it.category == c } } }
    androidx.compose.runtime.LaunchedEffect(all) { vm.markCatalogSeen() }
    val updatedText = if (lastCheck > 0) stringResource(R.string.catalog_updated_on, java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, java.util.Locale.ITALY).format(java.util.Date(lastCheck)))
    else stringResource(R.string.catalog_updated, Catalog.UPDATED)

    ScreenScaffold(
        stringResource(R.string.catalog_title), scroll = false,
        actions = {
            IconButton(onClick = vm::refreshCatalog, enabled = update != CatalogUpdate.LOADING, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.catalog_refresh))
            }
        },
    ) {
        LazyColumn(
            Modifier.fillMaxSize().padding(it),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.catalog_search)) }, modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = cat == null, onClick = { cat = null }, label = { Text(stringResource(R.string.cat_all)) }) }
                    items(present) { c ->
                        FilterChip(selected = cat == c, onClick = { cat = if (cat == c) null else c }, label = { Text(c.label()) })
                    }
                }
            }
            item {
                Column(Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        if (update == CatalogUpdate.LOADING) {
                            androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(updatedText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val msg = when (update) {
                        CatalogUpdate.UPDATED -> R.string.catalog_update_ok
                        CatalogUpdate.UP_TO_DATE -> R.string.catalog_update_same
                        CatalogUpdate.OFFLINE -> R.string.catalog_update_offline
                        CatalogUpdate.ERROR -> R.string.catalog_update_error
                        else -> null
                    }
                    if (msg != null) Text(stringResource(msg), style = MaterialTheme.typography.bodyMedium)
                    staleDays?.let { Text(stringResource(R.string.campaigns_stale, it.toInt()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                }
            }
            if (list.isEmpty()) item { Text(stringResource(R.string.catalog_empty)) }
            if (ongoing.isNotEmpty()) item { SectionTitle(stringResource(R.string.catalog_ongoing), Modifier.padding(top = 8.dp)) }
            items(ongoing, key = { c -> c.id }) { c -> CatalogRow(c, onCard) }
            if (rest.isNotEmpty() && ongoing.isNotEmpty()) item { SectionTitle(stringResource(R.string.catalog_all_cards), Modifier.padding(top = 8.dp)) }
            items(rest, key = { c -> c.id }) { c -> CatalogRow(c, onCard) }
        }
    }
}

@Composable
private fun CatalogRow(c: ScamCard, onCard: (String) -> Unit) {
    EyeCard(onClick = { onCard(c.id) }) {
        IconRow(Icons.Rounded.Visibility, c.name, c.summary, onClick = { onCard(c.id) }, subtitleMaxLines = 3) {
            if (c.ongoing) {
                Text(stringResource(R.string.catalog_ongoing), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null)
        }
    }
}

/** yyyy-MM-dd -> data nel formato del telefono; se non è nel formato atteso, il testo com'è. */
internal fun formatIsoDate(iso: String): String = runCatching {
    val d = java.time.LocalDate.parse(iso)
    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, java.util.Locale.ITALY).format(java.util.Date.from(d.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()))
}.getOrDefault(iso)

@Composable
fun CatalogDetailScreen(vm: AppViewModel, id: String, onBack: () -> Unit) {
    val all by vm.catalog.collectAsState()
    val ctx = LocalContext.current
    val card: ScamCard? = Catalog.byId(id, all)
    var confirm by remember { mutableStateOf<ReportLink?>(null) }
    ScreenScaffold(
        card?.name ?: stringResource(R.string.catalog_title), onBack = onBack,
        actions = {
            if (card != null) IconButton(onClick = {
                ExternalIntents.shareText(ctx, card.name + "\n\n" + card.recognize.joinToString("\n") { "- $it" })
            }) { Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.action_share)) }
        },
    ) {
        if (card == null) return@ScreenScaffold
        Text(card.category.label(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(card.summary, style = MaterialTheme.typography.bodyLarge)
        card.updatedAt?.let { Text(stringResource(R.string.catalog_card_updated, formatIsoDate(it)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        SectionTitle(stringResource(R.string.catalog_recognize))
        card.recognize.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge) }
        SectionTitle(stringResource(R.string.catalog_example))
        EyeCard { Text(card.example, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge) }
        SectionTitle(stringResource(R.string.catalog_todo))
        card.actions.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodyLarge) }
        SectionTitle(stringResource(R.string.catalog_report_where))
        card.reportLinks.forEach { l -> TextButton(onClick = { confirm = l }, modifier = Modifier.padding(vertical = 4.dp)) { Text(l.label) } }
    }
    confirm?.let { l ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.report_open_confirm, ExternalIntents.host(l.url))) },
            confirmButton = {
                // il tocco è esplicito e l URL del feed si rivalida ora: solo https verso domini ufficiali, mai in automatico
                TextButton(onClick = { if (app.eye.engine.CampaignFeedVerifier.isOfficialHttpsUrl(l.url)) ExternalIntents.openUrl(ctx, l.url); confirm = null }) { Text(stringResource(R.string.action_open)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
