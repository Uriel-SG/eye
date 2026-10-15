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

    fun shareText(context: Context, text: String, subject: String? = null) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
