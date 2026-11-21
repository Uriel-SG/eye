package app.eye.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import app.eye.android.data.HistoryEntity

/**
 * Voce di cronologia [id], decifrata fuori dal thread principale e osservata da sola (mai tutta la cronologia).
 * Null finche' non e' caricata, se [id] e' null o se la voce non c'e' piu' / non e' leggibile.
 */
@Composable
fun rememberHistoryEntry(vm: AppViewModel, id: Long?): State<HistoryEntity?> =
    remember(id) { vm.historyEntry(id) }.collectAsState(initial = null)
