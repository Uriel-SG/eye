package app.eye.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
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

private const val EYE_RING = "M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73,-4.39 -6,-7.5 -11,-7.5zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z"
private const val EYE_PUPIL = "M12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z"

/** Occhio a due colori: contorno [outline] e pupilla [pupil] (verde cyber come il logo). */
private fun eyeVector(outline: Color, pupil: Color): ImageVector =
    ImageVector.Builder("eye", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes(EYE_RING), fill = SolidColor(outline), pathFillType = PathFillType.EvenOdd)
        addPath(addPathNodes(EYE_PUPIL), fill = SolidColor(pupil))
    }.build()

/** Occhio di Eye: contorno blu (primario) e pupilla verde (più scura sui fondi chiari per il contrasto 3:1). */
@Composable
fun EyeIcon(modifier: Modifier = Modifier, outline: Color = MaterialTheme.colorScheme.primary, pupil: Color = MaterialTheme.extras.pupil) {
    val img = remember(outline, pupil) { eyeVector(outline, pupil) }
    Icon(rememberVectorPainter(img), contentDescription = null, tint = Color.Unspecified, modifier = modifier)
}

/** Icona del livello; «Nessun segnale» usa l'occhio con la pupilla verde. [pupil] va scelta in base al fondo su cui sta. */
@Composable
fun LevelIcon(level: Level, tint: Color, modifier: Modifier = Modifier, pupil: Color = MaterialTheme.extras.pupil) {
    if (level == Level.NESSUN_SEGNALE) EyeIcon(modifier, outline = tint, pupil = pupil)
    else Icon(level.icon(), contentDescription = null, tint = tint, modifier = modifier)
}

/** Card con bordo 1dp (2dp in Modalità Famiglia) come da DESIGN: forme 16, elevazione tonale. */
@Composable
fun EyeCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, borderOverride: BorderStroke? = null, content: @Composable () -> Unit) {
    val family = MaterialTheme.extras.familyMode
    val border = if (borderOverride != null) borderOverride
    else if (family) BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
    else if (isSystemInDarkTheme()) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = if (family) 1f else 0.9f))
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = colors, border = border) { content() }
    } else {
        Card(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = colors, border = border) { content() }
    }
}

/** Titoletto di sezione: blu (primario) e grassetto. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color, modifier = modifier.semantics { heading() })
}

/** Metadati (date, aggiornamenti, fonti): corsivo, colore secondario. */
@Composable
fun MetaText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** Riga con icona, titolo e sottotitolo, minimo 72dp. */
@Composable
fun IconRow(icon: ImageVector?, title: String, subtitle: String?, onClick: () -> Unit, minHeight: Int = 72, subtitleMaxLines: Int = Int.MAX_VALUE, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = minHeight.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon == null) EyeIcon(Modifier.size(28.dp))
        else Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
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
    backdrop: Boolean = false,
    snackbar: androidx.compose.material3.SnackbarHostState? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { if (snackbar != null) androidx.compose.material3.SnackbarHost(snackbar) },
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
        } else if (backdrop) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(pad)) {
                ShieldBackdrop()
                content(PaddingValues())
            }
        } else content(pad)
    }
}
