package app.eye.android.core

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.eye.android.R

/** Tutte le uscite verso altre app passano da qui: sempre su azione esplicita dell'utente (SEC-20/SEC-09). */
object ExternalIntents {
    /** Solo http/https: altri schemi (intent:, javascript:, file:, content:) sono rifiutati. */
    fun isSafeWebUrl(url: String): Boolean {
        val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
        return scheme == "https" || scheme == "http"
    }

    fun host(url: String): String = runCatching { Uri.parse(url).host }.getOrNull() ?: url

    fun openUrl(context: Context, url: String): Boolean {
        if (!isSafeWebUrl(url)) return false
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) { false }
    }

    /**
     * Apre [url] (https) nel browser predefinito con un intent esplicito verso il browser (selettore CATEGORY_APP_BROWSER):
     * mai una WebView dentro Eye. Ripiega su ACTION_VIEW + BROWSABLE se il sistema non risolve il selettore.
     */
    fun openInBrowser(context: Context, url: String): Boolean {
        val uri = Uri.parse(url)
        if (uri.scheme?.lowercase() != "https") return false
        val viaBrowser = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
            .setData(uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { context.startActivity(viaBrowser); true } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (_: ActivityNotFoundException) { false }
        }
    }

    fun shareText(context: Context, text: String, subject: String? = null) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, ShareTexts.safeOutgoing(text))
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }


    /** Copia il testo negli appunti su azione dell'utente; su Android 13+ la clip è marcata sensibile (contiene il messaggio). */
    fun copyToClipboard(context: Context, label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
        val clip = android.content.ClipData.newPlainText(label, text)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        runCatching { cm.setPrimaryClip(clip) }
    }

    /**
     * Email precompilata con mailto:?subject=&body= (Gmail e la maggior parte dei client la leggono; con ACTION_SEND + selector
     * Gmail perde oggetto e corpo). Senza app per SENDTO ripiega su ACTION_SEND text/plain con chooser.
     */
    fun composeEmail(context: Context, to: String, subject: String, body: String) {
        val mail = Intent(Intent.ACTION_SENDTO, Uri.parse(ShareTexts.mailtoUri(to, subject, ShareTexts.safeOutgoing(body)))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(mail) } catch (_: ActivityNotFoundException) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, ShareTexts.safeOutgoing(body))
            }
            context.startActivity(Intent.createChooser(send, context.getString(R.string.share_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
