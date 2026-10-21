package app.eye.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.eye.android.R
import app.eye.android.core.update.UpdateCardState

/** Scheda non bloccante in Home per l'aggiornamento dell'app (Google Play, flusso flexible). */
@Composable
fun UpdateCard(state: UpdateCardState, onUpdate: () -> Unit, onLater: () -> Unit, onRestart: () -> Unit) {
    if (state == UpdateCardState.Hidden) return
    EyeCard(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(if (state == UpdateCardState.Ready) R.string.update_ready_title else R.string.update_available_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            when (state) {
                is UpdateCardState.Available -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onUpdate, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.update_now)) }
                    TextButton(onClick = onLater, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.update_later)) }
                }
                UpdateCardState.Downloading -> Text(stringResource(R.string.update_downloading), style = MaterialTheme.typography.bodyLarge)
                UpdateCardState.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onRestart, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.update_restart)) }
                }
                UpdateCardState.Hidden -> Unit
            }
        }
    }
}
