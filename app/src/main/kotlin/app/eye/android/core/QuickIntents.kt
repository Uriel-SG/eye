package app.eye.android.core

/** Parsing puro (testabile su JVM) degli input degli accessi rapidi. Input non fidato (SEC-01). */
object QuickIntents {
    /** Azione interna: MainActivity legge gli appunti solo dopo aver ottenuto il focus. */
    const val ACTION_CHECK_CLIPBOARD = "app.eye.android.action.CHECK_CLIPBOARD"

    /** Testo selezionato o copiato: null se vuoto/bianco, altrimenti al massimo MAX_CHARS + 1 caratteri (per segnalare il troncamento). */
    fun clean(raw: CharSequence?): String? {
        val s = runCatching { raw?.toString() }.getOrNull() ?: return null
        if (s.isBlank()) return null
        return s.take(CheckUseCase.MAX_CHARS + 1)
    }
}
