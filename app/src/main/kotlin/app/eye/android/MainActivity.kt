package app.eye.android

import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import app.eye.android.core.QuickIntents
import app.eye.engine.Source
import android.os.Bundle
import androidx.activity.ComponentActivity
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import app.eye.android.data.ThemeMode
import kotlinx.coroutines.flow.first
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.eye.android.core.CheckUseCase
import app.eye.android.ui.AppNav
import app.eye.android.ui.AppViewModel
import app.eye.android.ui.theme.EyeTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels { AppViewModel.Factory((application as EyeApp).container) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) { handleShare(intent); handleQuickAction(intent); handleAlert(intent); handleOpenCatalog(intent) }
        // Tema letto prima del primo frame (DataStore locale, pochi ms): niente flash del tema sbagliato.
        val initialMode = runCatching { kotlinx.coroutines.runBlocking { (application as EyeApp).container.settings.settings.first().themeMode } }
            .getOrDefault(ThemeMode.SYSTEM)
        setContent {
            val settings by vm.settings.collectAsState()
            val mode = if (settings.onboardingSeen == null) initialMode else settings.themeMode
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK, ThemeMode.OLED -> true
            }
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
                onDispose {}
            }
            EyeTheme(familyMode = settings.familyMode, dark = dark, oled = mode == ThemeMode.OLED) { AppNav(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
        handleQuickAction(intent)
        handleAlert(intent)
        handleOpenCatalog(intent)
    }

    /** Tocco su un avviso della Protezione automatica: il testo arriva solo dalla memoria dell'app (chiave casuale nell'Intent). */
    private fun handleAlert(intent: Intent?) {
        if (intent?.action != app.eye.android.core.notif.AlertNotifier.ACTION_OPEN_ALERT) return
        val key = runCatching { intent.getStringExtra(app.eye.android.core.notif.AlertNotifier.EXTRA_KEY) }.getOrNull()
        intent.action = null
        intent.removeExtra(app.eye.android.core.notif.AlertNotifier.EXTRA_KEY)
        val text = app.eye.android.core.notif.PendingAlertTexts.shared.take(key)
        if (text == null) Toast.makeText(this, R.string.alert_expired, Toast.LENGTH_LONG).show() else vm.onShared(text)
    }

    private fun handleOpenCatalog(intent: Intent?) {
        if (intent?.action != app.eye.android.core.notif.CampaignNotifier.ACTION_OPEN_CATALOG) return
        intent.action = null
        vm.requestOpenCatalog()
    }

    private var pendingClipboard = false

    private fun handleQuickAction(intent: Intent?) {
        if (intent?.action == QuickIntents.ACTION_CHECK_CLIPBOARD) {
            pendingClipboard = true
            intent.action = null // non rileggere gli appunti a una ricreazione
        }
    }

    /** Appunti letti solo con l'activity in primo piano e con il focus (Android 10+ lo impone comunque). */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !pendingClipboard) return
        pendingClipboard = false
        val cm = getSystemService(ClipboardManager::class.java)
        val raw = runCatching { cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this) }.getOrNull()
        val text = QuickIntents.clean(raw)
        if (text == null) Toast.makeText(this, R.string.clipboard_empty_notice, Toast.LENGTH_LONG).show()
        else vm.check(text, Source.PASTE)
    }

    /** Input non fidato (SEC-01/05): solo ACTION_SEND con MIME atteso; testo troncato, mai elaborato qui. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val type = intent.type ?: return
        when {
            type == "text/plain" -> {
                val text = runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() }.getOrNull()
                vm.onShared(text?.take(CheckUseCase.MAX_CHARS + 1))
            }
            type.startsWith("image/") -> {
                val uri = runCatching { androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java) }.getOrNull()
                if (uri != null && uri.scheme == "content") vm.ocr(this, uri)
            }
        }
    }
}
