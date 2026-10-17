package app.eye.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import app.eye.android.R
import app.eye.android.ui.theme.extras

/**
 * Sfondo decorativo (immagine dell'utente: scudo con lucchetto, circuiti, codice). Opacità bassa:
 * scuro/OLED ~22%; chiaro ~11% con immagine desaturata e invertita (fondo scuro -> bianco, tracce -> blu-grigio),
 * così non sporca il fondo chiaro. Assente in Modalità Famiglia. Va messo come primo figlio di un Box.
 * Non porta informazioni: nascosto a TalkBack.
 */
@Composable
fun BoxScope.ShieldBackdrop(modifier: Modifier = Modifier) {
    if (MaterialTheme.extras.familyMode) return
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val filter = remember(dark) {
        if (dark) null else ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    -0.17f, -0.57f, -0.06f, 0f, 255f,
                    -0.13f, -0.43f, -0.05f, 0f, 255f,
                    -0.06f, -0.20f, -0.02f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }
    Image(
        painterResource(R.drawable.bg_cyber), contentDescription = null,
        modifier = modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.BottomEnd,
        alpha = if (dark) 0.22f else 0.14f, colorFilter = filter,
    )
}
