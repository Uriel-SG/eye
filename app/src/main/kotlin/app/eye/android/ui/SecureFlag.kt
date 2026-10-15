package app.eye.android.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** FLAG_SECURE (niente screenshot né anteprima in Recenti) finché [enabled]; rimosso quando si nasconde o si esce. */
@Composable
fun SecureWindow(enabled: Boolean) {
    val window = LocalContext.current.activity()?.window
    DisposableEffect(enabled, window) {
        if (enabled) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (enabled) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
