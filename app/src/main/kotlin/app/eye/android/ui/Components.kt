package app.eye.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.eye.android.R
import app.eye.android.ui.theme.extras
import app.eye.engine.Level

fun Level.icon(): ImageVector = when (this) {
    Level.PROBABILE_TRUFFA -> Icons.Rounded.GppBad
    Level.SOSPETTO -> Icons.Rounded.Warning
    Level.NESSUN_SEGNALE -> Icons.Rounded.Visibility
}

/** Card con bordo 1dp (2dp in Modalità Famiglia) come da DESIGN: forme 16, elevazione tonale. */
@Composable
fun EyeCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val family = MaterialTheme.extras.familyMode
    val border = if (family) BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
    else if (isSystemInDarkTheme()) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = colors, border = border) { content() }
    } else {
        Card(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = colors, border = border) { content() }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.semantics { heading() })
}

/** Riga con icona, titolo e sottotitolo, minimo 72dp. */
@Composable
fun IconRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit, minHeight: Int = 72, subtitleMaxLines: Int = Int.MAX_VALUE, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = minHeight.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = subtitleMaxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** Schermata con top bar e contenuto scorrevole, margini 16dp. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    scroll: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = { actions() },
            )
        },
    ) { pad ->
        if (scroll) {
            Column(
                Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) { content(PaddingValues()) }
        } else content(pad)
    }
}
