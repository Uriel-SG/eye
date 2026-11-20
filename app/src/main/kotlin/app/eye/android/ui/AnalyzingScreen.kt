package app.eye.android.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.eye.android.R

/** Animazioni di sistema disattivate (scala 0): il logo resta fermo. */
@Composable
private fun reducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        runCatching { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/**
 * Schermata mostrata subito mentre il controllo e' in corso (regole piu' eventuale analisi dell'AI collegata), per esempio dopo il tocco
 * su un avviso. Quando l'analisi finisce la sostituisce il verdetto. Indietro riporta alla Home.
 */
@Composable
fun AnalyzingScreen(vm: AppViewModel, onBack: () -> Unit) {
    val aiBusy by vm.aiAnalyzing.collectAsState()
    val title = stringResource(R.string.analyzing_title)
    val aiLine = stringResource(R.string.ai_autoan_running)
    val cd = stringResource(R.string.analyzing_cd)
    val still = reducedMotion()
    val t = rememberInfiniteTransition(label = "analyzing")
    val pulse by t.animateFloat(
        0.94f, 1.06f,
        infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse",
    )
    val scale = if (still) 1f else pulse
    ScreenScaffold(stringResource(R.string.app_name), onBack = onBack, scroll = false) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(24.dp).semantics(mergeDescendants = true) {
                contentDescription = if (aiBusy) "$cd. $aiLine" else cd
                liveRegion = LiveRegionMode.Polite
            },
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // rotellina che gira attorno all'occhio di Eye
            Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.fillMaxSize(), strokeWidth = 5.dp)
                EyeIcon(Modifier.size(96.dp).graphicsLayer { scaleX = scale; scaleY = scale })
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            if (aiBusy) Text(aiLine, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}
