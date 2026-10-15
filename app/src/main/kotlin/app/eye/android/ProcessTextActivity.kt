package app.eye.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import app.eye.android.core.QuickIntents

/**
 * «Verifica con Eye» nel menu di selezione testo. Trasparente: inoltra il testo a MainActivity come
 * Share Target e si chiude. EXTRA_PROCESS_TEXT_READONLY si ignora: non restituiamo mai testo modificato.
 */
class ProcessTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && intent?.action == Intent.ACTION_PROCESS_TEXT) {
            val text = QuickIntents.clean(runCatching { intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT) }.getOrNull())
            if (text != null) {
                runCatching {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .setAction(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, text)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    )
                }
            }
        }
        finish()
    }
}
